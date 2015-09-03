/**
 * Copyright (C) 2012-2015, Markus Sprunck
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - The name of its contributor may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package com.sw_engineering_candies.fem.client;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class Solver {

	/** Thickness of 2D structure in mm */
	protected static final double THICKNESS = 10.0f;

	/** Poisson's Ratio of material*/
	protected static final double POISSION_RATIO = 0.2f;

	/** Young's Modulus of material in N/mm^2*/
	protected static final double YOUNGS_MODULUS = 1.6E+05f;

	/** Stiffness matrix of all elements */
	protected BandMatrixFull stiffness;

	/** Stiffness matrix with replaced of all known displacements with forces */
	protected BandMatrixFull stiffnessRearanged;

	/** Input forces in N */
	protected Vector inputForces;

	/** Resulting forces in N after solving */
	protected Vector solutionForces;

	/** Input displacements in mm */
	protected Vector inputDisplacements;

	/** Resulting displacements in mm after solving */
	protected Vector solutionDisplacements;

	/** Expected band with for global stiffness matrix resulting form the max node number distance in one element */
	protected int bandWidthExpected = 0;

	/** Total number of nodes in the model */
	protected int numberOfNodes = 0;

	/** Total number of elements in the model */
	protected int numberOfElements = 0;

	/** Stores the node information for each element in the model */
	protected Node[][] nodes;

	/** Scale factor for all coordinates of input model for x-axis */
	protected final static double ZOOM_X = 2.3;

	/** Scale factor for all coordinates of input model for y-axis */
	protected final static double ZOOM_Y = -2.3;

	/** Area of element, the map is used to calculate the value just once */
	protected Map<Integer, Double> elementAreas = new HashMap<Integer, Double>();

	/** Delta area of element, the map is used to calculate the value just once */
	protected Map<Integer, Double> elementDeltaAreas = new HashMap<Integer, Double>();

	/** Matrix of differential operators that convert displacements to strains using linear elasticity theory */
	protected final double[][] D = calculateDifferentialOperatorsMatrix();

	/**
	 * Parse model description and create global stiffness matrix
	 */
	public void createModel(final String model) {
		bandWidthExpected = parseModel(model);
		stiffness = calulateSystemStiffnessMatrix();
		stiffnessRearanged = rearangeSystemStiffnesMatrix();
		solutionForces = new Vector(stiffness.getMaxRows());
	}

	/**
	 * Calculate displacements with rearranged stiffness matrix and 
	 * resulting forces with original global stiffness matrix
	 */
	public void solve(final Vector forces) {
		this.inputForces = forces;
		this.solutionDisplacements = BandMatrixFull.solve(stiffnessRearanged, inputForces, 500);
		this.stiffness.times(solutionDisplacements, solutionForces);
	}

	/**
	 * Simulate gravity based on the sensor data of the mobile device 
	 * @param selecedElementId 
	 * @param isGravityActive 
	 */
	public Vector caluculateInputForces(final double beta, final double gamma, boolean isGravityActive, String selecedElementId) {

		// Calculate forces based on mobile sensor data
		final double yForce = 2 * Math.sin(-beta / 180 * Math.PI);
		final double xForce = 2 * Math.sin(-gamma / 180 * Math.PI);

		// Create forces for nodes which are not fixed
		final Vector forces = new Vector(inputForces.getMaxRows());
		for (int elementId = 1; elementId <= numberOfElements; elementId++) {

			double fy = yForce;
			double fx = xForce;
			String currentElementId = "E" + elementId;
			if (currentElementId.equals(selecedElementId) && !isGravityActive) {
				fy *= 50;
				fx *= 50;
			} 
			else {
				fy *= 0.00000001;
				fx *= 0.00000001;
			}

			final double area = calculateAreaOfElement(elementId);
			for (int cornerId = 1; cornerId < 4; cornerId++) {
				final int nodeId = getNodeIdByElementId(elementId, cornerId);

				if (!isNodeFixedInYAxis(nodeId)) {
					double valueY = forces.getValue(nodeId * 2 - 1);
					forces.setValue(nodeId * 2 - 1, valueY + fy * area);
				}

				if (!isNodeFixedInXAxis(nodeId)) {
					double valueX = forces.getValue(nodeId * 2 - 2);
					forces.setValue(nodeId * 2 - 2, valueX + fx * area);
				}
			}

		}
		return forces;
	}

	/**
	 * Just calculates the area of a triangle element 
	 */
	Double calculateAreaOfElement(final Integer elementId) {
		// Store values to avid repeated calculations
		if (!elementAreas.containsKey(elementId)) {
			final double Ax = nodes[elementId][1].x;
			final double Ay = nodes[elementId][1].y;
			final double Bx = nodes[elementId][2].x;
			final double By = nodes[elementId][2].y;
			final double Cx = nodes[elementId][3].x;
			final double Cy = nodes[elementId][3].y;
			final double value = Math.abs(((Cx - Bx) * (Ay - By) - (Bx - Ax) * (By - Cy))) / 2.0;
			elementAreas.put(elementId, value);
		}
		return elementAreas.get(elementId);
	}

	/**
	 * Just calculates the delta area of a triangle element 
	 */
	Double calculateDeltaAreaOfElement(final Integer elementId) {
		final double Ax = nodes[elementId][1].x + getDisplacementX(elementId, 1);
		final double Ay = nodes[elementId][1].y + getDisplacementY(elementId, 1);
		final double Bx = nodes[elementId][2].x + getDisplacementX(elementId, 2);
		final double By = nodes[elementId][2].y + getDisplacementY(elementId, 2);
		final double Cx = nodes[elementId][3].x + getDisplacementX(elementId, 3);
		final double Cy = nodes[elementId][3].y + getDisplacementY(elementId, 3);
		double value = Math.abs(((Cx - Bx) * (Ay - By) - (Bx - Ax) * (By - Cy))) / 2.0 / calculateAreaOfElement(elementId) - 1.0;
		return value * 1000;
	}

	private double[][] calculateStrainDisplacementMatrix(final int elementId, final double area) {

		final double[] a = new double[4];
		a[1] = nodes[elementId][2].x * nodes[elementId][3].y - nodes[elementId][3].x * nodes[elementId][2].y;
		a[2] = nodes[elementId][3].x * nodes[elementId][1].y - nodes[elementId][1].x * nodes[elementId][3].y;
		a[3] = nodes[elementId][1].x * nodes[elementId][2].y - nodes[elementId][2].x * nodes[elementId][1].y;

		final double[] b = new double[4];
		b[1] = nodes[elementId][2].y - nodes[elementId][3].y;
		b[2] = nodes[elementId][3].y - nodes[elementId][1].y;
		b[3] = nodes[elementId][1].y - nodes[elementId][2].y;

		final double[] c = new double[4];
		c[1] = -nodes[elementId][2].x + nodes[elementId][3].x;
		c[2] = -nodes[elementId][3].x + nodes[elementId][1].x;
		c[3] = -nodes[elementId][1].x + nodes[elementId][2].x;

		final double[][] B = new double[7][4];
		B[1][1] = b[1] / (area * 2);
		B[2][1] = 0;
		B[3][1] = b[2] / (area * 2);
		B[4][1] = 0;
		B[5][1] = b[3] / (area * 2);
		B[6][1] = 0;
		B[1][2] = 0;
		B[2][2] = c[1] / (area * 2);
		B[3][2] = 0;
		B[4][2] = c[2] / (area * 2);
		B[5][2] = 0;
		B[6][2] = c[3] / (area * 2);
		B[1][3] = c[1] / (area * 2);
		B[2][3] = b[1] / (area * 2);
		B[3][3] = c[2] / (area * 2);
		B[4][3] = b[2] / (area * 2);
		B[5][3] = c[3] / (area * 2);
		B[6][3] = b[3] / (area * 2);
		return B;
	}

	/**
	 * Stiffness matrix of a single element
	 */
	private double[][] calculateElementStiffnessMatrix(final double[][] D, final double[][] B, final int k, final double area) {

		final double volume = area * THICKNESS;
		final double[][] h = new double[4][7];
		h[1][1] = volume * (B[1][1] * D[1][1] + B[1][2] * D[2][1] + B[1][3] * D[3][1]);
		h[2][1] = volume * (B[1][1] * D[1][2] + B[1][2] * D[2][2] + B[1][3] * D[3][2]);
		h[3][1] = volume * (B[1][1] * D[1][3] + B[1][2] * D[2][3] + B[1][3] * D[3][3]);
		h[1][2] = volume * (B[2][1] * D[1][1] + B[2][2] * D[2][1] + B[2][3] * D[3][1]);
		h[2][2] = volume * (B[2][1] * D[1][2] + B[2][2] * D[2][2] + B[2][3] * D[3][2]);
		h[3][2] = volume * (B[2][1] * D[1][3] + B[2][2] * D[2][3] + B[2][3] * D[3][3]);
		h[1][3] = volume * (B[3][1] * D[1][1] + B[3][2] * D[2][1] + B[3][3] * D[3][1]);
		h[2][3] = volume * (B[3][1] * D[1][2] + B[3][2] * D[2][2] + B[3][3] * D[3][2]);
		h[3][3] = volume * (B[3][1] * D[1][3] + B[3][2] * D[2][3] + B[3][3] * D[3][3]);
		h[1][4] = volume * (B[4][1] * D[1][1] + B[4][2] * D[2][1] + B[4][3] * D[3][1]);
		h[2][4] = volume * (B[4][1] * D[1][2] + B[4][2] * D[2][2] + B[4][3] * D[3][2]);
		h[3][4] = volume * (B[4][1] * D[1][3] + B[4][2] * D[2][3] + B[4][3] * D[3][3]);
		h[1][5] = volume * (B[5][1] * D[1][1] + B[5][2] * D[2][1] + B[5][3] * D[3][1]);
		h[2][5] = volume * (B[5][1] * D[1][2] + B[5][2] * D[2][2] + B[5][3] * D[3][2]);
		h[3][5] = volume * (B[5][1] * D[1][3] + B[5][2] * D[2][3] + B[5][3] * D[3][3]);
		h[1][6] = volume * (B[6][1] * D[1][1] + B[6][2] * D[2][1] + B[6][3] * D[3][1]);
		h[2][6] = volume * (B[6][1] * D[1][2] + B[6][2] * D[2][2] + B[6][3] * D[3][2]);
		h[3][6] = volume * (B[6][1] * D[1][3] + B[6][2] * D[2][3] + B[6][3] * D[3][3]);

		final double[][] Ke = new double[7][7];
		Ke[1][1] = h[1][1] * B[1][1] + h[2][1] * B[1][2] + h[3][1] * B[1][3];
		Ke[2][1] = h[1][1] * B[2][1] + h[2][1] * B[2][2] + h[3][1] * B[2][3];
		Ke[3][1] = h[1][1] * B[3][1] + h[2][1] * B[3][2] + h[3][1] * B[3][3];
		Ke[4][1] = h[1][1] * B[4][1] + h[2][1] * B[4][2] + h[3][1] * B[4][3];
		Ke[5][1] = h[1][1] * B[5][1] + h[2][1] * B[5][2] + h[3][1] * B[5][3];
		Ke[6][1] = h[1][1] * B[6][1] + h[2][1] * B[6][2] + h[3][1] * B[6][3];
		Ke[1][2] = h[1][2] * B[1][1] + h[2][2] * B[1][2] + h[3][2] * B[1][3];
		Ke[2][2] = h[1][2] * B[2][1] + h[2][2] * B[2][2] + h[3][2] * B[2][3];
		Ke[3][2] = h[1][2] * B[3][1] + h[2][2] * B[3][2] + h[3][2] * B[3][3];
		Ke[4][2] = h[1][2] * B[4][1] + h[2][2] * B[4][2] + h[3][2] * B[4][3];
		Ke[5][2] = h[1][2] * B[5][1] + h[2][2] * B[5][2] + h[3][2] * B[5][3];
		Ke[6][2] = h[1][2] * B[6][1] + h[2][2] * B[6][2] + h[3][2] * B[6][3];
		Ke[1][3] = h[1][3] * B[1][1] + h[2][3] * B[1][2] + h[3][3] * B[1][3];
		Ke[2][3] = h[1][3] * B[2][1] + h[2][3] * B[2][2] + h[3][3] * B[2][3];
		Ke[3][3] = h[1][3] * B[3][1] + h[2][3] * B[3][2] + h[3][3] * B[3][3];
		Ke[4][3] = h[1][3] * B[4][1] + h[2][3] * B[4][2] + h[3][3] * B[4][3];
		Ke[5][3] = h[1][3] * B[5][1] + h[2][3] * B[5][2] + h[3][3] * B[5][3];
		Ke[6][3] = h[1][3] * B[6][1] + h[2][3] * B[6][2] + h[3][3] * B[6][3];
		Ke[1][4] = h[1][4] * B[1][1] + h[2][4] * B[1][2] + h[3][4] * B[1][3];
		Ke[2][4] = h[1][4] * B[2][1] + h[2][4] * B[2][2] + h[3][4] * B[2][3];
		Ke[3][4] = h[1][4] * B[3][1] + h[2][4] * B[3][2] + h[3][4] * B[3][3];
		Ke[4][4] = h[1][4] * B[4][1] + h[2][4] * B[4][2] + h[3][4] * B[4][3];
		Ke[5][4] = h[1][4] * B[5][1] + h[2][4] * B[5][2] + h[3][4] * B[5][3];
		Ke[6][4] = h[1][4] * B[6][1] + h[2][4] * B[6][2] + h[3][4] * B[6][3];
		Ke[1][5] = h[1][5] * B[1][1] + h[2][5] * B[1][2] + h[3][5] * B[1][3];
		Ke[2][5] = h[1][5] * B[2][1] + h[2][5] * B[2][2] + h[3][5] * B[2][3];
		Ke[3][5] = h[1][5] * B[3][1] + h[2][5] * B[3][2] + h[3][5] * B[3][3];
		Ke[4][5] = h[1][5] * B[4][1] + h[2][5] * B[4][2] + h[3][5] * B[4][3];
		Ke[5][5] = h[1][5] * B[5][1] + h[2][5] * B[5][2] + h[3][5] * B[5][3];
		Ke[6][5] = h[1][5] * B[6][1] + h[2][5] * B[6][2] + h[3][5] * B[6][3];
		Ke[1][6] = h[1][6] * B[1][1] + h[2][6] * B[1][2] + h[3][6] * B[1][3];
		Ke[2][6] = h[1][6] * B[2][1] + h[2][6] * B[2][2] + h[3][6] * B[2][3];
		Ke[3][6] = h[1][6] * B[3][1] + h[2][6] * B[3][2] + h[3][6] * B[3][3];
		Ke[4][6] = h[1][6] * B[4][1] + h[2][6] * B[4][2] + h[3][6] * B[4][3];
		Ke[5][6] = h[1][6] * B[5][1] + h[2][6] * B[5][2] + h[3][6] * B[5][3];
		Ke[6][6] = h[1][6] * B[6][1] + h[2][6] * B[6][2] + h[3][6] * B[6][3];

		return Ke;
	}

	/**
	 * Combines the single elements to a global stiffness matrix
	 */
	private BandMatrixFull calulateSystemStiffnessMatrix() {

		final BandMatrixFull K = new BandMatrixFull(numberOfNodes * 2, bandWidthExpected);

		for (int elementId = 1; elementId <= numberOfElements; elementId++) {

			final double area = calculateAreaOfElement(elementId);

			final double[][] B = calculateStrainDisplacementMatrix(elementId, area);

			final double[][] Ke = calculateElementStiffnessMatrix(D, B, elementId, area);

			// Add element stiffness matrix (Ke) to system stiffness matrix (K)
			for (int i = 1; i <= 3; i++) {
				for (int j = 1; j <= 3; j++) {

					final int col = nodes[elementId][i].nodeID * 2;
					final int row = nodes[elementId][j].nodeID * 2;

					final boolean isUpperPartOfMatrix = col - row > -1;
					if (isUpperPartOfMatrix) {
						final int r = row - 2;
						final int c = col - row;

						final double topLeft = K.getValue(r, c + r);
						K.setValue(r, c + r, topLeft + Ke[i * 2 - 1][j * 2 - 1]);

						final double topRight = K.getValue(r, c + r + 1);
						K.setValue(r, c + r + 1, topRight + Ke[i * 2][j * 2 - 1]);

						final double bottomRight = K.getValue(r + 1, c + r + 1);
						K.setValue(r + 1, c + r + 1, bottomRight + Ke[i * 2][j * 2]);
					}

					final boolean isAboveDiagonal = col - row > 0;
					if (isAboveDiagonal) {
						final int r = row - 2;
						final int c = col - row;

						final double bottomLeft = K.getValue(r + 1, c + r);
						K.setValue(r + 1, c + r, bottomLeft + Ke[i * 2 - 1][j * 2]);
					}
				}
			}
		}
		return K;
	}

	private double[][] calculateDifferentialOperatorsMatrix() {
		final double[][] D = new double[4][4];
		final double factor = YOUNGS_MODULUS / (1 + POISSION_RATIO) / (1 - 2 * POISSION_RATIO);
		D[1][1] = (1 - POISSION_RATIO) * factor;
		D[2][1] = POISSION_RATIO * factor;
		D[3][1] = 0;
		D[1][2] = POISSION_RATIO * factor;
		D[2][2] = (1 - POISSION_RATIO) * factor;
		D[3][2] = 0;
		D[1][3] = 0;
		D[2][3] = 0;
		D[3][3] = (1 - 2 * POISSION_RATIO) / 2 * factor;
		return D;
	}

	private BandMatrixFull rearangeSystemStiffnesMatrix() {

		final BandMatrixFull result = new BandMatrixFull(stiffness);

		for (int nodeId = 1; nodeId <= numberOfNodes; nodeId++) {

			if (isNodeFixedInYAxis(nodeId)) {
				final int REI = nodeId * 2;
				for (int i = 1; i <= numberOfNodes; i++) {
					final int reihe = i * 2;
					if (REI > reihe) {
						setNodeForceX(i, getNodeForceX(i) - getNodeDisplacementX(nodeId) * result.getValue(reihe - 1, REI - 1));
						setNodeForceY(i, getNodeForceY(i) - getNodeDisplacementY(nodeId) * result.getValue(reihe, REI - 1));
						result.setValue(reihe - 1, REI - 1, 0.0);
						result.setValue(reihe, REI - 1, 0.0);
					} else if (reihe - REI + 1 <= this.bandWidthExpected) {
						setNodeForceX(i, getNodeForceX(i) - getNodeDisplacementX(nodeId) * result.getValue(REI - 1, reihe));
						setNodeForceY(i, getNodeForceY(i) - getNodeDisplacementY(nodeId) * result.getValue(REI - 1, reihe + 1));
						result.setValue(REI - 1, reihe, 0.0);
						result.setValue(REI - 1, reihe + 1, 0.0);
					}
				}
				setNodeForceY(nodeId, getNodeDisplacementY(nodeId));
				result.setValue(REI - 1, REI - 1, 1.0);
			}

			if (isNodeFixedInXAxis(nodeId)) {
				final int REI = nodeId * 2 - 1;
				for (int i = 1; i <= numberOfNodes; i++) {
					final int reihe = i * 2 - 1;
					if (REI > reihe) {
						setNodeForceX(i, getNodeForceX(i) - getNodeDisplacementX(nodeId) * result.getValue(reihe - 1, REI - 1));
						setNodeForceY(i, getNodeForceY(i) - getNodeDisplacementY(nodeId) * result.getValue(reihe, REI - 1));
						result.setValue(reihe - 1, REI - 1, 0.0);
						result.setValue(reihe, REI - 1, 0.0);
					} else if (reihe - REI + 1 <= this.bandWidthExpected) {
						setNodeForceX(i, getNodeForceX(i) - getNodeDisplacementX(nodeId) * result.getValue(REI - 1, reihe));
						setNodeForceY(i, getNodeForceY(i) - getNodeDisplacementY(nodeId) * result.getValue(REI - 1, reihe + 1));
						result.setValue(REI - 1, reihe, 0.0);
						result.setValue(REI - 1, reihe + 1, 0.0);
					}
				}
				setNodeForceX(nodeId, getNodeDisplacementX(nodeId));
				result.setValue(REI - 1, REI - 1, 1.0);
			}
		}

		// Now all forces are known and will be reseted to zero
		for (int index = 0; index < numberOfNodes * 2; index++) {
			if (Double.isNaN(inputForces.getValue(index))) {
				inputForces.setValue(index, 0.0);
			}
		}

		return result;
	}

	public int getNumberOfNodes() {
		return numberOfNodes;
	}

	public int getNumberOfElements() {
		return numberOfElements;
	}

	public Vector getInputForces() {
		return inputForces;
	}

	public Vector getInputDisplacements() {
		return inputDisplacements;
	}

	public double[] getSolutionForces() {
		return solutionForces.values;
	}

	public double getSolutionNodeDisplacementY(final int nodeId) {
		return solutionDisplacements.getValue(nodeId * 2 - 1);
	}

	public double getSolutionNodeDisplacementX(final int nodeId) {
		return solutionDisplacements.getValue(nodeId * 2 - 2);
	}

	public double getSolutionNodeForceY(final int nodeId) {
		return solutionForces.getValue(nodeId * 2 - 1);
	}

	public double getSolutionNodeForceX(final int nodeId) {
		return solutionForces.getValue(nodeId * 2 - 2);
	}

	public double getNodeForceY(final int nodeId) {
		return inputForces.getValue(nodeId * 2 - 1);
	}

	public double getNodeForceX(final int nodeId) {
		return inputForces.getValue(nodeId * 2 - 2);
	}

	private double getNodeDisplacementY(final int nodeId) {
		return inputDisplacements.getValue(nodeId * 2 - 1);
	}

	private double getNodeDisplacementX(final int nodeId) {
		return inputDisplacements.getValue(nodeId * 2 - 2);
	}

	public void setNodeForceY(final int nodeId, final double value) {
		inputForces.setValue(nodeId * 2 - 1, value);
	}

	public void setNodeForceX(final int nodeId, final double value) {
		inputForces.setValue(nodeId * 2 - 2, value);
	}

	public boolean isNodeFixedInYAxis(final int nodeId) {
		return !Double.isNaN(inputDisplacements.getValue(nodeId * 2 - 1));
	}

	public boolean isNodeFixedInXAxis(final int nodeId) {
		return !Double.isNaN(inputDisplacements.getValue(nodeId * 2 - 2));
	}

	public void setInputDisplacementX(final int nodeId, final Double value) {
		inputDisplacements.setValue(nodeId * 2 - 2, value);
	}

	public void setInputDisplacementY(final int nodeId, final Double value) {
		inputDisplacements.setValue(nodeId * 2 - 1, value);
	}

	public int getNodeIdByElementId(final int elementId, final int cornerId) {
		return nodes[elementId][cornerId].nodeID;
	}

	public void setNodeIdByElementIdX(final int elementId, final int cornerId, final double value) {
		nodes[elementId][cornerId].x = value;
	}

	public void setNodeIdByElementIdY(final int elementId, final int cornerId, final double value) {
		nodes[elementId][cornerId].y = value;
	}

	public double getNodeX(final int elementId, final int cornerId) {
		return nodes[elementId][cornerId].x;
	}

	public double getDisplacementX(final int elementId, final int cornerId) {
		return solutionDisplacements.getValue(nodes[elementId][cornerId].nodeID * 2 - 2);
	}

	public double getDisplacementY(final int elementId, final int cornerId) {
		return solutionDisplacements.getValue(nodes[elementId][cornerId].nodeID * 2 - 1);
	}

	public double getNodeY(final int elementId, final int cornerId) {
		return nodes[elementId][cornerId].y;
	}

	public void setNodeIdByElementIdnodeID(final int elementId, final int cornerId, final int value) {
		nodes[elementId][cornerId].nodeID = value;
	}

	public int parseModel(final String input) {

		int bandWidth = 0;

		final List<Node> temporaryNodes = new LinkedList<Node>();
		temporaryNodes.add(new Node());

		final List<Integer[]> temporaryElements = new LinkedList<Integer[]>();
		temporaryElements.add(new Integer[5]);

		final String[] lines = input.toString().split("\\n");
		for (final String line : lines) {
			if (!line.trim().isEmpty()) {
				final String[] args = line.replace("  ", " ").split(" ");
				if (0 == args[0].trim().compareToIgnoreCase("N")) {
					this.numberOfNodes++;
					final int number = Integer.valueOf(args[1].trim());
					for (int index = temporaryNodes.size(); index <= number; index++) {
						temporaryNodes.add(new Node());
					}

					final Double first = Double.valueOf(args[2].trim()) * ZOOM_X;
					final Double second = Double.valueOf(args[3].trim()) * ZOOM_Y;

					temporaryNodes.get(number).x = first;
					temporaryNodes.get(number).y = second;
				}
				if (0 == args[0].trim().compareToIgnoreCase("E")) {
					this.numberOfElements++;
					final int number = Integer.valueOf(args[1].trim());
					for (int index = temporaryElements.size(); index <= number; index++) {
						temporaryElements.add(new Integer[5]);
					}

					final Integer first = Integer.valueOf(args[2].trim());
					final Integer second = Integer.valueOf(args[3].trim());
					final Integer third = Integer.valueOf(args[4].trim());

					temporaryElements.get(this.getNumberOfElements())[1] = number;
					temporaryElements.get(this.getNumberOfElements())[2] = first;
					temporaryElements.get(this.getNumberOfElements())[3] = second;
					temporaryElements.get(this.getNumberOfElements())[4] = third;

					final int max = Math.max(Math.max(first, second), third);
					final int min = Math.min(Math.min(first, second), third);
					final int bandwidthOfElement = (1 + max - min) * 2;
					bandWidth = Math.max(bandWidth, bandwidthOfElement);
				}
				if (0 == args[0].trim().compareToIgnoreCase("D")) {

					if (null == this.inputDisplacements) {
						this.inputDisplacements = new Vector(numberOfNodes * 2);
						for (int index = 0; index < this.numberOfNodes * 2; index++) {
							this.inputDisplacements.setValue(index, Double.NaN);
						}
					}

					final int number = Integer.valueOf(args[1].trim());
					if (0 == args[2].trim().compareToIgnoreCase("x")) {
						this.setInputDisplacementX(number, Double.valueOf(args[3].trim()));
					}
					if (0 == args[2].trim().compareToIgnoreCase("y")) {
						this.setInputDisplacementY(number, Double.valueOf(args[3].trim()));
					}
				}
				if (0 == args[0].trim().compareToIgnoreCase("F")) {

					if (null == this.inputForces) {
						this.inputForces = new Vector(numberOfNodes * 2);
						for (int index = 0; index < this.numberOfNodes * 2; index++) {
							this.inputForces.setValue(index, Double.NaN);
						}
					}

					final int number = Integer.valueOf(args[1].trim());
					if (0 == args[2].trim().compareToIgnoreCase("x")) {
						this.setNodeForceX(number, Double.valueOf(args[3].trim()));
					}
					if (0 == args[2].trim().compareToIgnoreCase("y")) {
						this.setNodeForceY(number, Double.valueOf(args[3].trim()));
					}
				}
			}
		}

		// Create all nodes
		this.nodes = new Node[1 + this.numberOfElements][1 + this.numberOfNodes];
		for (int i1 = 0; i1 < 1 + this.numberOfElements; i1++) {
			for (int k = 0; k < 1 + this.numberOfNodes; k++) {
				this.nodes[i1][k] = new Node();
			}
		}

		for (int i = 1; i <= this.getNumberOfElements(); i++) {
			this.setNodeIdByElementIdX(temporaryElements.get(i)[1], 1, temporaryNodes.get(temporaryElements.get(i)[2]).x);
			this.setNodeIdByElementIdX(temporaryElements.get(i)[1], 2, temporaryNodes.get(temporaryElements.get(i)[3]).x);
			this.setNodeIdByElementIdX(temporaryElements.get(i)[1], 3, temporaryNodes.get(temporaryElements.get(i)[4]).x);

			this.setNodeIdByElementIdY(temporaryElements.get(i)[1], 1, temporaryNodes.get(temporaryElements.get(i)[2]).y);
			this.setNodeIdByElementIdY(temporaryElements.get(i)[1], 2, temporaryNodes.get(temporaryElements.get(i)[3]).y);
			this.setNodeIdByElementIdY(temporaryElements.get(i)[1], 3, temporaryNodes.get(temporaryElements.get(i)[4]).y);

			this.setNodeIdByElementIdnodeID(temporaryElements.get(i)[1], 1, temporaryElements.get(i)[2]);
			this.setNodeIdByElementIdnodeID(temporaryElements.get(i)[1], 2, temporaryElements.get(i)[3]);
			this.setNodeIdByElementIdnodeID(temporaryElements.get(i)[1], 3, temporaryElements.get(i)[4]);
		}

		return bandWidth;
	}

	public Vector calculateSolutionsDisplacementsMean() {
		final Vector result = new Vector(getNumberOfElements() * 2);
		final int numberOfElements = getNumberOfElements();
		double delta1, delta2, delta3;
		for (int elementId = 1; elementId <= numberOfElements; elementId++) {
			delta1 = getDisplacementX(elementId, 1);
			delta2 = getDisplacementX(elementId, 2);
			delta3 = getDisplacementX(elementId, 3);
			result.setValue(2 * elementId - 2, (delta3 + delta2 + delta1) / 3.0);
			delta1 = getDisplacementY(elementId, 1);
			delta2 = getDisplacementY(elementId, 2);
			delta3 = getDisplacementY(elementId, 3);
			result.setValue(2 * elementId - 1, (delta3 + delta2 + delta1) / 3.0);
		}
		return result;
	}

	public String getJSON() {

		final int numberOfElements = getNumberOfElements();

		final Vector deltaVector = calculateSolutionsDisplacementsMean();

		final HashMap<Integer, Boolean> nodeIds = new HashMap<Integer, Boolean>();
		final StringBuilder pre = new StringBuilder("[");
		for (int elementId = 1; elementId <= numberOfElements; elementId++) {
			pre.append("[");
			for (int cornerId = 1; cornerId < 4; cornerId++) {
				final int nodeId = getNodeIdByElementId(elementId, cornerId);
				final double x_force = getSolutionNodeForceX(nodeId);
				final double y_force = getSolutionNodeForceY(nodeId);
				final double x_d = getSolutionNodeDisplacementX(nodeId);
				final double y_d = getSolutionNodeDisplacementY(nodeId);
				final boolean x_fixed = isNodeFixedInXAxis(nodeId);
				final boolean y_fixed = isNodeFixedInYAxis(nodeId);
				final double x = getNodeX(elementId, cornerId);
				final double y = getNodeY(elementId, cornerId);
				final double deltaX = deltaVector.getValue(2 * elementId - 2);
				final double deltaY = deltaVector.getValue(2 * elementId - 1);
				final double deltaArea = calculateDeltaAreaOfElement(elementId);

				pre.append("\n{\"id\": ").append(nodeId);
				pre.append(", \"x_force\" : ").append(x_force);
				pre.append(", \"y_force\" : ").append(y_force);
				pre.append(", \"x_d\" : ").append(x_d);
				pre.append(", \"y_d\" : ").append(y_d);
				pre.append(", \"x_fixed\" : ").append(x_fixed);
				pre.append(", \"y_fixed\" : ").append(y_fixed);
				pre.append(", \"x\" : ").append(x);
				pre.append(", \"y\" : ").append(y);
				pre.append(", \"deltaX\" : ").append(deltaX);
				pre.append(", \"deltaY\" : ").append(deltaY);
				pre.append(", \"deltaArea\" : ").append(deltaArea).append(" }\n");
				if (cornerId < 3) {
					pre.append(',');
				}
				nodeIds.put(nodeId, true);
			}
			pre.append("]");
			if (elementId < numberOfElements) {
				pre.append(',');
			}
		}
		pre.append("]");

		return pre.toString();
	}

}
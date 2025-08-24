////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.chemistry.opencmis.inmemory.content.fractal;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;

final class FractalCalculator {
    private int[] colorMap;
    protected int[][] noIterations;
    private double delta;
    private double iRangeMax;
    private double iRangeMin;
    private int maxIterations;
    private ComplexRectangle newRect;
    private int numColors;
    private int imageHeight;
    private int imageWidth;
    private double rRangeMax;
    private double rRangeMin;
    // For Julia set:
    private double cJuliaPointR = 0.0; // Real
    private double cJuliaPointI = 0.0; // Imaginary
    boolean useJulia = false;

    public FractalCalculator(ComplexRectangle complRect, int maxIters, int imgWidth, int imgHeight, int[] colMap,
            ComplexPoint juliaPoint) {
        maxIterations = maxIters;
        newRect = complRect;
        imageWidth = imgWidth;
        imageHeight = imgHeight;
        colorMap = Arrays.copyOf(colMap, colMap.length);
        numColors = colorMap.length;
        rRangeMin = newRect.getRMin();
        rRangeMax = newRect.getRMax();
        iRangeMin = newRect.getIMin();
        iRangeMax = newRect.getIMax();
        delta = (rRangeMax - rRangeMin) / imageWidth;
        if (null != juliaPoint) {
            cJuliaPointR = juliaPoint.getReal();
            cJuliaPointI = juliaPoint.getImaginary();
            useJulia = true;
        }
    }

    public int[][] calcFractal() {
        noIterations = new int[ imageWidth ][ imageHeight ];

        // For each pixel...
        for (int x = 0; x < imageWidth; x++) {
            for (int y = 0; y < imageHeight; y++) {
                double zR = rRangeMin + x * delta;
                double zI = iRangeMin + (imageHeight - y) * delta;

                // Is the point inside the set?
                if (useJulia) {
                    noIterations[x][y] = testPointJuliaSet(zR, zI, maxIterations);
                } else {
                    noIterations[x][y] = testPointMandelbrot(zR, zI, maxIterations);
                }            
            }
        }
        return noIterations;
    }

    public BufferedImage mapItersToColors(int[][] iterations) {

        // Assign a color to every pixel ( x , y ) in the Image, corresponding
        // to
        // one point, z, in the imaginary plane ( zr, zi ).
        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_3BYTE_BGR );

        // For each pixel...
        for (int x = 0; x < imageWidth; x++) {
            for (int y = 0; y < imageHeight; y++) {
                int color = getColor(iterations[x][y]);
                image.setRGB(x, y, color);
            }
        }
        return image;
    }

    protected int getColor(int numIterations) {
        int c = Color.black.getRGB();

        if (numIterations != 0) {
            // The point is outside the set. It gets a color based on the number
            // of iterations it took to know this.
            int colorNum = (int) (numColors * (1.0 - (float) numIterations / (float) maxIterations));
            colorNum = (colorNum == numColors) ? 0 : colorNum;

            c = colorMap[colorNum];
        }
        return c;
    }

    private int testPointMandelbrot(double cR, double cI, int maxIterations) {
        // Is the given complex point, (cR, cI), in the Mandelbrot set?
        // Use the formula: z <= z*z + c, where z is initially equal to c.
        // If |z| >= 2, then the point is not in the set.
        // Return 0 if the point is in the set; else return the number of
        // iterations it took to decide that the point is not in the set.
        double zR = cR;
        double zI = cI;

        for (int i = 1; i <= maxIterations; i++) {
            // To square a complex number: (a+bi)(a+bi) = a*a - b*b + 2abi
            double zROld = zR;
            zR = zR * zR - zI * zI + cR;
            zI = 2 * zROld * zI + cI;

            // We know that if the distance from z to the origin is >= 2
            // then the point is out of the set. To avoid a square root,
            // we'll instead check if the distance squared >= 4.
            double distSquared = zR * zR + zI * zI;
            if (distSquared >= 4) {
                return i;
            }
        }
        return 0;
    }

    private int testPointJuliaSet(double zR, double zI, int maxIterations) {
        // Is the given complex point, (zR, zI), in the Julia set?
        // Use the formula: z <= z*z + c, where z is the point being tested,
        // and c is the Julia Set constant.
        // If |z| >= 2, then the point is not in the set.
        // Return 0 if the point is in the set; else return the number of
        // iterations it took to decide that the point is not in the set.
        for (int i = 1; i <= maxIterations; i++) {
            double zROld = zR;
            // To square a complex number: (a+bi)(a+bi) = a*a - b*b + 2abi
            zR = zR * zR - zI * zI + cJuliaPointR;
            zI = 2 * zROld * zI + cJuliaPointI;
            // We know that if the distance from z to the origin is >= 2
            // then the point is out of the set. To avoid a square root,
            // we'll instead check if the distance squared >= 4.
            double distSquared = zR * zR + zI * zI;
            if (distSquared >= 4) {
                return i;
            }
        }
        return 0;
    }
}
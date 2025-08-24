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


public class ComplexRectangle {
    private double iMin; // imaginary
    private double iMax;
    private double rMin; // real
    private double rMax;

    public ComplexRectangle(double r1, double r2, double i1, double i2) {
        set(r1, r2, i1, i2);
    }

    public ComplexRectangle() {
        set(0.0, 0.0, 0.0, 0.0);
    }

    public ComplexRectangle(ComplexRectangle cr) {
        set(cr);
    }

    public double getIMin() {
        return iMin;
    }

    public double getIMax() {
        return iMax;
    }

    public double getRMin() {
        return rMin;
    }

    public double getRMax() {
        return rMax;
    }

    public double getHeight() {
        return iMax - iMin;
    }

    public double getWidth() {
        return rMax - rMin;
    }

    public void set(ComplexRectangle cr) {
        set(cr.getRMin(), cr.getRMax(), cr.getIMin(), cr.getIMax());
    }

    public void set(ComplexPoint p1, ComplexPoint p2) {
        set(p1.getReal(), p2.getReal(), p1.getImaginary(), p2.getImaginary());
    }

    public void set(double r1, double r2, double i1, double i2) {
        if (r1 > r2) {
            rMin = r2;
            rMax = r1;
        } else {
            rMin = r1;
            rMax = r2;
        }
        if (i1 > i2) {
            iMin = i2;
            iMax = i1;
        } else {
            iMin = i1;
            iMax = i2;
        }
    }
}
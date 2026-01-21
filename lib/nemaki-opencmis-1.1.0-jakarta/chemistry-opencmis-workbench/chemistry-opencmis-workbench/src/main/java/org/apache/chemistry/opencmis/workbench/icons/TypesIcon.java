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
package org.apache.chemistry.opencmis.workbench.icons;

import java.awt.Graphics2D;
import java.awt.geom.GeneralPath;

public class TypesIcon extends AbstractWorkbenchIcon {

    public TypesIcon() {
        super();
    }

    public TypesIcon(int width, int height) {
        super(width, height);
    }

    public TypesIcon(int width, int height, boolean enabled) {
        super(width, height, enabled);
    }

    @Override
    protected int getOrginalHeight() {
        return 64;
    }

    @Override
    protected int getOrginalWidth() {
        return 64;
    }

    @Override
    protected void paint(Graphics2D g) {
        GeneralPath shape = new GeneralPath();

        shape.moveTo(56.113, 26.872);
        shape.lineTo(50.98, 21.962);
        shape.curveTo(50.974, 21.971, 50.968, 21.980999, 50.963, 21.99);
        shape.lineTo(34.126, 5.884);
        shape.curveTo(33.582, 5.338, 32.831, 5.0, 32.0, 5.0);
        shape.curveTo(31.997, 5.0, 31.995, 5.001, 31.992, 5.001);
        shape.lineTo(31.992, 4.993);
        shape.lineTo(13.0, 4.993);
        shape.lineTo(13.0, 5.0);
        shape.curveTo(11.343, 5.0, 10.0, 6.343, 10.0, 8.0);
        shape.lineTo(32.0, 8.0);
        shape.lineTo(56.081, 31.155);
        shape.lineTo(56.3, 30.9);
        shape.lineTo(56.302, 30.901);
        shape.curveTo(56.73, 30.383, 57.0, 29.726, 57.0, 29.0);
        shape.curveTo(57.0, 28.168, 56.66, 27.415, 56.113, 26.872);
        shape.closePath();
        shape.moveTo(54.997, 35.951);
        shape.curveTo(54.996002, 35.864, 54.993, 35.777, 54.984, 35.691);
        shape.curveTo(54.982002, 35.675003, 54.978, 35.661003, 54.976, 35.646004);
        shape.curveTo(54.966003, 35.560005, 54.955, 35.476006, 54.937, 35.392002);
        shape.curveTo(54.937, 35.390003, 54.937, 35.390003, 54.937, 35.388);
        shape.curveTo(54.814, 34.794, 54.515, 34.265, 54.097, 33.857002);
        shape.lineTo(31.126, 11.884);
        shape.curveTo(30.582, 11.338, 29.831, 11.0, 29.0, 11.0);
        shape.curveTo(28.997, 11.0, 28.995, 11.001, 28.992, 11.001);
        shape.lineTo(28.992, 10.993);
        shape.lineTo(10.0, 10.993);
        shape.lineTo(10.0, 11.0);
        shape.curveTo(8.343, 11.0, 7.0, 12.343, 7.0, 14.0);
        shape.lineTo(6.993, 14.0);
        shape.lineTo(6.993, 33.049);
        shape.lineTo(7.006, 33.049);
        shape.curveTo(7.021, 33.927, 7.415, 34.706, 8.029, 35.245);
        shape.lineTo(8.018001, 35.257);
        shape.lineTo(33.018, 58.257);
        shape.lineTo(33.024002, 58.252);
        shape.curveTo(33.419003, 58.6, 33.903004, 58.846, 34.442, 58.945);
        shape.curveTo(34.463, 58.95, 34.487, 58.953, 34.509003, 58.956);
        shape.curveTo(34.576004, 58.967003, 34.643, 58.978, 34.711002, 58.984);
        shape.curveTo(34.807, 58.995, 34.902, 59.0, 35.0, 59.0);
        shape.curveTo(35.916, 59.0, 36.734, 58.589, 37.284, 57.941);
        shape.lineTo(37.285, 57.943);
        shape.lineTo(54.285, 37.943);
        shape.lineTo(54.284, 37.941);
        shape.curveTo(54.73, 37.418, 55.0, 36.741, 55.0, 36.0);
        shape.curveTo(55.0, 35.984, 54.998, 35.968, 54.997, 35.951);
        shape.closePath();
        shape.moveTo(15.0, 23.0);
        shape.curveTo(12.791, 23.0, 11.0, 21.209, 11.0, 19.0);
        shape.curveTo(11.0, 16.791, 12.791, 15.0, 15.0, 15.0);
        shape.curveTo(17.209, 15.0, 19.0, 16.791, 19.0, 19.0);
        shape.curveTo(19.0, 21.209, 17.209, 23.0, 15.0, 23.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

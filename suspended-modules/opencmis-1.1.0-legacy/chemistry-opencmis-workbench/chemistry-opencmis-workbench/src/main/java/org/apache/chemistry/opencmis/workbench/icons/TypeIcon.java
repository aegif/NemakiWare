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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.GeneralPath;

public class TypeIcon extends AbstractWorkbenchIcon {

    public TypeIcon() {
        super();
    }

    public TypeIcon(int width, int height) {
        super(width, height);
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
    protected Color getColor() {
        return new Color(0x73a4d1);
    }

    @Override
    protected void paint(Graphics2D g) {
        GeneralPath shape = new GeneralPath();

        shape.moveTo(56.0, 33.0);
        shape.curveTo(56.0, 32.982, 55.998, 32.967, 55.997, 32.951);
        shape.curveTo(55.996002, 32.864, 55.993, 32.777, 55.984, 32.693);
        shape.curveTo(55.982002, 32.676003, 55.978, 32.662003, 55.976, 32.646);
        shape.curveTo(55.966003, 32.562, 55.955, 32.477, 55.937, 32.394);
        shape.curveTo(55.937, 32.392002, 55.937, 32.391003, 55.937, 32.389);
        shape.curveTo(55.814, 31.795, 55.515, 31.266, 55.097, 30.858);
        shape.lineTo(32.126, 8.884);
        shape.curveTo(31.582, 8.338, 30.831, 8.0, 30.0, 8.0);
        shape.curveTo(29.997, 8.0, 29.995, 8.001, 29.992, 8.001);
        shape.lineTo(29.992, 7.993);
        shape.lineTo(11.0, 7.993);
        shape.lineTo(11.0, 8.0);
        shape.curveTo(9.343, 8.0, 8.0, 9.343, 8.0, 11.0);
        shape.lineTo(7.993, 11.0);
        shape.lineTo(7.993, 30.049);
        shape.lineTo(8.006, 30.049);
        shape.curveTo(8.021, 30.927, 8.415, 31.706, 9.028999, 32.245);
        shape.lineTo(9.018, 32.257);
        shape.lineTo(34.017998, 55.257);
        shape.lineTo(34.024, 55.252);
        shape.curveTo(34.419, 55.6, 34.903, 55.843998, 35.441998, 55.945);
        shape.curveTo(35.462997, 55.95, 35.486996, 55.953, 35.509, 55.956);
        shape.curveTo(35.576, 55.967003, 35.642998, 55.978, 35.711, 55.984);
        shape.curveTo(35.807, 55.995, 35.902, 56.0, 36.0, 56.0);
        shape.curveTo(36.916, 56.0, 37.734, 55.589, 38.284, 54.941);
        shape.lineTo(38.285, 54.943);
        shape.lineTo(55.285, 34.943);
        shape.lineTo(55.284, 34.941);
        shape.curveTo(55.73, 34.418, 56.0, 33.741, 56.0, 33.0);
        shape.closePath();
        shape.moveTo(16.0, 20.0);
        shape.curveTo(13.791, 20.0, 12.0, 18.209, 12.0, 16.0);
        shape.curveTo(12.0, 13.791, 13.791, 12.0, 16.0, 12.0);
        shape.curveTo(18.209, 12.0, 20.0, 13.791, 20.0, 16.0);
        shape.curveTo(20.0, 18.209, 18.209, 20.0, 16.0, 20.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

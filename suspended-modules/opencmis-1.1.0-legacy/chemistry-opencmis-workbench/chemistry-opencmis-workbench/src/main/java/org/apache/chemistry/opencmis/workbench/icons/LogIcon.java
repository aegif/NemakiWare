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

public class LogIcon extends AbstractWorkbenchIcon {

    public LogIcon() {
        super();
    }

    public LogIcon(int width, int height) {
        super(width, height);
    }

    public LogIcon(int width, int height, boolean enabled) {
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

        shape.moveTo(61.0, 15.0);
        shape.lineTo(3.0, 15.0);
        shape.curveTo(1.343, 15.0, 0.0, 16.343, 0.0, 18.0);
        shape.lineTo(0.0, 46.0);
        shape.curveTo(0.0, 47.657, 1.343, 49.0, 3.0, 49.0);
        shape.lineTo(13.0, 49.0);
        shape.lineTo(13.0, 34.0);
        shape.lineTo(51.0, 34.0);
        shape.lineTo(51.0, 49.0);
        shape.lineTo(61.0, 49.0);
        shape.curveTo(62.657, 49.0, 64.0, 47.657, 64.0, 46.0);
        shape.lineTo(64.0, 18.0);
        shape.curveTo(64.0, 16.343, 62.657, 15.0, 61.0, 15.0);
        shape.closePath();
        shape.moveTo(9.0, 27.0);
        shape.curveTo(7.343, 27.0, 6.0, 25.657, 6.0, 24.0);
        shape.curveTo(6.0, 22.343, 7.343, 21.0, 9.0, 21.0);
        shape.curveTo(10.657, 21.0, 12.0, 22.343, 12.0, 24.0);
        shape.curveTo(12.0, 25.657, 10.657, 27.0, 9.0, 27.0);
        shape.closePath();
        shape.moveTo(48.0, 2.969);
        shape.lineTo(47.997, 2.969);
        shape.curveTo(47.997, 2.9680002, 47.997, 2.967, 47.997, 2.966);
        shape.curveTo(47.99, 2.351, 47.8, 1.782, 47.477, 1.309);
        shape.curveTo(47.473, 1.302, 47.465, 1.296, 47.460003, 1.289);
        shape.curveTo(47.356003, 1.139, 47.24, 0.998, 47.109, 0.87);
        shape.lineTo(47.109, 0.87);
        shape.curveTo(47.107002, 0.86800003, 47.105, 0.866, 47.101, 0.864);
        shape.curveTo(46.935, 0.699, 46.743, 0.56200004, 46.54, 0.439);
        shape.curveTo(46.538002, 0.43800002, 46.535, 0.437, 46.533, 0.435);
        shape.curveTo(46.533, 0.435, 46.532, 0.435, 46.532, 0.43400002);
        shape.curveTo(46.247, 0.263, 45.938, 0.12900001, 45.602, 0.06100002);
        shape.lineTo(45.602, 0.06100002);
        shape.curveTo(45.463, 0.03300002, 45.319, 0.027000017, 45.175003, 0.018000018);
        shape.curveTo(45.117, 0.014, 45.061, 0.0, 45.0, 0.0);
        shape.lineTo(19.0, 0.0);
        shape.curveTo(17.757, 0.0, 16.691, 0.756, 16.236, 1.832);
        shape.curveTo(16.084, 2.191, 16.0, 2.586, 16.0, 3.0);
        shape.lineTo(16.0, 12.0);
        shape.lineTo(48.0, 12.0);
        shape.lineTo(48.0, 2.969);
        shape.closePath();
        shape.moveTo(16.0, 61.0);
        shape.curveTo(16.0, 62.657, 17.343, 64.0, 19.0, 64.0);
        shape.lineTo(45.0, 64.0);
        shape.curveTo(46.657, 64.0, 48.0, 62.657, 48.0, 61.0);
        shape.lineTo(48.0, 37.0);
        shape.lineTo(16.0, 37.0);
        shape.lineTo(16.0, 61.0);
        shape.closePath();
        shape.moveTo(22.0, 43.0);
        shape.lineTo(42.0, 43.0);
        shape.lineTo(42.0, 46.0);
        shape.lineTo(22.0, 46.0);
        shape.lineTo(22.0, 43.0);
        shape.closePath();
        shape.moveTo(22.0, 49.0);
        shape.lineTo(42.0, 49.0);
        shape.lineTo(42.0, 52.0);
        shape.lineTo(22.0, 52.0);
        shape.lineTo(22.0, 49.0);
        shape.closePath();
        shape.moveTo(22.0, 55.0);
        shape.lineTo(42.0, 55.0);
        shape.lineTo(42.0, 58.0);
        shape.lineTo(22.0, 58.0);
        shape.lineTo(22.0, 55.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

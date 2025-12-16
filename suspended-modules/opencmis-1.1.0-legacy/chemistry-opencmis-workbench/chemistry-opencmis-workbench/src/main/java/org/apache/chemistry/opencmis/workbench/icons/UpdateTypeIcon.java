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

public class UpdateTypeIcon extends AbstractWorkbenchIcon {

    public UpdateTypeIcon() {
        super();
    }

    public UpdateTypeIcon(int width, int height) {
        super(width, height);
    }

    public UpdateTypeIcon(int width, int height, boolean enabled) {
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

        shape.moveTo(61.0, 0.0);
        shape.lineTo(3.0, 0.0);
        shape.curveTo(1.343, 0.0, 0.0, 1.343, 0.0, 3.0);
        shape.lineTo(0.0, 61.0);
        shape.curveTo(0.0, 62.657, 1.343, 64.0, 3.0, 64.0);
        shape.lineTo(61.0, 64.0);
        shape.curveTo(62.657, 64.0, 64.0, 62.657, 64.0, 61.0);
        shape.lineTo(64.0, 3.0);
        shape.curveTo(64.0, 1.343, 62.657, 0.0, 61.0, 0.0);
        shape.closePath();
        shape.moveTo(58.0, 58.0);
        shape.lineTo(6.0, 58.0);
        shape.lineTo(6.0, 6.0);
        shape.lineTo(58.0, 6.0);
        shape.lineTo(58.0, 58.0);
        shape.closePath();

        shape.moveTo(12.938, 42.498);
        shape.lineTo(21.383999, 50.927002);
        shape.lineTo(46.723, 25.639002);
        shape.lineTo(38.276, 17.210003);
        shape.lineTo(12.938, 42.498);
        shape.closePath();
        shape.moveTo(7.998, 55.84);
        shape.lineTo(19.256, 53.034);
        shape.lineTo(10.809001, 44.603);
        shape.lineTo(7.998, 55.84);
        shape.closePath();
        shape.moveTo(55.167, 12.996);
        shape.lineTo(50.943, 8.781);
        shape.curveTo(49.777, 7.617, 47.886, 7.617, 46.72, 8.781);
        shape.lineTo(40.386, 15.103001);
        shape.lineTo(48.832, 23.532001);
        shape.lineTo(55.166, 17.210001);
        shape.curveTo(56.333, 16.046, 56.333, 14.159, 55.167, 12.996);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

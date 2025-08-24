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

public class ReloadIcon extends AbstractWorkbenchIcon {

    public ReloadIcon() {
        super();
    }

    public ReloadIcon(int width, int height) {
        super(width, height);
    }

    public ReloadIcon(int width, int height, boolean enabled) {
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

        shape.moveTo(61.0, 29.0);
        shape.curveTo(59.343, 29.0, 58.0, 30.343, 58.0, 32.0);
        shape.curveTo(58.0, 46.359, 46.359, 58.0, 32.0, 58.0);
        shape.curveTo(23.232, 58.0, 15.4939995, 53.652, 10.792999, 47.0);
        shape.lineTo(16.0, 47.0);
        shape.curveTo(17.657, 47.0, 19.0, 45.657, 19.0, 44.0);
        shape.curveTo(19.0, 42.343, 17.657, 41.0, 16.0, 41.0);
        shape.lineTo(3.0, 41.0);
        shape.curveTo(1.343, 41.0, 0.0, 42.343, 0.0, 44.0);
        shape.lineTo(0.0, 56.0);
        shape.curveTo(0.0, 57.657, 1.343, 59.0, 3.0, 59.0);
        shape.curveTo(4.657, 59.0, 6.0, 57.657, 6.0, 56.0);
        shape.lineTo(6.0, 50.546);
        shape.curveTo(11.789, 58.68, 21.254, 64.0, 32.0, 64.0);
        shape.curveTo(49.673, 64.0, 64.0, 49.673, 64.0, 32.0);
        shape.curveTo(64.0, 30.343, 62.657, 29.0, 61.0, 29.0);
        shape.closePath();
        shape.moveTo(61.0, 5.0);
        shape.curveTo(59.343, 5.0, 58.0, 6.343, 58.0, 8.0);
        shape.lineTo(58.0, 13.412001);
        shape.curveTo(52.204, 5.299, 42.732, 0.0, 32.0, 0.0);
        shape.curveTo(14.327, 0.0, 0.0, 14.327, 0.0, 32.0);
        shape.curveTo(0.0, 33.657, 1.343, 35.0, 3.0, 35.0);
        shape.curveTo(4.657, 35.0, 6.0, 33.657, 6.0, 32.0);
        shape.curveTo(6.0, 17.641, 17.641, 6.0, 32.0, 6.0);
        shape.curveTo(40.760002, 6.0, 48.469, 10.355, 53.167, 17.0);
        shape.lineTo(48.0, 17.0);
        shape.curveTo(46.343, 17.0, 45.0, 18.343, 45.0, 20.0);
        shape.curveTo(45.0, 21.657, 46.343, 23.0, 48.0, 23.0);
        shape.lineTo(61.0, 23.0);
        shape.curveTo(62.657, 23.0, 64.0, 21.657, 64.0, 20.0);
        shape.lineTo(64.0, 8.0);
        shape.curveTo(64.0, 6.343, 62.657, 5.0, 61.0, 5.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

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

public class HelpIcon extends AbstractWorkbenchIcon {

    public HelpIcon() {
        super();
    }

    public HelpIcon(int width, int height) {
        super(width, height);
    }

    public HelpIcon(int width, int height, boolean enabled) {
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

        shape.moveTo(32.0, 0.0);
        shape.curveTo(14.327, 0.0, 0.0, 14.327, 0.0, 32.0);
        shape.curveTo(0.0, 49.673, 14.327, 64.0, 32.0, 64.0);
        shape.curveTo(49.673, 64.0, 64.0, 49.673, 64.0, 32.0);
        shape.curveTo(64.0, 14.327, 49.673, 0.0, 32.0, 0.0);
        shape.closePath();
        shape.moveTo(32.0, 58.0);
        shape.curveTo(17.641, 58.0, 6.0, 46.359, 6.0, 32.0);
        shape.curveTo(6.0, 17.64, 17.641, 6.0, 32.0, 6.0);
        shape.curveTo(46.359, 6.0, 58.0, 17.64, 58.0, 32.0);
        shape.curveTo(58.0, 46.359, 46.359, 58.0, 32.0, 58.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);

        shape = new GeneralPath();
        shape.moveTo(35.153282, 25.48125);
        shape.lineTo(35.153282, 46.06719);
        shape.lineTo(35.153282, 48.469532);
        shape.curveTo(35.153282, 50.871876, 35.817345, 51.282032, 39.704063, 51.282032);
        shape.lineTo(40.13375, 51.282032);
        shape.lineTo(40.13375, 53.45);
        shape.lineTo(24.586876, 53.45);
        shape.lineTo(24.586876, 51.282032);
        shape.lineTo(25.016563, 51.282032);
        shape.curveTo(28.844688, 51.282032, 29.50875, 50.871876, 29.50875, 48.469532);
        shape.lineTo(29.50875, 46.06719);
        shape.lineTo(29.50875, 31.73125);
        shape.curveTo(29.50875, 29.153126, 29.157188, 28.723438, 26.930626, 28.723438);
        shape.lineTo(23.86422, 28.723438);
        shape.lineTo(23.86422, 26.145313);
        shape.closePath();
        shape.moveTo(32.32125, 10.559376);
        shape.curveTo(34.254845, 10.559376, 35.817345, 12.1023445, 35.817345, 14.035938);
        shape.curveTo(35.817345, 15.950001, 34.254845, 17.5125, 32.262657, 17.5125);
        shape.curveTo(30.407188, 17.5125, 28.844688, 15.891407, 28.844688, 14.035938);
        shape.curveTo(28.844688, 12.1023445, 30.407188, 10.559376, 32.32125, 10.559376);
        shape.closePath();

        g.fill(shape);
    }
}

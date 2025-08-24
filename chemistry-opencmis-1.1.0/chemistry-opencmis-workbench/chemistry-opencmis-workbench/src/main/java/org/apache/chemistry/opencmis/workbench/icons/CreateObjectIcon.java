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

public class CreateObjectIcon extends AbstractWorkbenchIcon {

    public CreateObjectIcon() {
        super();
    }

    public CreateObjectIcon(int width, int height) {
        super(width, height);
    }

    public CreateObjectIcon(int width, int height, boolean enabled) {
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

        shape.moveTo(45.0, 29.0);
        shape.lineTo(35.0, 29.0);
        shape.lineTo(35.0, 19.0);
        shape.curveTo(35.0, 17.343, 33.657, 16.0, 32.0, 16.0);
        shape.curveTo(30.342999, 16.0, 29.0, 17.343, 29.0, 19.0);
        shape.lineTo(29.0, 29.0);
        shape.lineTo(19.0, 29.0);
        shape.curveTo(17.343, 29.0, 16.0, 30.343, 16.0, 32.0);
        shape.curveTo(16.0, 33.656998, 17.343, 35.0, 19.0, 35.0);
        shape.lineTo(29.0, 35.0);
        shape.lineTo(29.0, 45.0);
        shape.curveTo(29.0, 46.657, 30.343, 48.0, 32.0, 48.0);
        shape.curveTo(33.656998, 48.0, 35.0, 46.657, 35.0, 45.0);
        shape.lineTo(35.0, 35.0);
        shape.lineTo(45.0, 35.0);
        shape.curveTo(46.657, 35.0, 48.0, 33.657, 48.0, 32.0);
        shape.curveTo(48.0, 30.342999, 46.657, 29.0, 45.0, 29.0);
        shape.closePath();
        shape.moveTo(32.0, 0.0);
        shape.curveTo(14.327, 0.0, 0.0, 14.327, 0.0, 32.0);
        shape.curveTo(0.0, 49.673, 14.327, 64.0, 32.0, 64.0);
        shape.curveTo(49.673, 64.0, 64.0, 49.673, 64.0, 32.0);
        shape.curveTo(64.0, 14.327, 49.673, 0.0, 32.0, 0.0);
        shape.closePath();
        shape.moveTo(32.0, 58.0);
        shape.curveTo(17.641, 58.0, 6.0, 46.359, 6.0, 32.0);
        shape.curveTo(6.0, 17.64, 17.641, 6.0, 32.0, 6.0);
        shape.curveTo(46.359, 6.0, 58.0, 17.640999, 58.0, 32.0);
        shape.curveTo(58.0, 46.359, 46.359, 58.0, 32.0, 58.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

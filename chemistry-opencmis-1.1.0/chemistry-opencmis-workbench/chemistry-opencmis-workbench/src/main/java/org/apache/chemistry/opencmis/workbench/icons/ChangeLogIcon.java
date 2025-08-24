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

public class ChangeLogIcon extends AbstractWorkbenchIcon {

    public ChangeLogIcon() {
        super();
    }

    public ChangeLogIcon(int width, int height) {
        super(width, height);
    }

    public ChangeLogIcon(int width, int height, boolean enabled) {
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

        shape.moveTo(9.0, 45.0);
        shape.lineTo(3.0, 45.0);
        shape.curveTo(1.343, 45.0, 0.0, 46.343, 0.0, 48.0);
        shape.curveTo(0.0, 49.657, 1.343, 51.0, 3.0, 51.0);
        shape.lineTo(9.0, 51.0);
        shape.lineTo(9.0, 51.0);
        shape.curveTo(10.657, 51.0, 12.0, 49.657, 12.0, 48.0);
        shape.curveTo(12.0, 46.343, 10.657, 45.0, 9.0, 45.0);
        shape.closePath();
        shape.moveTo(61.0, 29.0);
        shape.lineTo(21.0, 29.0);
        shape.curveTo(19.343, 29.0, 18.0, 30.343, 18.0, 32.0);
        shape.curveTo(18.0, 33.656998, 19.343, 35.0, 21.0, 35.0);
        shape.lineTo(21.0, 35.0);
        shape.lineTo(61.0, 35.0);
        shape.curveTo(62.657, 35.0, 64.0, 33.657, 64.0, 32.0);
        shape.curveTo(64.0, 30.342999, 62.657, 29.0, 61.0, 29.0);
        shape.closePath();
        shape.moveTo(9.0, 29.0);
        shape.lineTo(3.0, 29.0);
        shape.curveTo(1.343, 29.0, 0.0, 30.343, 0.0, 32.0);
        shape.curveTo(0.0, 33.656998, 1.343, 35.0, 3.0, 35.0);
        shape.lineTo(9.0, 35.0);
        shape.lineTo(9.0, 35.0);
        shape.curveTo(10.657, 35.0, 12.0, 33.657, 12.0, 32.0);
        shape.curveTo(12.0, 30.342999, 10.657, 29.0, 9.0, 29.0);
        shape.closePath();
        shape.moveTo(21.0, 19.0);
        shape.lineTo(61.0, 19.0);
        shape.curveTo(62.657, 19.0, 64.0, 17.657, 64.0, 16.0);
        shape.curveTo(64.0, 14.343, 62.657, 13.0, 61.0, 13.0);
        shape.lineTo(21.001, 13.0);
        shape.lineTo(21.0, 13.0);
        shape.curveTo(19.343, 13.0, 18.0, 14.343, 18.0, 16.0);
        shape.curveTo(18.0, 17.657, 19.343, 19.0, 21.0, 19.0);
        shape.closePath();
        shape.moveTo(9.0, 13.0);
        shape.lineTo(9.0, 13.0);
        shape.lineTo(3.0, 13.0);
        shape.curveTo(1.343, 13.0, 0.0, 14.343, 0.0, 16.0);
        shape.curveTo(0.0, 17.657, 1.343, 19.0, 3.0, 19.0);
        shape.lineTo(9.0, 19.0);
        shape.curveTo(10.657, 19.0, 12.0, 17.657, 12.0, 16.0);
        shape.curveTo(12.0, 14.343, 10.657, 13.0, 9.0, 13.0);
        shape.closePath();
        shape.moveTo(61.0, 45.0);
        shape.lineTo(21.0, 45.0);
        shape.curveTo(19.343, 45.0, 18.0, 46.343, 18.0, 48.0);
        shape.curveTo(18.0, 49.657, 19.343, 51.0, 21.0, 51.0);
        shape.lineTo(21.0, 51.0);
        shape.lineTo(61.0, 51.0);
        shape.curveTo(62.657, 51.0, 64.0, 49.657, 64.0, 48.0);
        shape.curveTo(64.0, 46.343, 62.657, 45.0, 61.0, 45.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

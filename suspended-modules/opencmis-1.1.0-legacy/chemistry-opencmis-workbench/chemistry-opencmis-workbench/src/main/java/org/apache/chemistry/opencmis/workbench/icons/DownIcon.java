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

public class DownIcon extends AbstractWorkbenchIcon {

    public DownIcon() {
        super();
    }

    public DownIcon(int width, int height) {
        super(width, height);
    }

    public DownIcon(int width, int height, boolean enabled) {
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

        shape.moveTo(53.0, 23.0);
        shape.curveTo(53.0, 21.343, 51.657, 20.0, 50.0, 20.0);
        shape.curveTo(49.191, 20.0, 48.458, 20.321, 47.918, 20.841);
        shape.lineTo(47.917, 20.84);
        shape.lineTo(31.993, 36.764);
        shape.lineTo(16.275, 21.046);
        shape.curveTo(15.725, 20.406, 14.91, 20.0, 14.0, 20.0);
        shape.curveTo(12.343, 20.0, 11.0, 21.343, 11.0, 23.0);
        shape.curveTo(11.0, 23.805, 11.318, 24.536, 11.835, 25.075);
        shape.lineTo(11.827, 25.083);
        shape.lineTo(29.827, 43.083);
        shape.lineTo(29.828, 43.082);
        shape.curveTo(30.374, 43.648, 31.139, 44.0, 31.987, 44.0);
        shape.curveTo(31.989, 44.0, 31.991, 44.0, 31.994, 44.0);
        shape.curveTo(31.996, 44.0, 31.998, 44.0, 32.001, 44.0);
        shape.curveTo(32.85, 44.0, 33.613, 43.648, 34.16, 43.082);
        shape.lineTo(34.161, 43.083);
        shape.lineTo(52.161, 25.083);
        shape.lineTo(52.16, 25.082);
        shape.curveTo(52.68, 24.543, 53.0, 23.809, 53.0, 23.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

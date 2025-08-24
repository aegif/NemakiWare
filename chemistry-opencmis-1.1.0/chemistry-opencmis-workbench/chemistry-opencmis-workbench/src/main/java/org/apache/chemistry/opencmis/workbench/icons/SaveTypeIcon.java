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

public class SaveTypeIcon extends AbstractWorkbenchIcon {

    public SaveTypeIcon() {
        super();
    }

    public SaveTypeIcon(int width, int height) {
        super(width, height);
    }

    public SaveTypeIcon(int width, int height, boolean enabled) {
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

        shape.moveTo(20.77, 29.007);
        shape.lineTo(29.77, 39.007);
        shape.lineTo(29.77, 39.007);
        shape.curveTo(30.319, 39.616, 31.115, 40.0, 32.0, 40.0);
        shape.curveTo(32.885002, 40.0, 33.68, 39.616, 34.23, 39.007);
        shape.lineTo(34.23, 39.007);
        shape.lineTo(43.23, 29.007);
        shape.lineTo(43.23, 29.007);
        shape.curveTo(43.708, 28.475, 44.0, 27.772, 44.0, 27.0);
        shape.curveTo(44.0, 25.343, 42.657, 24.0, 41.0, 24.0);
        shape.curveTo(40.115, 24.0, 39.318, 24.383, 38.77, 24.993);
        shape.lineTo(38.77, 24.993);
        shape.lineTo(35.0, 29.182);
        shape.lineTo(35.0, 11.0);
        shape.curveTo(35.0, 9.343, 33.657, 8.0, 32.0, 8.0);
        shape.curveTo(30.342999, 8.0, 29.0, 9.343, 29.0, 11.0);
        shape.lineTo(29.0, 29.182);
        shape.lineTo(25.23, 24.993);
        shape.lineTo(25.23, 24.993);
        shape.curveTo(24.681, 24.384, 23.885, 24.0, 23.0, 24.0);
        shape.curveTo(21.343, 24.0, 20.0, 25.343, 20.0, 27.0);
        shape.curveTo(20.0, 27.772, 20.292, 28.475, 20.77, 29.007);
        shape.lineTo(20.77, 29.007);
        shape.closePath();
        shape.moveTo(55.0, 35.0);
        shape.curveTo(53.343, 35.0, 52.0, 36.343, 52.0, 38.0);
        shape.lineTo(52.0, 50.0);
        shape.lineTo(12.0, 50.0);
        shape.lineTo(12.0, 38.0);
        shape.curveTo(12.0, 36.343, 10.657, 35.0, 9.0, 35.0);
        shape.curveTo(7.3430004, 35.0, 6.0, 36.343, 6.0, 38.0);
        shape.lineTo(6.0, 53.0);
        shape.curveTo(6.0, 54.657, 7.343, 56.0, 9.0, 56.0);
        shape.lineTo(31.997, 56.0);
        shape.curveTo(31.998, 56.0, 31.999, 56.0, 32.0, 56.0);
        shape.curveTo(32.001, 56.0, 32.002, 56.0, 32.003, 56.0);
        shape.lineTo(55.0, 56.0);
        shape.curveTo(56.657, 56.0, 58.0, 54.657, 58.0, 53.0);
        shape.lineTo(58.0, 38.0);
        shape.curveTo(58.0, 36.343, 56.657, 35.0, 55.0, 35.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

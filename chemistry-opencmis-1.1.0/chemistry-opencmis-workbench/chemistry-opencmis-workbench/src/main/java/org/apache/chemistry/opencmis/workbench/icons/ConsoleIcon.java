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

public class ConsoleIcon extends AbstractWorkbenchIcon {

    public ConsoleIcon() {
        super();
    }

    public ConsoleIcon(int width, int height) {
        super(width, height);
    }

    public ConsoleIcon(int width, int height, boolean enabled) {
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

        shape.moveTo(30.0, 32.0);
        shape.curveTo(30.0, 31.998, 30.0, 31.995, 30.0, 31.993);
        shape.curveTo(30.0, 31.991, 30.0, 31.988, 30.0, 31.986);
        shape.curveTo(30.0, 31.138, 29.648, 30.373, 29.082, 29.827);
        shape.lineTo(29.083, 29.826);
        shape.lineTo(11.083, 11.826);
        shape.lineTo(11.075, 11.834001);
        shape.curveTo(10.536, 11.318, 9.806, 11.0, 9.0, 11.0);
        shape.curveTo(7.343, 11.0, 6.0, 12.343, 6.0, 14.0);
        shape.curveTo(6.0, 14.91, 6.406, 15.725, 7.046, 16.275);
        shape.lineTo(22.764, 31.993);
        shape.lineTo(6.84, 47.917);
        shape.lineTo(6.841, 47.918);
        shape.curveTo(6.321, 48.458, 6.0, 49.191, 6.0, 50.0);
        shape.curveTo(6.0, 51.657, 7.343, 53.0, 9.0, 53.0);
        shape.curveTo(9.809, 53.0, 10.543, 52.679, 11.082, 52.159);
        shape.lineTo(11.083, 52.16);
        shape.lineTo(29.083, 34.16);
        shape.lineTo(29.082, 34.159);
        shape.curveTo(29.648, 33.613, 30.0, 32.848, 30.0, 32.0);
        shape.closePath();
        shape.moveTo(55.5, 47.0);
        shape.lineTo(32.5, 47.0);
        shape.curveTo(31.119, 47.0, 30.0, 48.119, 30.0, 49.5);
        shape.lineTo(30.0, 50.5);
        shape.curveTo(30.0, 51.881, 31.119, 53.0, 32.5, 53.0);
        shape.lineTo(55.5, 53.0);
        shape.curveTo(56.881, 53.0, 58.0, 51.881, 58.0, 50.5);
        shape.lineTo(58.0, 49.5);
        shape.curveTo(58.0, 48.119, 56.881, 47.0, 55.5, 47.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

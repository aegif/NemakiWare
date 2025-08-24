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

public class ItemIcon extends AbstractWorkbenchIcon {

    public ItemIcon() {
        super();
    }

    public ItemIcon(int width, int height) {
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

        shape.moveTo(64.0, 26.0);
        shape.curveTo(64.0, 24.343, 62.657, 23.0, 61.0, 23.0);
        shape.lineTo(41.047, 23.0);
        shape.lineTo(34.869, 4.12);
        shape.curveTo(34.492, 2.893, 33.352, 2.0, 32.0, 2.0);
        shape.curveTo(30.65, 2.0, 29.508, 2.892, 29.132, 4.12);
        shape.lineTo(22.953, 23.0);
        shape.lineTo(3.0, 23.0);
        shape.curveTo(1.343, 23.0, 0.0, 24.343, 0.0, 26.0);
        shape.curveTo(0.0, 27.0, 0.49, 27.885, 1.242, 28.43);
        shape.lineTo(1.242, 28.43);
        shape.lineTo(17.36, 40.09);
        shape.lineTo(11.151001, 59.061);
        shape.curveTo(11.053, 59.357, 11.0, 59.672, 11.0, 60.0);
        shape.curveTo(11.0, 61.657, 12.343, 63.0, 14.0, 63.0);
        shape.curveTo(14.657, 63.0, 15.2630005, 62.787, 15.758, 62.43);
        shape.lineTo(15.759001, 62.43);
        shape.lineTo(32.0, 50.682);
        shape.lineTo(48.242, 62.43);
        shape.lineTo(48.243, 62.43);
        shape.curveTo(48.736, 62.788, 49.343, 63.0, 50.0, 63.0);
        shape.curveTo(51.657, 63.0, 53.0, 61.657, 53.0, 60.0);
        shape.curveTo(53.0, 59.672, 52.947, 59.357, 52.85, 59.062);
        shape.lineTo(46.641, 40.09);
        shape.lineTo(62.759, 28.43);
        shape.lineTo(62.759, 28.43);
        shape.curveTo(63.51, 27.885, 64.0, 27.0, 64.0, 26.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

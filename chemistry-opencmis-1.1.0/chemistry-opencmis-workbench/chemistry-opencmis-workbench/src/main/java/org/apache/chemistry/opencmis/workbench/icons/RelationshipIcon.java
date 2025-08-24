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

public class RelationshipIcon extends AbstractWorkbenchIcon {

    public RelationshipIcon() {
        super();
    }

    public RelationshipIcon(int width, int height) {
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

        shape.moveTo(18.0, 43.0);
        shape.curveTo(17.172, 43.0, 16.422, 43.336, 15.879, 43.879);
        shape.lineTo(6.0, 53.757);
        shape.lineTo(6.0, 49.0);
        shape.curveTo(6.0, 47.343, 4.657, 46.0, 3.0, 46.0);
        shape.curveTo(1.3429999, 46.0, 0.0, 47.343, 0.0, 49.0);
        shape.lineTo(0.0, 61.0);
        shape.curveTo(0.0, 62.657, 1.343, 64.0, 3.0, 64.0);
        shape.lineTo(15.0, 64.0);
        shape.curveTo(16.657, 64.0, 18.0, 62.657, 18.0, 61.0);
        shape.curveTo(18.0, 59.343, 16.657, 58.0, 15.0, 58.0);
        shape.lineTo(10.243, 58.0);
        shape.lineTo(20.122, 48.121002);
        shape.curveTo(20.664, 47.578, 21.0, 46.829, 21.0, 46.0);
        shape.curveTo(21.0, 44.343, 19.657, 43.0, 18.0, 43.0);
        shape.closePath();
        shape.moveTo(61.0, 0.0);
        shape.lineTo(49.0, 0.0);
        shape.curveTo(47.343, 0.0, 46.0, 1.343, 46.0, 3.0);
        shape.curveTo(46.0, 4.657, 47.343, 6.0, 49.0, 6.0);
        shape.lineTo(53.757, 6.0);
        shape.lineTo(43.878, 15.879);
        shape.curveTo(43.336, 16.422, 43.0, 17.172, 43.0, 18.0);
        shape.curveTo(43.0, 19.657, 44.343, 21.0, 46.0, 21.0);
        shape.curveTo(46.828, 21.0, 47.578, 20.664, 48.121, 20.121);
        shape.lineTo(58.0, 10.243);
        shape.lineTo(58.0, 15.0);
        shape.curveTo(58.0, 16.657, 59.343, 18.0, 61.0, 18.0);
        shape.curveTo(62.657, 18.0, 64.0, 16.657, 64.0, 15.0);
        shape.lineTo(64.0, 3.0);
        shape.curveTo(64.0, 1.343, 62.657, 0.0, 61.0, 0.0);
        shape.closePath();
        shape.moveTo(32.0, 23.0);
        shape.curveTo(27.029, 23.0, 23.0, 27.029, 23.0, 32.0);
        shape.curveTo(23.0, 36.971, 27.029, 41.0, 32.0, 41.0);
        shape.curveTo(36.971, 41.0, 41.0, 36.971, 41.0, 32.0);
        shape.curveTo(41.0, 27.029, 36.971, 23.0, 32.0, 23.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

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

public class CopyIcon extends AbstractWorkbenchIcon {

    public CopyIcon() {
        super();
    }

    public CopyIcon(int width, int height) {
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
    protected void paint(Graphics2D g) {
        GeneralPath shape = new GeneralPath();

        shape.moveTo(41.0, 20.0);
        shape.lineTo(3.0, 20.0);
        shape.curveTo(1.343, 20.0, 0.0, 21.343, 0.0, 23.0);
        shape.lineTo(0.0, 61.0);
        shape.curveTo(0.0, 62.657, 1.343, 64.0, 3.0, 64.0);
        shape.lineTo(41.0, 64.0);
        shape.curveTo(42.657, 64.0, 44.0, 62.657, 44.0, 61.0);
        shape.lineTo(44.0, 23.0);
        shape.curveTo(44.0, 21.343, 42.657, 20.0, 41.0, 20.0);
        shape.closePath();
        shape.moveTo(38.0, 58.0);
        shape.lineTo(6.0, 58.0);
        shape.lineTo(6.0, 26.0);
        shape.lineTo(15.0, 26.0);
        shape.lineTo(15.0, 26.0);
        shape.lineTo(28.0, 26.0);
        shape.lineTo(28.0, 26.0);
        shape.lineTo(38.0, 26.0);
        shape.lineTo(38.0, 58.0);
        shape.closePath();
        shape.moveTo(61.0, 0.0);
        shape.lineTo(23.0, 0.0);
        shape.curveTo(21.343, 0.0, 20.0, 1.343, 20.0, 3.0);
        shape.lineTo(20.0, 17.0);
        shape.lineTo(26.0, 17.0);
        shape.lineTo(26.0, 6.0);
        shape.lineTo(35.0, 6.0);
        shape.lineTo(35.0, 6.0);
        shape.lineTo(48.0, 6.0);
        shape.lineTo(48.0, 6.0);
        shape.lineTo(58.0, 6.0);
        shape.lineTo(58.0, 38.0);
        shape.lineTo(47.0, 38.0);
        shape.lineTo(47.0, 44.0);
        shape.lineTo(61.0, 44.0);
        shape.curveTo(62.657, 44.0, 64.0, 42.657, 64.0, 41.0);
        shape.lineTo(64.0, 3.0);
        shape.curveTo(64.0, 1.344, 62.657, 0.0, 61.0, 0.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

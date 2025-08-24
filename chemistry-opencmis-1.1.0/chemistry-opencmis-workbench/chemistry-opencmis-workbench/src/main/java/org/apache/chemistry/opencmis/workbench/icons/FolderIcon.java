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

public class FolderIcon extends AbstractWorkbenchIcon {

    public FolderIcon() {
        super();
    }

    public FolderIcon(int width, int height) {
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
        return new Color(0x89328c);
    }

    @Override
    protected void paint(Graphics2D g) {
        GeneralPath shape = new GeneralPath();

        shape.moveTo(0.0, 54.0);
        shape.curveTo(0.0, 55.657, 1.343, 57.0, 3.0, 57.0);
        shape.lineTo(61.0, 57.0);
        shape.curveTo(62.657, 57.0, 64.0, 55.657, 64.0, 54.0);
        shape.lineTo(64.0, 22.0);
        shape.lineTo(0.0, 22.0);
        shape.lineTo(0.0, 54.0);
        shape.closePath();
        shape.moveTo(61.0, 13.0);
        shape.lineTo(23.982, 13.0);
        shape.lineTo(18.0, 7.0);
        shape.lineTo(3.0, 7.0);
        shape.curveTo(1.343, 7.0, 0.0, 8.343, 0.0, 10.0);
        shape.lineTo(0.0, 19.0);
        shape.lineTo(64.0, 19.0);
        shape.lineTo(64.0, 16.0);
        shape.curveTo(64.0, 14.343, 62.657, 13.0, 61.0, 13.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

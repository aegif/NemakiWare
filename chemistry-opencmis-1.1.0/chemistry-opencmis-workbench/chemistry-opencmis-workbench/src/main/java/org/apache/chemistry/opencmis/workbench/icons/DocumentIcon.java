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

public class DocumentIcon extends AbstractWorkbenchIcon {

    public DocumentIcon() {
        super();
    }

    public DocumentIcon(int width, int height) {
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

        shape.moveTo(35.0, 23.008);
        shape.lineTo(54.0, 23.008);
        shape.lineTo(32.0, 2.964);
        shape.lineTo(32.0, 20.001001);
        shape.curveTo(32.0, 21.662, 33.343, 23.008, 35.0, 23.008);
        shape.closePath();
        shape.moveTo(29.0, 20.001);
        shape.lineTo(29.0, 3.0);
        shape.lineTo(13.0, 3.0);
        shape.curveTo(11.343, 3.0, 10.0, 4.343, 10.0, 6.0);
        shape.lineTo(10.0, 58.0);
        shape.curveTo(10.0, 59.657, 11.343, 61.0, 13.0, 61.0);
        shape.lineTo(51.0, 61.0);
        shape.curveTo(52.657, 61.0, 54.0, 59.657, 54.0, 58.0);
        shape.lineTo(54.0, 26.014);
        shape.lineTo(35.0, 26.014);
        shape.curveTo(31.686, 26.014, 29.0, 23.322, 29.0, 20.001);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

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

public class SecondaryIcon extends AbstractWorkbenchIcon {

    public SecondaryIcon() {
        super();
    }

    public SecondaryIcon(int width, int height) {
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

        shape.moveTo(41.0, 5.0);
        shape.lineTo(23.0, 5.0);
        shape.curveTo(21.343, 5.0, 20.0, 6.343, 20.0, 8.0);
        shape.lineTo(20.0, 59.0);
        shape.lineTo(32.0, 47.0);
        shape.lineTo(44.0, 59.0);
        shape.lineTo(44.0, 8.0);
        shape.curveTo(44.0, 6.343, 42.657, 5.0, 41.0, 5.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

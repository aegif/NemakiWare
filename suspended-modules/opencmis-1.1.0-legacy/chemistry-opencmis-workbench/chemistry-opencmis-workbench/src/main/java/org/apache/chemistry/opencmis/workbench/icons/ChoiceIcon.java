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

public class ChoiceIcon extends AbstractWorkbenchIcon {

    public ChoiceIcon() {
        super();
    }

    public ChoiceIcon(int width, int height) {
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

        shape.moveTo(46.0, 32.0);
        shape.curveTo(46.0, 30.901001, 45.408, 29.94, 44.525, 29.417);
        shape.lineTo(44.525, 29.417);
        shape.lineTo(22.561, 16.438);
        shape.curveTo(22.553001, 16.433, 22.545, 16.428999, 22.537, 16.424);
        shape.lineTo(22.526001, 16.417);
        shape.lineTo(22.526001, 16.417);
        shape.curveTo(22.079, 16.153, 21.557, 16.0, 21.0, 16.0);
        shape.curveTo(19.343, 16.0, 18.0, 17.343, 18.0, 19.0);
        shape.lineTo(18.0, 45.0);
        shape.curveTo(18.0, 46.657, 19.343, 48.0, 21.0, 48.0);
        shape.curveTo(21.557, 48.0, 22.079, 47.848, 22.526001, 47.583);
        shape.lineTo(22.526001, 47.583);
        shape.lineTo(22.537, 47.576);
        shape.curveTo(22.545, 47.57, 22.553001, 47.566, 22.561, 47.562);
        shape.lineTo(44.525, 34.583);
        shape.lineTo(44.525, 34.583);
        shape.curveTo(45.408, 34.061, 46.0, 33.1, 46.0, 32.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

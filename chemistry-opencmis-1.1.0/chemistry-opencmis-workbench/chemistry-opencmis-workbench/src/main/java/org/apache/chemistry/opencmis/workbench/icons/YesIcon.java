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

public class YesIcon extends AbstractWorkbenchIcon {

    public YesIcon() {
        super();
    }

    public YesIcon(int width, int height) {
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

        shape.moveTo(50.0, 18.0);
        shape.curveTo(49.172, 18.0, 48.422, 18.336, 47.879, 18.879);
        shape.lineTo(27.0, 39.757);
        shape.lineTo(16.121, 28.879);
        shape.curveTo(15.578, 28.336, 14.828, 28.0, 14.0, 28.0);
        shape.curveTo(12.343, 28.0, 11.0, 29.343, 11.0, 31.0);
        shape.curveTo(11.0, 31.828, 11.336, 32.578, 11.879, 33.121);
        shape.lineTo(24.879, 46.121);
        shape.curveTo(25.422, 46.664, 26.172, 47.0, 27.0, 47.0);
        shape.curveTo(27.828, 47.0, 28.578, 46.664, 29.121, 46.121);
        shape.lineTo(52.121002, 23.120998);
        shape.curveTo(52.664, 22.579, 53.0, 21.828, 53.0, 21.0);
        shape.curveTo(53.0, 19.343, 51.657, 18.0, 50.0, 18.0);
        shape.closePath();

        g.setPaint(Color.GREEN);
        g.fill(shape);

    }
}

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

public class NoIcon extends AbstractWorkbenchIcon {

    public NoIcon() {
        super();
    }

    public NoIcon(int width, int height) {
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

        shape.moveTo(36.243, 32.0);
        shape.lineTo(48.122, 20.121);
        shape.curveTo(48.664, 19.579, 49.0, 18.828, 49.0, 18.0);
        shape.curveTo(49.0, 16.343, 47.657, 15.0, 46.0, 15.0);
        shape.curveTo(45.172, 15.0, 44.422, 15.336, 43.879, 15.879);
        shape.lineTo(32.0, 27.757);
        shape.lineTo(20.121, 15.879);
        shape.curveTo(19.578, 15.336, 18.828, 15.0, 18.0, 15.0);
        shape.curveTo(16.343, 15.0, 15.0, 16.343, 15.0, 18.0);
        shape.curveTo(15.0, 18.828, 15.336, 19.578, 15.879, 20.121);
        shape.lineTo(27.757, 32.0);
        shape.lineTo(15.879, 43.879);
        shape.curveTo(15.336, 44.422, 15.0, 45.172, 15.0, 46.0);
        shape.curveTo(15.0, 47.657, 16.343, 49.0, 18.0, 49.0);
        shape.curveTo(18.828, 49.0, 19.578, 48.664, 20.121, 48.121);
        shape.lineTo(32.0, 36.243);
        shape.lineTo(43.878998, 48.122);
        shape.curveTo(44.422, 48.664, 45.172, 49.0, 46.0, 49.0);
        shape.curveTo(47.657, 49.0, 49.0, 47.657, 49.0, 46.0);
        shape.curveTo(49.0, 45.172, 48.664, 44.422, 48.121, 43.879);
        shape.lineTo(36.243, 32.0);
        shape.closePath();

        g.setPaint(Color.RED);
        g.fill(shape);
    }
}

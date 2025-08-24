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

public class AddIcon extends AbstractWorkbenchIcon {

    public AddIcon() {
        super();
    }

    public AddIcon(int width, int height) {
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

        shape.moveTo(52.0, 29.0);
        shape.lineTo(35.0, 29.0);
        shape.lineTo(35.0, 12.0);
        shape.curveTo(35.0, 10.343, 33.657, 9.0, 32.0, 9.0);
        shape.curveTo(30.342999, 9.0, 29.0, 10.343, 29.0, 12.0);
        shape.lineTo(29.0, 29.0);
        shape.lineTo(12.0, 29.0);
        shape.curveTo(10.343, 29.0, 9.0, 30.343, 9.0, 32.0);
        shape.curveTo(9.0, 33.656998, 10.343, 35.0, 12.0, 35.0);
        shape.lineTo(29.0, 35.0);
        shape.lineTo(29.0, 52.0);
        shape.curveTo(29.0, 53.657, 30.343, 55.0, 32.0, 55.0);
        shape.curveTo(33.656998, 55.0, 35.0, 53.657, 35.0, 52.0);
        shape.lineTo(35.0, 35.0);
        shape.lineTo(52.0, 35.0);
        shape.curveTo(53.657, 35.0, 55.0, 33.657, 55.0, 32.0);
        shape.curveTo(55.0, 30.342999, 53.657, 29.0, 52.0, 29.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

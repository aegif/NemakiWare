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

public class InfoIcon extends AbstractWorkbenchIcon {

    public InfoIcon() {
        super();
    }

    public InfoIcon(int width, int height) {
        super(width, height);
    }

    public InfoIcon(int width, int height, boolean enabled) {
        super(width, height, enabled);
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

        shape.moveTo(32.0, 0.0);
        shape.curveTo(18.746, 0.0, 8.0, 10.747, 8.0, 24.0);
        shape.curveTo(8.0, 37.253998, 32.0, 64.0, 32.0, 64.0);
        shape.curveTo(32.0, 64.0, 56.0, 37.253998, 56.0, 24.0);
        shape.curveTo(56.0, 10.747, 45.254, 0.0, 32.0, 0.0);
        shape.closePath();
        shape.moveTo(32.0, 40.0);
        shape.curveTo(23.164, 40.0, 16.0, 32.836, 16.0, 24.0);
        shape.curveTo(16.0, 15.164001, 23.164, 8.0, 32.0, 8.0);
        shape.curveTo(40.836, 8.0, 48.0, 15.164, 48.0, 24.0);
        shape.curveTo(48.0, 32.836, 40.836, 40.0, 32.0, 40.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

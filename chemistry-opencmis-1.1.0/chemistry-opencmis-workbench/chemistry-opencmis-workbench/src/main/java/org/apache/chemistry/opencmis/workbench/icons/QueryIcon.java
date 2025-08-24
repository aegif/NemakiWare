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

public class QueryIcon extends AbstractWorkbenchIcon {

    public QueryIcon() {
        super();
    }

    public QueryIcon(int width, int height) {
        super(width, height);
    }

    public QueryIcon(int width, int height, boolean enabled) {
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

        shape.moveTo(62.243, 53.758);
        shape.lineTo(44.658, 36.173);
        shape.curveTo(46.768, 32.602, 48.0, 28.449, 48.0, 24.0);
        shape.curveTo(48.0, 10.745, 37.255, 0.0, 24.0, 0.0);
        shape.curveTo(10.744999, 0.0, 0.0, 10.745, 0.0, 24.0);
        shape.curveTo(0.0, 37.255, 10.745, 48.0, 24.0, 48.0);
        shape.curveTo(28.449, 48.0, 32.602, 46.768, 36.173, 44.658);
        shape.lineTo(53.757, 62.242);
        shape.curveTo(54.843, 63.329, 56.343, 64.0, 58.0, 64.0);
        shape.curveTo(61.314, 64.0, 64.0, 61.314, 64.0, 58.0);
        shape.curveTo(64.0, 56.343, 63.328, 54.843, 62.243, 53.758);
        shape.closePath();
        shape.moveTo(24.0, 42.0);
        shape.curveTo(14.059, 42.0, 6.0, 33.941, 6.0, 24.0);
        shape.curveTo(6.0, 14.059, 14.059, 6.0, 24.0, 6.0);
        shape.curveTo(33.941, 6.0, 42.0, 14.059, 42.0, 24.0);
        shape.curveTo(42.0, 33.941, 33.941, 42.0, 24.0, 42.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

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

public class UpIcon extends AbstractWorkbenchIcon {

    public UpIcon() {
        super();
    }

    public UpIcon(int width, int height) {
        super(width, height);
    }

    public UpIcon(int width, int height, boolean enabled) {
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

        shape.moveTo(52.159, 38.918);
        shape.lineTo(52.159, 38.918);
        shape.lineTo(34.16, 20.917);
        shape.lineTo(34.159, 20.918);
        shape.curveTo(33.613, 20.352, 32.848, 20.0, 32.0, 20.0);
        shape.curveTo(31.998, 20.0, 31.996, 20.0, 31.993, 20.0);
        shape.curveTo(31.99, 20.0, 31.989, 20.0, 31.986, 20.0);
        shape.curveTo(31.138, 20.0, 30.373, 20.352, 29.827, 20.918);
        shape.lineTo(29.826, 20.917);
        shape.lineTo(11.826, 38.917);
        shape.lineTo(11.834001, 38.925);
        shape.curveTo(11.318, 39.464, 11.0, 40.195, 11.0, 41.0);
        shape.curveTo(11.0, 42.657, 12.343, 44.0, 14.0, 44.0);
        shape.curveTo(14.91, 44.0, 15.725, 43.594, 16.275, 42.954);
        shape.lineTo(31.993, 27.235998);
        shape.lineTo(47.917, 43.16);
        shape.lineTo(47.918, 43.159);
        shape.curveTo(48.458, 43.68, 49.191, 44.0, 50.0, 44.0);
        shape.curveTo(51.657, 44.0, 53.0, 42.657, 53.0, 41.0);
        shape.curveTo(53.0, 40.191, 52.68, 39.458, 52.159, 38.918);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

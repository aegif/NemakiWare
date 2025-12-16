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

public class ExtensionIcon extends AbstractWorkbenchIcon {

    public ExtensionIcon() {
        super();
    }

    public ExtensionIcon(int width, int height) {
        super(width, height);
    }

    public ExtensionIcon(int width, int height, boolean enabled) {
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

        shape.moveTo(55.628, 11.373);
        shape.curveTo(48.599, 4.3440003, 37.201996, 4.3440003, 30.171999, 11.373);
        shape.lineTo(6.837, 34.707);
        shape.curveTo(1.7599998, 39.784, 1.7599998, 48.015, 6.837, 53.092003);
        shape.curveTo(11.914, 58.169003, 20.145, 58.169003, 25.222, 53.092003);
        shape.lineTo(47.142, 31.172003);
        shape.curveTo(50.266, 28.048002, 50.266, 22.983002, 47.142, 19.858002);
        shape.curveTo(44.017998, 16.734001, 38.952, 16.734001, 35.828, 19.858002);
        shape.lineTo(17.444, 38.243);
        shape.curveTo(16.272, 39.415, 16.272, 41.314, 17.444, 42.486);
        shape.curveTo(18.616001, 43.658, 20.515, 43.658, 21.687, 42.486);
        shape.lineTo(40.072, 24.101);
        shape.curveTo(40.852997, 23.32, 42.119, 23.32, 42.899998, 24.101);
        shape.curveTo(43.680996, 24.882, 43.680996, 26.147999, 42.899998, 26.929);
        shape.lineTo(20.979998, 48.849);
        shape.curveTo(18.245998, 51.583, 13.813997, 51.583, 11.080997, 48.849);
        shape.curveTo(8.346997, 46.114998, 8.346997, 41.683, 11.080997, 38.948997);
        shape.lineTo(34.414997, 15.614998);
        shape.curveTo(39.100998, 10.928998, 46.698997, 10.928998, 51.385998, 15.614998);
        shape.curveTo(56.072, 20.300999, 56.072, 27.898998, 51.385998, 32.586);
        shape.lineTo(31.586, 52.385);
        shape.curveTo(30.414, 53.557, 30.414, 55.455997, 31.586, 56.628);
        shape.curveTo(32.758, 57.799, 34.657, 57.799, 35.829002, 56.628);
        shape.lineTo(55.628002, 36.829);
        shape.curveTo(62.657, 29.799, 62.657, 18.402, 55.628, 11.373);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

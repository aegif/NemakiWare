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

public class TckIcon extends AbstractWorkbenchIcon {

    public TckIcon() {
        super();
    }

    public TckIcon(int width, int height) {
        super(width, height);
    }

    public TckIcon(int width, int height, boolean enabled) {
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

        shape.moveTo(32.0, 20.0);
        shape.curveTo(25.373001, 20.0, 20.0, 25.372, 20.0, 32.0);
        shape.curveTo(20.0, 38.627, 25.372, 44.0, 32.0, 44.0);
        shape.curveTo(38.628, 44.0, 44.0, 38.627, 44.0, 32.0);
        shape.curveTo(44.0, 25.373001, 38.627, 20.0, 32.0, 20.0);
        shape.closePath();
        shape.moveTo(32.0, 38.0);
        shape.curveTo(28.686, 38.0, 26.0, 35.314, 26.0, 32.0);
        shape.curveTo(26.0, 28.686, 28.686, 26.0, 32.0, 26.0);
        shape.curveTo(35.314, 26.0, 38.0, 28.686, 38.0, 32.0);
        shape.curveTo(38.0, 35.314, 35.314, 38.0, 32.0, 38.0);
        shape.closePath();
        shape.moveTo(55.518, 25.462);
        shape.curveTo(54.977, 23.505, 54.192, 21.651, 53.207, 19.926);
        shape.curveTo(54.328, 18.419, 58.187, 12.783001, 55.406002, 10.000001);
        shape.lineTo(53.978, 8.493001);
        shape.curveTo(51.599, 6.1150007, 45.523003, 9.829, 44.047, 10.791001);
        shape.curveTo(42.302002, 9.802001, 40.427002, 9.017001, 38.447002, 8.481001);
        shape.curveTo(38.13, 6.594001, 36.743004, -0.005999565, 32.828003, -0.005999565);
        shape.lineTo(31.261003, -0.005999565);
        shape.curveTo(27.900003, -0.005999565, 25.971004, 6.8180003, 25.540003, 8.528001);
        shape.curveTo(23.588003, 9.073001, 21.739002, 9.862, 20.018003, 10.848001);
        shape.curveTo(18.661, 9.839, 12.841, 5.816, 10.0, 8.656);
        shape.lineTo(8.493, 9.924);
        shape.curveTo(6.017, 12.399, 10.1310005, 18.928, 10.883, 20.071);
        shape.curveTo(9.931001, 21.769, 9.168, 23.588, 8.642, 25.505999);
        shape.curveTo(7.0010004, 25.767998, -0.0069999695, 27.119999, -0.0069999695, 31.151999);
        shape.lineTo(-0.0069999695, 32.718);
        shape.curveTo(-0.0069999695, 36.211, 7.342, 38.150997, 8.683, 38.476997);
        shape.curveTo(9.216, 40.380997, 9.983, 42.185997, 10.938, 43.871);
        shape.curveTo(10.146999, 45.109997, 6.193, 51.609997, 8.646, 54.065);
        shape.lineTo(10.073, 55.253);
        shape.curveTo(13.302, 58.482, 20.047, 53.177998, 20.047, 53.177998);
        shape.lineTo(19.733002, 52.843998);
        shape.curveTo(21.522001, 53.901997, 23.459002, 54.739998, 25.506, 55.314);
        shape.curveTo(25.841, 56.693, 27.781, 63.990997, 31.261002, 63.990997);
        shape.lineTo(32.828003, 63.990997);
        shape.curveTo(37.394005, 63.990997, 38.525, 55.469997, 38.525, 55.469997);
        shape.lineTo(38.107002, 55.456997);
        shape.curveTo(40.176003, 54.927998, 42.138, 54.131996, 43.958, 53.111996);
        shape.curveTo(45.384, 54.060997, 51.359, 57.799995, 53.744, 55.415997);
        shape.lineTo(55.331, 53.827995);
        shape.curveTo(58.514, 50.644997, 53.334, 44.131996, 53.189003, 43.950996);
        shape.curveTo(54.173004, 42.228996, 54.962, 40.379997, 55.505005, 38.425995);
        shape.curveTo(57.285004, 37.973995, 63.988007, 36.051994, 63.988007, 32.718994);
        shape.lineTo(63.988007, 31.152994);
        shape.curveTo(63.988, 26.73, 56.016, 25.533, 55.518, 25.462);
        shape.closePath();
        shape.moveTo(32.0, 50.0);
        shape.curveTo(22.059, 50.0, 14.0, 41.941, 14.0, 32.0);
        shape.curveTo(14.0, 22.059, 22.059, 14.0, 32.0, 14.0);
        shape.curveTo(41.941, 14.0, 50.0, 22.059, 50.0, 32.0);
        shape.curveTo(50.0, 41.941, 41.941, 50.0, 32.0, 50.0);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

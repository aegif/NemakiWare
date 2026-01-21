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

public class ConnectIcon extends AbstractWorkbenchIcon {

    public ConnectIcon() {
        super();
    }

    public ConnectIcon(int width, int height) {
        super(width, height);
    }

    public ConnectIcon(int width, int height, boolean enabled) {
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

        shape.moveTo(54.836, 21.812);
        shape.curveTo(53.671997, 20.648, 51.783997, 20.648, 50.62, 21.812);
        shape.lineTo(43.593998, 28.838001);
        shape.lineTo(35.162, 20.406002);
        shape.lineTo(42.188, 13.380002);
        shape.curveTo(43.352, 12.2160015, 43.352, 10.328002, 42.188, 9.164001);
        shape.curveTo(41.024, 8.0, 39.137, 8.0, 37.973, 9.164);
        shape.lineTo(30.946999, 16.189999);
        shape.lineTo(26.730999, 11.973999);
        shape.lineTo(20.758999, 17.946);
        shape.curveTo(14.565999, 24.139, 14.039999, 33.831, 19.141998, 40.642);
        shape.lineTo(9.164, 50.62);
        shape.curveTo(8.0, 51.784, 8.0, 53.672, 9.164, 54.836);
        shape.curveTo(10.327999, 56.0, 12.216, 56.0, 13.379999, 54.836);
        shape.lineTo(23.356998, 44.858997);
        shape.curveTo(30.168, 49.961, 39.86, 49.434998, 46.052998, 43.241997);
        shape.lineTo(52.024998, 37.268997);
        shape.lineTo(47.809, 33.052998);
        shape.lineTo(54.835, 26.026997);
        shape.curveTo(56.0, 24.863, 56.0, 22.976, 54.836, 21.812);
        shape.closePath();

        g.setPaint(getColor());
        g.fill(shape);
    }
}

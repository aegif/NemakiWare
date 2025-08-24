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

public class NewDocumentIcon extends DocumentIcon {

    public NewDocumentIcon() {
        super();
    }

    public NewDocumentIcon(int width, int height) {
        super(width, height);
    }

    @Override
    protected Color getColor() {
        return DEFAULT_COLOR;
    }

    @Override
    protected void paint(Graphics2D g) {
        super.paint(g);
        paintPlusBadge(g, getColor().brighter());
    }
}

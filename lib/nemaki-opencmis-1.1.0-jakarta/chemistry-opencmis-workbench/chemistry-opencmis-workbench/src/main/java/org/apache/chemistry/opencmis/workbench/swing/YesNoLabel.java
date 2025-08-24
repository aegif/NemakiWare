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
package org.apache.chemistry.opencmis.workbench.swing;

import javax.swing.Icon;
import javax.swing.JLabel;

import org.apache.chemistry.opencmis.workbench.icons.NoIcon;
import org.apache.chemistry.opencmis.workbench.icons.YesIcon;

public class YesNoLabel extends JLabel {

    private static final long serialVersionUID = 1L;

    public static final Icon TRUE_ICON = new YesIcon(18, 18);
    public static final Icon FALSE_ICON = new NoIcon(18, 18);

    public static final String YES_TEXT = "Yes";
    public static final String NO_TEXT = "No";

    private boolean value = true;

    public YesNoLabel() {
        super(YES_TEXT, TRUE_ICON, LEFT);
    }

    public boolean getValue() {
        return value;
    }

    public void setValue(boolean value) {
        if (this.value != value) {
            this.value = value;
            setIcon(value ? TRUE_ICON : FALSE_ICON);
            setText(value ? YES_TEXT : NO_TEXT);
            invalidate();
        }
    }
}

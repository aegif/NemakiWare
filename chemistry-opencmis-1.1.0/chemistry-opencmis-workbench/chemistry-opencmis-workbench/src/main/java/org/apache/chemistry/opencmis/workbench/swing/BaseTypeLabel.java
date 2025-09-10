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

import java.util.EnumMap;
import java.util.Map;

import javax.swing.Icon;
import javax.swing.JLabel;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.workbench.icons.DocumentIcon;
import org.apache.chemistry.opencmis.workbench.icons.FolderIcon;
import org.apache.chemistry.opencmis.workbench.icons.ItemIcon;
import org.apache.chemistry.opencmis.workbench.icons.PolicyIcon;
import org.apache.chemistry.opencmis.workbench.icons.RelationshipIcon;
import org.apache.chemistry.opencmis.workbench.icons.SecondaryIcon;

public class BaseTypeLabel extends JLabel {

    private static final long serialVersionUID = 1L;

    private static final int ICON_SIZE = 16;
    private static final Map<BaseTypeId, Icon> ICONS;

    static {
        ICONS = new EnumMap<BaseTypeId, Icon>(BaseTypeId.class);
        ICONS.put(BaseTypeId.CMIS_DOCUMENT, new DocumentIcon(ICON_SIZE, ICON_SIZE));
        ICONS.put(BaseTypeId.CMIS_FOLDER, new FolderIcon(ICON_SIZE, ICON_SIZE));
        ICONS.put(BaseTypeId.CMIS_RELATIONSHIP, new RelationshipIcon(ICON_SIZE, ICON_SIZE));
        ICONS.put(BaseTypeId.CMIS_POLICY, new PolicyIcon(ICON_SIZE, ICON_SIZE));
        ICONS.put(BaseTypeId.CMIS_ITEM, new ItemIcon(ICON_SIZE, ICON_SIZE));
        ICONS.put(BaseTypeId.CMIS_SECONDARY, new SecondaryIcon(ICON_SIZE, ICON_SIZE));
    }

    private BaseTypeId value = null;

    public BaseTypeLabel() {
        super("", null, LEFT);
    }

    public BaseTypeId getValue() {
        return value;
    }

    public void setValue(BaseTypeId value) {
        if (this.value != value) {
            this.value = value;

            if (value == null) {
                setIcon(null);
                setText("");
            } else {
                setIcon(ICONS.get(value));
                setText(value.value());
            }

            invalidate();
        }
    }
}

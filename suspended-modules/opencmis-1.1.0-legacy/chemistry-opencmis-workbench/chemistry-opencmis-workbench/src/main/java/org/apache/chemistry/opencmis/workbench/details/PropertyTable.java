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
package org.apache.chemistry.opencmis.workbench.details;

import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.List;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.Property;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.workbench.PropertyEditorFrame;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.swing.CollectionRenderer;

public class PropertyTable extends AbstractDetailsTable {

    private static final long serialVersionUID = 1L;

    private static final String[] COLUMN_NAMES = { "Name", "Type", "Value", "Property ID", "Object Type ID" };
    private static final int[] COLUMN_WIDTHS = { 200, 80, 400, 200, 200 };

    public PropertyTable(ClientModel model) {
        super();
        init(model, COLUMN_NAMES, COLUMN_WIDTHS);
        setDefaultRenderer(Collection.class, new CollectionRenderer(true));
    }

    @Override
    public void doubleClickAction(MouseEvent e, int rowIndex) {
        if (getObject().hasAllowableAction(Action.CAN_UPDATE_PROPERTIES)) {
            new PropertyEditorFrame(getClientModel(), getObject());
        }
    }

    @Override
    public int getDetailRowCount() {
        return getObject().getProperties().size();
    }

    @Override
    public Object getDetailValueAt(int rowIndex, int columnIndex) {
        CmisObject obj = getObject();
        Property<?> property = obj.getProperties().get(rowIndex);

        switch (columnIndex) {
        case 0:
            return property.getDefinition().getDisplayName();
        case 1:
            return property.getDefinition().getPropertyType().value();
        case 2:
            return property.getValues();
        case 3:
            return property.getId();
        case 4:
            return findObjectType(obj, property.getId());
        default:
        }

        return null;
    }

    @Override
    public Class<?> getDetailColumClass(int columnIndex) {
        if (columnIndex == 2) {
            return Collection.class;
        }

        return super.getDetailColumClass(columnIndex);
    }

    private String findObjectType(CmisObject obj, String propertyId) {
        List<ObjectType> types = obj.findObjectType(propertyId);
        if (types == null || types.isEmpty()) {
            return "???";
        }

        StringBuilder sb = new StringBuilder(64);

        for (ObjectType type : types) {
            if (sb.length() > 0) {
                sb.append(" ,");
            }
            sb.append(type.getId());
        }

        return sb.toString();
    }
}

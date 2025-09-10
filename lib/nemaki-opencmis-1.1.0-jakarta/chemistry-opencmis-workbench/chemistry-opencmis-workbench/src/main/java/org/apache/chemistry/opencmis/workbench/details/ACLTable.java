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

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.workbench.AclEditorFrame;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.worker.LoadFolderWorker;
import org.apache.chemistry.opencmis.workbench.worker.LoadObjectWorker;

public class ACLTable extends AbstractDetailsTable {

    private static final long serialVersionUID = 1L;

    private static final String[] COLUMN_NAMES = { "Principal", "Permissions", "Direct" };
    private static final int[] COLUMN_WIDTHS = { 200, 400, 50 };

    public ACLTable(ClientModel model) {
        super();
        init(model, COLUMN_NAMES, COLUMN_WIDTHS);
    }

    @Override
    public void doubleClickAction(MouseEvent e, int rowIndex) {
        AllowableActions aa = getObject().getAllowableActions();

        if ((aa == null) || (aa.getAllowableActions() == null)
                || aa.getAllowableActions().contains(Action.CAN_APPLY_ACL)) {
            new AclEditorFrame(getClientModel(), getObject());

            LoadObjectWorker.reloadObject(this, getClientModel());
            LoadFolderWorker.reloadFolder(this, getClientModel());
        }
    }

    @Override
    public int getDetailRowCount() {
        if ((getObject().getAcl() == null) || (getObject().getAcl().getAces() == null)) {
            return 0;
        }

        return getObject().getAcl().getAces().size();
    }

    @Override
    public Object getDetailValueAt(int rowIndex, int columnIndex) {
        Ace ace = getObject().getAcl().getAces().get(rowIndex);

        switch (columnIndex) {
        case 0:
            return ace.getPrincipalId();
        case 1:
            return ace.getPermissions();
        case 2:
            return ace.isDirect();
        default:
        }

        return null;
    }

    @Override
    public Class<?> getDetailColumClass(int columnIndex) {
        if (columnIndex == 1) {
            return Collection.class;
        } else if (columnIndex == 2) {
            return Boolean.class;
        }

        return super.getDetailColumClass(columnIndex);
    }
}

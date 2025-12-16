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

import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.Policy;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.worker.LoadObjectWorker;

public class PolicyTable extends AbstractDetailsTable {

    private static final long serialVersionUID = 1L;

    private static final String[] COLUMN_NAMES = { "Name", "Id" };
    private static final int[] COLUMN_WIDTHS = { 200, 400 };

    public PolicyTable(ClientModel model) {
        super();
        init(model, COLUMN_NAMES, COLUMN_WIDTHS);
    }

    @Override
    public void singleClickAction(MouseEvent e, int rowIndex, int colIndex) {
        if (getColumnClass(colIndex) != ObjectId.class) {
            return;
        }

        LoadObjectWorker.loadObject(this, getClientModel(),
                getObject().getPolicies().get(getRowSorter().convertRowIndexToModel(rowIndex)).getId());
        setTab(0);
    }

    @Override
    public int getDetailRowCount() {
        if (getObject().getPolicies() == null) {
            return 0;
        }

        return getObject().getPolicies().size();
    }

    @Override
    public Object getDetailValueAt(int rowIndex, int columnIndex) {
        Policy policy = getObject().getPolicies().get(rowIndex);

        switch (columnIndex) {
        case 0:
            return policy.getName();
        case 1:
            return policy;
        default:
        }

        return null;
    }

    @Override
    public Class<?> getDetailColumClass(int columnIndex) {
        if (columnIndex == 1) {
            return ObjectId.class;
        }

        return super.getDetailColumClass(columnIndex);
    }
}

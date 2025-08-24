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

import org.apache.chemistry.opencmis.client.api.Rendition;
import org.apache.chemistry.opencmis.workbench.ClientHelper;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;

public class RenditionTable extends AbstractDetailsTable {

    private static final long serialVersionUID = 1L;

    private static final String[] COLUMN_NAMES = { "Title", "Kind", "MIME Type", "Size", "Stream Id" };
    private static final int[] COLUMN_WIDTHS = { 200, 200, 80, 80, 200 };

    public RenditionTable(ClientModel model) {
        super();
        init(model, COLUMN_NAMES, COLUMN_WIDTHS);
    }

    @Override
    public void doubleClickAction(MouseEvent e, int rowIndex) {
        String streamId = getObject().getRenditions().get(getRowSorter().convertRowIndexToModel(rowIndex))
                .getStreamId();

        if (e.isShiftDown()) {
            ClientHelper.download(this.getParent(), getObject(), streamId);
        } else {
            ClientHelper.open(this.getParent(), getObject(), streamId);
        }
    }

    @Override
    public int getDetailRowCount() {
        if (getObject().getRenditions() == null) {
            return 0;
        }

        return getObject().getRenditions().size();
    }

    @Override
    public Object getDetailValueAt(int rowIndex, int columnIndex) {
        Rendition rendition = getObject().getRenditions().get(rowIndex);

        switch (columnIndex) {
        case 0:
            return rendition.getTitle();
        case 1:
            return rendition.getKind();
        case 2:
            return rendition.getMimeType();
        case 3:
            return rendition.getLength();
        case 4:
            return rendition.getStreamId();
        default:
        }

        return null;
    }
}

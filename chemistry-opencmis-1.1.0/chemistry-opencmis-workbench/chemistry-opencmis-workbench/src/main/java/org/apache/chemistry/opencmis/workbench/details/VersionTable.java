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

import java.awt.Cursor;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.swing.SwingUtilities;

import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.workbench.ClientHelper;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.model.ClientModelEvent;
import org.apache.chemistry.opencmis.workbench.worker.LoadObjectWorker;

public class VersionTable extends AbstractDetailsTable {

    private static final long serialVersionUID = 1L;

    private static final String[] COLUMN_NAMES = { "Name", "Label", "Latest", "Major", "Latest Major", "Id",
            "Filename", "MIME Type", "Length" };
    private static final int[] COLUMN_WIDTHS = { 200, 200, 50, 50, 50, 400, 200, 100, 100 };

    private List<Document> versions;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public VersionTable(ClientModel model) {
        super();

        versions = Collections.emptyList();
        init(model, COLUMN_NAMES, COLUMN_WIDTHS);
    }

    @Override
    public void objectLoaded(ClientModelEvent event) {
        lock.writeLock().lock();
        try {
            versions = Collections.emptyList();
        } finally {
            lock.writeLock().unlock();
        }

        if (getObject() instanceof Document) {
            final Document doc = (Document) getObject();

            boolean fetchVersions = (getObject().getAllowableActions() == null)
                    || (getObject().getAllowableActions().getAllowableActions() == null)
                    || doc.hasAllowableAction(Action.CAN_GET_ALL_VERSIONS);

            if (fetchVersions) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                            List<Document> newVersions = doc.getAllVersions(getClientModel().getClientSession()
                                    .getVersionOperationContext());

                            lock.writeLock().lock();
                            try {
                                versions = newVersions;
                            } finally {
                                lock.writeLock().unlock();
                            }
                        } catch (Exception ex) {
                            if (!(ex instanceof CmisNotSupportedException)) {
                                ClientHelper.showError(null, ex);
                            }
                        } finally {
                            setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                        }
                        ((DetailsTableModel) getModel()).fireTableDataChanged();
                    }
                });
            }
        }

        super.objectLoaded(event);
    }

    @Override
    public void singleClickAction(MouseEvent e, int rowIndex, int colIndex) {
        if (getColumnClass(colIndex) != ObjectId.class) {
            return;
        }

        String versionId = null;
        lock.readLock().lock();
        try {
            versionId = versions.get(getRowSorter().convertRowIndexToModel(rowIndex)).getId();
        } finally {
            lock.readLock().unlock();
        }

        LoadObjectWorker.loadObject(this, getClientModel(), versionId);
        setTab(0);
    }

    @Override
    public void doubleClickAction(MouseEvent e, int rowIndex) {
        lock.readLock().lock();
        try {
            if (e.isShiftDown()) {
                ClientHelper.download(this.getParent(), versions.get(getRowSorter().convertRowIndexToModel(rowIndex)),
                        null);
            } else {
                ClientHelper
                        .open(this.getParent(), versions.get(getRowSorter().convertRowIndexToModel(rowIndex)), null);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int getDetailRowCount() {
        if (!(getObject() instanceof Document)) {
            return 0;
        }

        lock.readLock().lock();
        try {
            return versions.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Object getDetailValueAt(int rowIndex, int columnIndex) {
        Document version = null;

        lock.readLock().lock();
        try {
            version = versions.get(rowIndex);
        } finally {
            lock.readLock().unlock();
        }

        switch (columnIndex) {
        case 0:
            return version.getName();
        case 1:
            return version.getVersionLabel();
        case 2:
            return version.isLatestVersion();
        case 3:
            return version.isMajorVersion();
        case 4:
            return version.isLatestMajorVersion();
        case 5:
            return version;
        case 6:
            return version.getContentStreamFileName();
        case 7:
            return version.getContentStreamMimeType();
        case 8:
            return version.getContentStreamLength() == -1 ? null : version.getContentStreamLength();
        default:
        }

        return null;
    }

    @Override
    public Class<?> getDetailColumClass(int columnIndex) {
        if ((columnIndex == 2) || (columnIndex == 3) || (columnIndex == 4)) {
            return Boolean.class;
        } else if (columnIndex == 5) {
            return ObjectId.class;
        } else if (columnIndex == 8) {
            return Long.class;
        }

        return super.getDetailColumClass(columnIndex);
    }
}

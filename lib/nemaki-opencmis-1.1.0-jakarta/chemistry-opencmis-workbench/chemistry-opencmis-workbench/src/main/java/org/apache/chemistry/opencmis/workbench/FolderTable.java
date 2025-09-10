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
package org.apache.chemistry.opencmis.workbench;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

import javax.swing.DropMode;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.workbench.icons.CheckedOutIcon;
import org.apache.chemistry.opencmis.workbench.icons.DocumentIcon;
import org.apache.chemistry.opencmis.workbench.icons.FolderIcon;
import org.apache.chemistry.opencmis.workbench.icons.ItemIcon;
import org.apache.chemistry.opencmis.workbench.icons.PolicyIcon;
import org.apache.chemistry.opencmis.workbench.icons.PwcIcon;
import org.apache.chemistry.opencmis.workbench.icons.RelationshipIcon;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.model.ClientModelEvent;
import org.apache.chemistry.opencmis.workbench.model.FolderListener;
import org.apache.chemistry.opencmis.workbench.swing.GregorianCalendarRenderer;
import org.apache.chemistry.opencmis.workbench.worker.DeleteWorker;
import org.apache.chemistry.opencmis.workbench.worker.LoadFolderWorker;
import org.apache.chemistry.opencmis.workbench.worker.LoadObjectWorker;
import org.apache.chemistry.opencmis.workbench.worker.TempFileContentWorker;

public class FolderTable extends JTable implements FolderListener {

    private static final long serialVersionUID = 1L;

    private static final String[] COLUMN_NAMES = { "", "Name", "Type", "Content Type", "Size", "Creation Date",
            "Created by", "Modification Date", "Modified by", "Id" };
    private static final int[] COLUMN_WIDTHS = { ClientHelper.OBJECT_ICON_SIZE + 4, 200, 150, 150, 80, 180, 100, 180,
            100, 300 };
    public static final int ID_COLUMN = 9;

    private final ClientModel model;

    private Map<BaseTypeId, Icon> icons;
    private Icon checkedOutIcon;
    private Icon pwcIcon;

    private JMenuItem downloadItem;
    private JMenuItem deleteItem;

    public FolderTable(final ClientModel model) {
        super();

        this.model = model;

        setModel(new FolderTableModel());

        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setAutoResizeMode(AUTO_RESIZE_OFF);
        setAutoCreateRowSorter(true);

        setDefaultRenderer(GregorianCalendar.class, new GregorianCalendarRenderer());
        setTransferHandler(new FolderTransferHandler());
        setDragEnabled(true);
        setDropMode(DropMode.INSERT);

        for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
            TableColumn column = getColumnModel().getColumn(i);
            column.setPreferredWidth(WorkbenchScale.scaleInt(COLUMN_WIDTHS[i]));
        }

        setRowHeight((int) (getFontMetrics(getFont()).getHeight() * 1.1));

        final JPopupMenu popup = new JPopupMenu();

        // popup menu: clipboard
        final JMenuItem clipboardAllItem = new JMenuItem("Copy all rows to clipboard");
        popup.add(clipboardAllItem);

        clipboardAllItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ClientHelper.copyTableToClipboard(FolderTable.this, false);
            }
        });

        final JMenuItem clipboardSelectedItem = new JMenuItem("Copy selected rows to clipboard");
        popup.add(clipboardSelectedItem);

        clipboardSelectedItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ClientHelper.copyTableToClipboard(FolderTable.this, true);
            }
        });

        popup.addSeparator();

        // popup menu: delete
        deleteItem = new JMenuItem("Delete");
        deleteItem.setEnabled(false);
        popup.add(deleteItem);

        deleteItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (model.getCurrentObject() != null) {
                    int answer = JOptionPane.showConfirmDialog(FolderTable.this,
                            "Do you really want to delete '" + model.getCurrentObject().getName() + "'?", "Delete",
                            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

                    if (answer == JOptionPane.YES_OPTION) {
                        DeleteWorker worker = new DeleteWorker(FolderTable.this, model.getCurrentObject()) {
                            @Override
                            protected void done() {
                                super.done();
                                LoadFolderWorker.reloadFolder(FolderTable.this, model);
                            }
                        };
                        worker.executeTask();
                    }
                }
            }
        });

        // popup menu: download
        downloadItem = new JMenuItem("Download");
        downloadItem.setEnabled(false);
        popup.add(downloadItem);

        downloadItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (model.getCurrentObject() != null) {
                    ClientHelper.download(FolderTable.this, model.getCurrentObject(), null);
                }
            }
        });

        getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (e.getValueIsAdjusting()) {
                    return;
                }

                int row = getSelectedRow();
                if (row > -1) {
                    String id = getModel().getValueAt(getRowSorter().convertRowIndexToModel(row), ID_COLUMN).toString();

                    LoadObjectWorker worker = new LoadObjectWorker(FolderTable.this, model, id) {
                        @Override
                        protected void done() {
                            super.done();

                            if (model.getCurrentObject() != null
                                    && model.getCurrentObject().getAllowableActions() != null) {
                                CmisObject object = model.getCurrentObject();
                                deleteItem.setEnabled(object.hasAllowableAction(Action.CAN_DELETE_OBJECT)
                                        || object.hasAllowableAction(Action.CAN_DELETE_TREE));
                                downloadItem.setEnabled(object.hasAllowableAction(Action.CAN_GET_CONTENT_STREAM));
                            } else {
                                deleteItem.setEnabled(false);
                                downloadItem.setEnabled(false);
                            }
                        }
                    };
                    worker.executeTask();
                }
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    doAction(e.isShiftDown());
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        addKeyListener(new KeyListener() {
            @Override
            public void keyTyped(KeyEvent e) {
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                    doAction(e.isShiftDown());
                }
            }

            @Override
            public void keyPressed(KeyEvent e) {
            }
        });

        // load icon and set icon column size
        loadIcons();
        getColumnModel().getColumn(0)
                .setPreferredWidth((int) (icons.get(BaseTypeId.CMIS_DOCUMENT).getIconWidth() * 1.1));
    }

    private void loadIcons() {
        icons = new EnumMap<BaseTypeId, Icon>(BaseTypeId.class);
        icons.put(BaseTypeId.CMIS_DOCUMENT,
                new DocumentIcon(ClientHelper.OBJECT_ICON_SIZE, ClientHelper.OBJECT_ICON_SIZE));
        icons.put(BaseTypeId.CMIS_FOLDER, new FolderIcon(ClientHelper.OBJECT_ICON_SIZE, ClientHelper.OBJECT_ICON_SIZE));
        icons.put(BaseTypeId.CMIS_RELATIONSHIP,
                new RelationshipIcon(ClientHelper.OBJECT_ICON_SIZE, ClientHelper.OBJECT_ICON_SIZE));
        icons.put(BaseTypeId.CMIS_POLICY, new PolicyIcon(ClientHelper.OBJECT_ICON_SIZE, ClientHelper.OBJECT_ICON_SIZE));
        icons.put(BaseTypeId.CMIS_ITEM, new ItemIcon(ClientHelper.OBJECT_ICON_SIZE, ClientHelper.OBJECT_ICON_SIZE));

        checkedOutIcon = new CheckedOutIcon(ClientHelper.OBJECT_ICON_SIZE, ClientHelper.OBJECT_ICON_SIZE);
        pwcIcon = new PwcIcon(ClientHelper.OBJECT_ICON_SIZE, ClientHelper.OBJECT_ICON_SIZE);
    }

    @Override
    public void folderLoaded(final ClientModelEvent event) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                event.getClientModel().getCurrentChildren();
                ((FolderTableModel) getModel()).fireTableDataChanged();
            }
        });
    }

    private void doAction(boolean alternate) {
        int row = getSelectedRow();
        if ((row > -1) && (row < model.getCurrentChildren().size())) {
            String id = getModel().getValueAt(getRowSorter().convertRowIndexToModel(row), ID_COLUMN).toString();
            CmisObject object = model.getFromCurrentChildren(id);

            if (object instanceof Document) {
                if (alternate) {
                    ClientHelper.download(this.getParent(), object, null);
                } else {
                    ClientHelper.open(this.getParent(), object, null);
                }
            } else if (object instanceof Folder) {
                LoadFolderWorker.loadFolderById(FolderTable.this, model, object.getId());
            }
        }
    }

    class FolderTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        @Override
        public String getColumnName(int columnIndex) {
            return COLUMN_NAMES[columnIndex];
        }

        @Override
        public int getColumnCount() {
            return COLUMN_NAMES.length;
        }

        @Override
        public int getRowCount() {
            return model.getCurrentChildren().size();
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            CmisObject obj = model.getCurrentChildren().get(rowIndex);

            switch (columnIndex) {
            case 0:
                if (obj instanceof Document) {
                    Document doc = (Document) obj;
                    if (Boolean.TRUE.equals(doc.isVersionSeriesCheckedOut())) {
                        if (Boolean.TRUE.equals(doc.isVersionSeriesPrivateWorkingCopy())) {
                            return pwcIcon;
                        } else {
                            return checkedOutIcon;
                        }
                    } else {
                        return icons.get(BaseTypeId.CMIS_DOCUMENT);
                    }
                }
                return icons.get(obj.getBaseTypeId());
            case 1:
                return obj.getName();
            case 2:
                return obj.getType().getId();
            case 3:
                if (obj instanceof Document) {
                    return ((Document) obj).getContentStreamMimeType();
                } else {
                    return null;
                }
            case 4:
                if (obj instanceof Document) {
                    return ((Document) obj).getContentStreamLength();
                } else {
                    return null;
                }
            case 5:
                return obj.getCreationDate();
            case 6:
                return obj.getCreatedBy();
            case 7:
                return obj.getLastModificationDate();
            case 8:
                return obj.getLastModifiedBy();
            case ID_COLUMN:
                return obj.getId();
            default:
            }

            return "";
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            switch (columnIndex) {
            case 0:
                return ImageIcon.class;
            case 4:
                return Long.class;
            case 5:
            case 7:
                return GregorianCalendar.class;
            default:
            }

            return String.class;
        }
    }

    private class FolderTransferHandler extends TransferHandler {

        private static final long serialVersionUID = 1L;

        public FolderTransferHandler() {
            super();
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                return false;
            }

            if (!support.isDrop()) {
                return false;
            }

            return true;
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }

            File file = null;
            try {
                List<File> fileList = (List<File>) support.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);

                if (fileList == null || fileList.size() != 1 || fileList.get(0) == null || !fileList.get(0).isFile()) {
                    return false;
                }

                file = fileList.get(0);
            } catch (Exception ex) {
                ClientHelper.showError(null, ex);
                return false;
            }

            new CreateDocumentDialog(null, model, file);

            return true;
        }

        @Override
        public int getSourceActions(JComponent c) {
            return COPY;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            int row = getSelectedRow();
            if (row < 0 || row >= model.getCurrentChildren().size()) {
                return null;
            }

            String id = getValueAt(row, ID_COLUMN).toString();
            final CmisObject object = model.getFromCurrentChildren(id);
            if (!(object instanceof Document)) {
                return null;
            }

            return new Transferable() {
                @Override
                public boolean isDataFlavorSupported(DataFlavor flavor) {
                    return flavor == DataFlavor.javaFileListFlavor;
                }

                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[] { DataFlavor.javaFileListFlavor };
                }

                @Override
                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
                    if (flavor != DataFlavor.javaFileListFlavor) {
                        throw new UnsupportedFlavorException(flavor);
                    }

                    try {
                        TempFileContentWorker worker = new TempFileContentWorker(null, (Document) object);
                        return Collections.singletonList(worker.executeSync());
                    } catch (Exception e) {
                        ClientHelper.showError(FolderTable.this, e);
                        return null;
                    }
                }
            };
        }
    }
}

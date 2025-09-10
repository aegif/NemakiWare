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
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.util.Collection;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.workbench.ClientHelper;
import org.apache.chemistry.opencmis.workbench.WorkbenchScale;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.model.ClientModelEvent;
import org.apache.chemistry.opencmis.workbench.model.ObjectListener;
import org.apache.chemistry.opencmis.workbench.swing.CollectionRenderer;
import org.apache.chemistry.opencmis.workbench.swing.IdRenderer;

public abstract class AbstractDetailsTable extends JTable implements ObjectListener {

    private static final long serialVersionUID = 1L;

    private static final Cursor HAND_CURSOR = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
    private static final Cursor DEFAULT_CURSOR = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR);

    private ClientModel model;
    private String[] columnNames;

    @SuppressWarnings("PMD.ArrayIsStoredDirectly")
    public void init(final ClientModel model, final String[] columnNames, final int[] colummnWidths) {
        this.model = model;
        model.addObjectListener(this);

        this.columnNames = columnNames;

        setModel(new DetailsTableModel(this));

        setDefaultRenderer(Collection.class, new CollectionRenderer());
        setDefaultRenderer(ObjectId.class, new IdRenderer());
        setAutoResizeMode(AUTO_RESIZE_OFF);
        setAutoCreateRowSorter(true);

        for (int i = 0; i < colummnWidths.length; i++) {
            TableColumn column = getColumnModel().getColumn(i);
            column.setPreferredWidth(WorkbenchScale.scaleInt(colummnWidths[i]));
        }

        setRowHeight((int) (getFontMetrics(getFont()).getHeight() * 1.1));

        setFillsViewportHeight(true);

        final JPopupMenu popup = new JPopupMenu();

        final JMenuItem clipboardAllItem = new JMenuItem("Copy all rows to clipboard");
        popup.add(clipboardAllItem);

        clipboardAllItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ClientHelper.copyTableToClipboard(AbstractDetailsTable.this, false);
            }
        });

        final JMenuItem clipboardSelectedItem = new JMenuItem("Copy selected rows to clipboard");
        popup.add(clipboardSelectedItem);

        clipboardSelectedItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ClientHelper.copyTableToClipboard(AbstractDetailsTable.this, true);
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    int row = rowAtPoint(e.getPoint());
                    int column = columnAtPoint(e.getPoint());
                    if (row > -1 && column > -1) {
                        if (e.getClickCount() == 1) {
                            singleClickAction(e, row, column);
                        } else if (e.getClickCount() == 2) {
                            doubleClickAction(e, row);
                        }
                    }
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

        addMouseMotionListener(new MouseMotionListener() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                int column = columnAtPoint(e.getPoint());
                if (row > -1 && getColumnClass(column) == ObjectId.class) {
                    setCursor(HAND_CURSOR);
                } else {
                    setCursor(DEFAULT_CURSOR);
                }

            }

            @Override
            public void mouseDragged(MouseEvent e) {
            }
        });
    }

    @Override
    public void objectLoaded(ClientModelEvent event) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ((DetailsTableModel) getModel()).fireTableDataChanged();
            }
        });
    }

    public CmisObject getObject() {
        return model.getCurrentObject();
    }

    public ClientModel getClientModel() {
        return model;
    }

    public String[] getColumnNames() {
        return columnNames;
    }

    public abstract int getDetailRowCount();

    public abstract Object getDetailValueAt(int rowIndex, int columnIndex);

    public Class<?> getDetailColumClass(int columnIndex) {
        return String.class;
    }

    public void singleClickAction(MouseEvent e, int rowIndex, int colIndex) {
    }

    public void doubleClickAction(MouseEvent e, int rowIndex) {
    }

    public void setTab(int tab) {
        ((JTabbedPane) getParent().getParent().getParent()).setSelectedIndex(tab);
    }

    static class DetailsTableModel extends AbstractTableModel {

        private final AbstractDetailsTable table;

        public DetailsTableModel(AbstractDetailsTable table) {
            this.table = table;
        }

        private static final long serialVersionUID = 1L;

        @Override
        public String getColumnName(int columnIndex) {
            return table.getColumnNames()[columnIndex];
        }

        @Override
        public int getColumnCount() {
            return table.getColumnNames().length;
        }

        @Override
        public int getRowCount() {
            if (table.getObject() == null) {
                return 0;
            }

            return table.getDetailRowCount();
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (table.getObject() == null) {
                return null;
            }

            return table.getDetailValueAt(rowIndex, columnIndex);
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return table.getDetailColumClass(columnIndex);
        }
    }
}

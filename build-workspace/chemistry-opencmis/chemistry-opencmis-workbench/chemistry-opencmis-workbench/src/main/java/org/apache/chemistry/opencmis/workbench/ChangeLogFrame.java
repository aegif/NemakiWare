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

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;

import org.apache.chemistry.opencmis.client.api.ChangeEvent;
import org.apache.chemistry.opencmis.client.api.ChangeEvents;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.swing.CollectionRenderer;
import org.apache.chemistry.opencmis.workbench.swing.GregorianCalendarRenderer;
import org.apache.chemistry.opencmis.workbench.worker.InfoWorkbenchWorker;

public class ChangeLogFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final String WINDOW_TITLE = "CMIS Change Log";

    private final ClientModel model;

    private JTextField changeLogTokenField;
    private ChangeLogTable changeLogTable;

    public ChangeLogFrame(ClientModel model) {
        super();

        this.model = model;
        createGUI();
    }

    private void createGUI() {
        setTitle(WINDOW_TITLE + " - " + model.getRepositoryName());
        setIconImages(ClientHelper.getCmisIconImages());

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setPreferredSize(new Dimension((int) (screenSize.getWidth() / 3), (int) (screenSize.getHeight() / 1.5)));
        setMinimumSize(new Dimension(200, 60));

        setLayout(new BorderLayout());

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3)));

        inputPanel.add(new JLabel("Change Log Token: "), BorderLayout.LINE_START);

        changeLogTokenField = new JTextField();
        try {
            changeLogTokenField.setText(model.getRepositoryInfo().getLatestChangeLogToken());
        } catch (Exception e) {
            changeLogTokenField.setText("");
        }
        inputPanel.add(changeLogTokenField, BorderLayout.CENTER);

        JButton loadButton = new JButton("Load");
        loadButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {

                String changeLogToken = changeLogTokenField.getText();
                if (changeLogToken.trim().length() == 0) {
                    changeLogToken = null;
                }

                (new ChangeLogWorker(ChangeLogFrame.this, changeLogToken)).executeTask();
            }
        });
        inputPanel.add(loadButton, BorderLayout.LINE_END);
        getRootPane().setDefaultButton(loadButton);

        add(inputPanel, BorderLayout.PAGE_START);

        changeLogTable = new ChangeLogTable();
        add(new JScrollPane(changeLogTable), BorderLayout.CENTER);

        ClientHelper.installEscapeBinding(this, getRootPane(), true);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        pack();

        setLocationRelativeTo(null);
        setVisible(true);
    }

    private static class ChangeLogTable extends JTable {

        private static final long serialVersionUID = 1L;

        private static final String[] COLUMN_NAMES = { "Change Type", "Object Id", "Change Time", "Properties" };
        private static final int[] COLUMN_WIDTHS = { 100, 200, 200, 400 };

        private List<ChangeEvent> changeEvents;

        public ChangeLogTable() {
            setDefaultRenderer(GregorianCalendar.class, new GregorianCalendarRenderer());
            setDefaultRenderer(Collection.class, new CollectionRenderer());
            setModel(new ChangeLogTableModel(this));

            setAutoResizeMode(AUTO_RESIZE_OFF);
            setAutoCreateRowSorter(true);

            for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
                TableColumn column = getColumnModel().getColumn(i);
                column.setPreferredWidth(WorkbenchScale.scaleInt(COLUMN_WIDTHS[i]));
            }

            setRowHeight((int) (getFontMetrics(getFont()).getHeight() * 1.1));

            setFillsViewportHeight(true);

            final JPopupMenu popup = new JPopupMenu();

            final JMenuItem clipboardAllItem = new JMenuItem("Copy all rows to clipboard");
            popup.add(clipboardAllItem);

            clipboardAllItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    ClientHelper.copyTableToClipboard(ChangeLogTable.this, false);
                }
            });

            final JMenuItem clipboardSelectedItem = new JMenuItem("Copy selected rows to clipboard");
            popup.add(clipboardSelectedItem);

            clipboardSelectedItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    ClientHelper.copyTableToClipboard(ChangeLogTable.this, true);
                }
            });

            addMouseListener(new MouseAdapter() {

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
        }

        public void setChangeEvents(List<ChangeEvent> changeEvents) {
            this.changeEvents = changeEvents;
            ((AbstractTableModel) getModel()).fireTableDataChanged();
        }

        public List<ChangeEvent> getChangeEvents() {
            return changeEvents;
        }

        static class ChangeLogTableModel extends AbstractTableModel {

            private static final long serialVersionUID = 1L;

            private final ChangeLogTable table;

            public ChangeLogTableModel(ChangeLogTable table) {
                this.table = table;
            }

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
                if (table.getChangeEvents() == null) {
                    return 0;
                }

                return table.getChangeEvents().size();
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                ChangeEvent event = table.getChangeEvents().get(rowIndex);

                switch (columnIndex) {
                case 0:
                    return event.getChangeType() == null ? "?" : event.getChangeType().value();
                case 1:
                    return event.getObjectId() == null ? "?" : event.getObjectId();
                case 2:
                    return event.getChangeTime();
                case 3:
                    return event.getProperties().entrySet();
                default:
                }

                return null;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 2) {
                    return GregorianCalendar.class;
                } else if (columnIndex == 3) {
                    return Collection.class;
                }

                return super.getColumnClass(columnIndex);
            }
        }
    }

    private class ChangeLogWorker extends InfoWorkbenchWorker {

        // in
        private String changeLogToken;

        // out
        private ChangeEvents events;

        public ChangeLogWorker(Window parent, String changeLogToken) {
            super(parent);
            this.changeLogToken = changeLogToken;
        }

        @Override
        protected String getTitle() {
            return "Retrieving Change Log";
        }

        @Override
        protected String getMessage() {
            return "Retrieving Change Log...";
        }

        @Override
        protected Object doInBackground() throws Exception {
            events = model.getClientSession().getSession().getContentChanges(changeLogToken, true, 1000);

            return null;
        }

        @Override
        protected void finializeTask() {
            if (isCancelled() || events == null) {
                changeLogTable.setChangeEvents(Collections.<ChangeEvent> emptyList());
                changeLogTokenField.setText("");
            } else {
                changeLogTable.setChangeEvents(events.getChangeEvents());
                changeLogTokenField.setText(events.getLatestChangeLogToken() == null ? "" : events
                        .getLatestChangeLogToken());
            }
        }
    }
}

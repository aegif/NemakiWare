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
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.NumberFormatter;
import javax.swing.undo.UndoManager;

import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.workbench.icons.QueryIcon;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.swing.IdRenderer;
import org.apache.chemistry.opencmis.workbench.worker.InfoWorkbenchWorker;
import org.apache.chemistry.opencmis.workbench.worker.LoadObjectWorker;

public class QueryFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final Cursor HAND_CURSOR = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
    private static final Cursor DEFAULT_CURSOR = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR);

    private static final String WINDOW_TITLE = "CMIS Query";
    private static final String DEFAULT_QUERY = "SELECT * FROM cmis:document";

    private final ClientModel model;

    private JTextArea queryText;
    private UndoManager undoManager;
    private JFormattedTextField skipCountField;
    private JFormattedTextField maxHitsField;
    private JCheckBox searchAllVersionsCheckBox;
    private ResultTable resultsTable;
    private JLabel queryTimeLabel;

    public QueryFrame(ClientModel model) {
        super();

        this.model = model;
        createGUI();
    }

    private void createGUI() {
        setTitle(WINDOW_TITLE + " - " + model.getRepositoryName());
        setIconImages(ClientHelper.getCmisIconImages());

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setPreferredSize(new Dimension((int) (screenSize.getWidth() / 2), (int) (screenSize.getHeight() / 2)));
        setMinimumSize(new Dimension(200, 60));

        setLayout(new BoxLayout(getContentPane(), BoxLayout.PAGE_AXIS));

        // input
        JPanel inputPanel = new JPanel();
        inputPanel.setLayout(new BoxLayout(inputPanel, BoxLayout.PAGE_AXIS));

        // query text area
        queryText = new JTextArea(DEFAULT_QUERY, 5, 60);
        queryText.setLineWrap(true);
        queryText.setPreferredSize(new Dimension(Short.MAX_VALUE, queryText.getPreferredSize().height));

        // undo
        undoManager = new UndoManager();
        queryText.getDocument().addUndoableEditListener(new UndoableEditListener() {
            public void undoableEditHappened(UndoableEditEvent e) {
                undoManager.addEdit(e.getEdit());
            }
        });

        AbstractAction undoAction = ClientHelper.createAndAttachUndoAction(undoManager, queryText);
        AbstractAction redoAction = ClientHelper.createAndAttachRedoAction(undoManager, queryText);

        inputPanel.add(queryText);

        JPanel inputPanel2 = new JPanel();
        inputPanel2.setLayout(new BorderLayout());

        // buttons
        JPanel buttonPanel = new JPanel();

        skipCountField = new JFormattedTextField(new NumberFormatter());
        skipCountField.setValue(Integer.valueOf(0));
        skipCountField.setColumns(5);

        JLabel skipCountLabel = new JLabel("Skip:");
        skipCountLabel.setLabelFor(skipCountField);

        buttonPanel.add(skipCountLabel);
        buttonPanel.add(skipCountField);

        maxHitsField = new JFormattedTextField(new NumberFormatter());
        maxHitsField.setValue(Integer.valueOf(100));
        maxHitsField.setColumns(5);

        JLabel maxHitsLabel = new JLabel("Max hits:");
        maxHitsLabel.setLabelFor(maxHitsField);

        buttonPanel.add(maxHitsLabel);
        buttonPanel.add(maxHitsField);

        searchAllVersionsCheckBox = new JCheckBox("search all versions", false);
        buttonPanel.add(searchAllVersionsCheckBox);

        JButton queryButton = new JButton("Query",
                new QueryIcon(ClientHelper.BUTTON_ICON_SIZE, ClientHelper.BUTTON_ICON_SIZE));
        queryButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                (new QueryWorker(QueryFrame.this)).executeTask();
            }
        });

        buttonPanel.add(queryButton);

        inputPanel2.add(buttonPanel, BorderLayout.LINE_END);

        // snippets
        final JPopupMenu queryPopup = new JPopupMenu("Snippets");

        final JMenuItem cutItem = new JMenuItem(new DefaultEditorKit.CutAction());
        cutItem.setText("Cut");
        queryPopup.add(cutItem);

        final JMenuItem copyItem = new JMenuItem(new DefaultEditorKit.CopyAction());
        copyItem.setText("Copy");
        queryPopup.add(copyItem);

        final JMenuItem pasteItem = new JMenuItem(new DefaultEditorKit.PasteAction());
        pasteItem.setText("Paste");
        queryPopup.add(pasteItem);

        queryPopup.addSeparator();

        final JMenuItem undoItem = new JMenuItem(undoAction);
        undoItem.setText("Undo");
        queryPopup.add(undoItem);

        final JMenuItem redoItem = new JMenuItem(redoAction);
        redoItem.setText("Redo");
        queryPopup.add(redoItem);

        final JMenuItem clearItem = new JMenuItem("Clear History");
        clearItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                undoManager.discardAllEdits();
            }
        });
        queryPopup.add(clearItem);

        queryPopup.addSeparator();

        queryPopup.add(createMenuGroup("Properties", readSnippets("properties.txt")));
        queryPopup.add(createMenuGroup("Queries", readSnippets("queries.txt")));
        queryPopup.add(createMenuGroup("SELECT", readSnippets("select.txt")));
        queryPopup.add(createMenuGroup("FROM", readSnippets("from.txt")));
        queryPopup.add(createMenuGroup("WHERE", readSnippets("where.txt")));
        queryPopup.add(createMenuGroup("ORDER BY", readSnippets("orderby.txt")));

        queryText.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
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
                    if (queryText.getSelectedText() != null) {
                        cutItem.setEnabled(true);
                        copyItem.setEnabled(true);

                    } else {
                        cutItem.setEnabled(false);
                        copyItem.setEnabled(false);
                    }

                    Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    pasteItem.setEnabled(clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor));

                    undoItem.setEnabled(undoManager.canUndo());
                    redoItem.setEnabled(undoManager.canRedo());
                    clearItem.setEnabled(undoManager.canUndo() || undoManager.canRedo());

                    queryPopup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        // query time label
        queryTimeLabel = new JLabel("");
        queryTimeLabel.setHorizontalAlignment(SwingConstants.CENTER);
        inputPanel2.add(queryTimeLabel, BorderLayout.CENTER);

        inputPanel2.setMaximumSize(new Dimension(Short.MAX_VALUE, inputPanel2.getPreferredSize().height));
        inputPanel.add(inputPanel2);

        // table
        resultsTable = new ResultTable();

        final JPopupMenu popup = new JPopupMenu();
        final JMenuItem clipboardAllItem = new JMenuItem("Copy all rows to clipboard");
        popup.add(clipboardAllItem);

        clipboardAllItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ClientHelper.copyTableToClipboard(resultsTable, false);
            }
        });

        final JMenuItem clipboardSelectedItem = new JMenuItem("Copy selected rows to clipboard");
        popup.add(clipboardSelectedItem);

        clipboardSelectedItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ClientHelper.copyTableToClipboard(resultsTable, true);
            }
        });

        resultsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = resultsTable.rowAtPoint(e.getPoint());
                int column = resultsTable.columnAtPoint(e.getPoint());
                if (row > -1 && resultsTable.getColumnClass(column) == ObjectIdImpl.class) {
                    LoadObjectWorker.loadObject(QueryFrame.this.getOwner(), model,
                            ((ObjectId) resultsTable.getValueAt(row, column)).getId());
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

        resultsTable.addMouseMotionListener(new MouseMotionListener() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = resultsTable.rowAtPoint(e.getPoint());
                int column = resultsTable.columnAtPoint(e.getPoint());
                if (row > -1 && resultsTable.getColumnClass(column) == ObjectIdImpl.class) {
                    resultsTable.setCursor(HAND_CURSOR);
                } else {
                    resultsTable.setCursor(DEFAULT_CURSOR);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
            }
        });

        add(new JSplitPane(JSplitPane.VERTICAL_SPLIT, inputPanel, new JScrollPane(resultsTable)));

        getRootPane().setDefaultButton(queryButton);

        ClientHelper.installEscapeBinding(this, getRootPane(), true);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        pack();

        setLocationRelativeTo(null);
        setVisible(true);
    }

    private List<String> readSnippets(String filename) {
        InputStream stream = null;
        try {
            stream = this.getClass().getResourceAsStream("/query/" + filename);
            if (stream != null) {
                return IOUtils.readAllLinesAsList(stream);
            }
        } catch (IOException e) {
            IOUtils.closeQuietly(stream);
        }

        return Collections.emptyList();
    }

    private JMenu createMenuGroup(String name, List<String> subs) {
        JMenu result = new JMenu(name);

        PopupMenuActionListener listener = new PopupMenuActionListener(queryText);

        for (String text : subs) {
            JMenuItem textItem = new JMenuItem(text);
            textItem.addActionListener(listener);

            result.add(textItem);
        }

        return result;
    }

    private static class PopupMenuActionListener implements ActionListener {
        private final JTextArea textArea;

        public PopupMenuActionListener(JTextArea textArea) {
            this.textArea = textArea;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            textArea.insert(((JMenuItem) e.getSource()).getText(), textArea.getCaretPosition());
        }
    }

    static class ResultTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private int columnCount = 0;
        private int rowCount = 0;
        private final Map<String, Integer> columnMapping = new HashMap<String, Integer>();
        private final Map<Integer, Map<Integer, Object>> data = new HashMap<Integer, Map<Integer, Object>>();
        private final Map<Integer, Map<Integer, List<?>>> multivalue = new HashMap<Integer, Map<Integer, List<?>>>();
        private final Map<Integer, Class<?>> columnClass = new HashMap<Integer, Class<?>>();

        public ResultTableModel() {
        }

        public void setColumnCount(int columnCount) {
            this.columnCount = columnCount;
        }

        @Override
        public int getColumnCount() {
            return columnCount;
        }

        public void setRowCount(int rowCount) {
            this.rowCount = rowCount;
        }

        @Override
        public int getRowCount() {
            return rowCount;
        }

        public void setValue(final int rowIndex, final String queryName, Object value) {
            Integer col = columnMapping.get(queryName);
            if (col == null) {
                col = columnMapping.size();
                columnMapping.put(queryName, columnMapping.size());
            }

            if (value == null) {
                return;
            }

            if (value instanceof List<?>) {
                List<?> values = (List<?>) value;
                if (values.isEmpty()) {
                    return;
                }

                value = values.get(0);

                if (values.size() > 1) {
                    Map<Integer, List<?>> mvrow = multivalue.get(rowIndex);
                    if (mvrow == null) {
                        mvrow = new HashMap<Integer, List<?>>();
                        multivalue.put(rowIndex, mvrow);
                    }
                    mvrow.put(col, values);
                }
            }

            if (value instanceof GregorianCalendar) {
                value = ClientHelper.getDateString((GregorianCalendar) value);
            }

            columnClass.put(col, value.getClass());

            Map<Integer, Object> row = data.get(rowIndex);
            if (row == null) {
                row = new HashMap<Integer, Object>();
                data.put(rowIndex, row);
            }

            row.put(col, value);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Map<Integer, Object> row = data.get(rowIndex);
            if (row == null) {
                return null;
            }

            return row.get(columnIndex);
        }

        public List<?> getMultiValueAt(int rowIndex, int columnIndex) {
            Map<Integer, List<?>> row = multivalue.get(rowIndex);
            if (row == null) {
                return null;
            }

            return row.get(columnIndex);
        }

        @Override
        public String getColumnName(int column) {
            for (Map.Entry<String, Integer> e : columnMapping.entrySet()) {
                if (e.getValue().equals(column)) {
                    return e.getKey();
                }
            }

            return "?";
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            Class<?> clazz = columnClass.get(columnIndex);
            if (clazz != null) {
                return clazz;
            }

            return String.class;
        }
    }

    static class ResultTable extends JTable {

        private static final long serialVersionUID = 1L;

        public ResultTable() {
            super();

            setDefaultRenderer(ObjectIdImpl.class, new IdRenderer());
            setFillsViewportHeight(true);
            setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

            setRowHeight((int) (getFontMetrics(getFont()).getHeight() * 1.1));
        }

        @Override
        public String getToolTipText(final MouseEvent e) {
            String result = null;

            Point p = e.getPoint();
            int rowIndex = rowAtPoint(p);
            int columnIndex = convertColumnIndexToModel(columnAtPoint(p));

            final ResultTableModel model = (ResultTableModel) getModel();

            final List<?> values = model.getMultiValueAt(rowIndex, columnIndex);
            if (values != null) {
                StringBuilder sb = new StringBuilder(128);

                for (Object value : values) {
                    if (sb.length() == 0) {
                        sb.append("<html>");
                    } else {
                        sb.append("<br>");
                    }
                    if (value == null) {
                        sb.append("<i>null</i>");
                    } else {
                        ClientHelper.encodeHtml(sb, value.toString());
                    }
                }

                result = sb.toString();
            } else {
                final Object value = model.getValueAt(rowIndex, columnIndex);
                if (value != null) {
                    result = value.toString();
                }
            }

            return result;
        }

        @Override
        public Component prepareRenderer(final TableCellRenderer renderer, final int rowIndex, final int columnIndex) {
            final Component prepareRenderer = super.prepareRenderer(renderer, rowIndex, columnIndex);
            final TableColumn column = getColumnModel().getColumn(columnIndex);

            final int currentWidth = column.getPreferredWidth();
            if (currentWidth < 200) {
                int width = prepareRenderer.getPreferredSize().width;
                if (currentWidth < width) {
                    if (width < 50) {
                        width = 50;
                    } else if (width > 200) {
                        width = 200;
                    }

                    if (width != currentWidth) {
                        column.setPreferredWidth(width);
                    }
                }
            }

            return prepareRenderer;
        }
    }

    private class QueryWorker extends InfoWorkbenchWorker {

        // in
        private String queryStatement;
        private int skipCount = 0;
        private int maxHits = 1000;
        private boolean searchAllVersions = false;

        // out
        private ResultTableModel resultTableModel;
        private String queryTimeStr;

        public QueryWorker(Window parent) {
            super(parent);
        }

        @Override
        protected String getTitle() {
            return "Query";
        }

        @Override
        protected String getMessage() {
            return "Executing query...";
        }

        @Override
        public void executeTask() {
            queryStatement = queryText.getText();
            queryStatement = queryStatement.replace('\n', ' ');

            try {
                skipCountField.commitEdit();
                skipCount = ((Number) skipCountField.getValue()).intValue();
                if (skipCount < 0) {
                    skipCount = 0;
                    skipCountField.setValue(0);
                }
            } catch (Exception e) {
                showError(e);
            }

            try {
                maxHitsField.commitEdit();
                maxHits = ((Number) maxHitsField.getValue()).intValue();
                if (maxHits < 0) {
                    maxHits = 0;
                    maxHitsField.setValue(0);
                }
            } catch (Exception e) {
                showError(e);
            }

            searchAllVersions = searchAllVersionsCheckBox.isSelected();

            super.executeTask();
        }

        @Override
        protected Object doInBackground() throws Exception {
            resultTableModel = new ResultTableModel();

            long startTime = System.currentTimeMillis();

            int row = 0;

            ItemIterable<QueryResult> page = model.query(queryStatement, searchAllVersions, maxHits);
            if (skipCount > 0) {
                page = page.skipTo(skipCount);
            }
            page = page.getPage(maxHits);

            if (isCancelled()) {
                return null;
            }

            for (QueryResult qr : page) {
                if (isCancelled()) {
                    break;
                }

                resultTableModel.setColumnCount(Math.max(resultTableModel.getColumnCount(), qr.getProperties().size()));

                for (PropertyData<?> prop : qr.getProperties()) {
                    if (PropertyIds.OBJECT_ID.equals(prop.getId()) && (prop.getFirstValue() != null)) {
                        resultTableModel.setValue(row, prop.getQueryName(),
                                new ObjectIdImpl(prop.getFirstValue().toString()));
                    } else {
                        resultTableModel.setValue(row, prop.getQueryName(), prop.getValues());
                    }
                }

                row++;
            }

            if (!isCancelled()) {
                resultTableModel.setRowCount(row);

                long stopTime = System.currentTimeMillis();
                float time = (stopTime - startTime) / 1000f;
                String total = "<unknown>";
                if (page.getTotalNumItems() >= 0) {
                    total = String.valueOf(page.getTotalNumItems());
                }

                queryTimeStr = " " + row + " hits, " + total + " total (" + time + " sec)";
            }

            return null;
        }

        @Override
        protected void finializeTask() {
            if (isCancelled()) {
                queryTimeLabel.setText(" canceled");
                resultsTable.setModel(new ResultTableModel());
            } else {
                queryTimeLabel.setText(queryTimeStr);
                resultsTable.setModel(resultTableModel);
            }
        }
    }
}

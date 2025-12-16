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
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.model.ClientModelEvent;
import org.apache.chemistry.opencmis.workbench.model.FolderListener;
import org.apache.chemistry.opencmis.workbench.model.ObjectListener;
import org.apache.chemistry.opencmis.workbench.worker.LoadFolderWorker;

public class FolderPanel extends JPanel implements FolderListener, ObjectListener {

    private static final long serialVersionUID = 1L;

    private static final int HISTORY = 10;

    private final ClientModel model;

    private String parentId;

    private JButton upButton;
    private JComboBox<String> pathField;
    private JButton goButton;
    private FolderTable folderTable;

    public FolderPanel(ClientModel model) {
        super();

        this.model = model;
        model.addFolderListener(this);
        model.addObjectListener(this);
        createGUI();
    }

    @Override
    public void folderLoaded(final ClientModelEvent event) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                Folder currentFolder = event.getClientModel().getCurrentFolder();

                if (currentFolder != null) {
                    String path = currentFolder.getPath();
                    setPath(path);

                    Folder parent = null;
                    try {
                        parent = currentFolder.getFolderParent();
                    } catch (CmisPermissionDeniedException pde) {
                        // user is not allowed to the see the parent folder
                    }
                    if (parent == null) {
                        parentId = null;
                        upButton.setEnabled(false);
                    } else {
                        parentId = parent.getId();
                        upButton.setEnabled(true);
                    }
                } else {
                    setPath(null);
                    parentId = null;
                    upButton.setEnabled(false);
                }
            }
        });
    }

    @Override
    public void objectLoaded(final ClientModelEvent event) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                int selectedRow = folderTable.getSelectedRow();
                if (selectedRow > -1 && event.getClientModel().getCurrentObject() != null) {
                    if (selectedRow < folderTable.getRowCount()) {

                        String selId = folderTable.getValueAt(folderTable.getSelectedRow(), FolderTable.ID_COLUMN)
                                .toString();
                        String curId = event.getClientModel().getCurrentObject().getId();

                        if (!curId.equals(selId)) {
                            folderTable.clearSelection();
                        }
                    } else {
                        folderTable.clearSelection();
                    }
                }
            }
        });
    }

    private void createGUI() {
        setLayout(new BorderLayout());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.LINE_AXIS));
        panel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0)));

        upButton = new JButton("up");
        upButton.setEnabled(false);
        upButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                loadParentFolder();
            }
        });
        panel.add(upButton);

        pathField = new JComboBox<String>();
        pathField.setEditable(true);
        pathField.setMaximumRowCount(HISTORY);

        pathField.getEditor().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                loadFolder();
            }
        });
        panel.add(pathField);

        goButton = new JButton("go");
        goButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                loadFolder();
            }
        });
        panel.add(goButton);

        add(panel, BorderLayout.PAGE_START);

        folderTable = new FolderTable(model);
        folderTable.setFillsViewportHeight(true);
        model.addFolderListener(folderTable);

        add(new JScrollPane(folderTable), BorderLayout.CENTER);
    }

    public void clear() {
        pathField.removeAllItems();
    }

    private void setPath(String path) {
        if (path == null) {
            // no path -> object couldn't be loaded
            pathField.setSelectedItem("");
            return;
        }

        // if the new path already exists in the history, remove it
        int i = 0;
        while (i < pathField.getItemCount()) {
            if (path.equals(pathField.getItemAt(i))) {
                pathField.removeItemAt(i);
            } else {
                i++;
            }
        }

        // add new path at the top
        pathField.insertItemAt(path, 0);

        // cut history
        while (pathField.getItemCount() > HISTORY) {
            pathField.removeItemAt(HISTORY);
        }

        // select the path in combo box
        pathField.setSelectedIndex(0);
    }

    private String getPath() {
        return pathField.getEditor().getItem().toString().trim();
    }

    private void loadFolder() {
        LoadFolderWorker.loadFolder(this, model, getPath());
    }

    private void loadParentFolder() {
        LoadFolderWorker.loadFolderById(this, model, parentId);
    }
}

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
package org.apache.chemistry.opencmis.workbench.swing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.workbench.ClientHelper;
import org.apache.chemistry.opencmis.workbench.WorkbenchScale;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.worker.LoadFolderWorker;
import org.apache.chemistry.opencmis.workbench.worker.LoadObjectWorker;

public abstract class ActionPanel extends JPanel implements ActionListener {

    private static final long serialVersionUID = 1L;

    private final ClientModel model;
    private CmisObject object;

    private JPanel centerPanel;

    public ActionPanel(String title, String buttonLabel, ClientModel model) {
        super();
        this.model = model;
        createGUI(title, buttonLabel);
    }

    public ClientModel getClientModel() {
        return model;
    }

    public void setObject(CmisObject object) {
        this.object = object;
    }

    public CmisObject getObject() {
        return object;
    }

    public CmisVersion getCmisVersion() {
        try {
            return model.getRepositoryInfo().getCmisVersion();
        } catch (Exception e) {
            return CmisVersion.CMIS_1_0;
        }
    }

    private void createGUI(String title, String buttonLabel) {
        BorderLayout borderLayout = new BorderLayout();
        borderLayout.setVgap(3);
        setLayout(borderLayout);

        setBackground(Color.WHITE);
        setBorder(WorkbenchScale.scaleBorder(BorderFactory.createCompoundBorder(
                WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)),
                WorkbenchScale.scaleBorder(BorderFactory.createCompoundBorder(
                        WorkbenchScale.scaleBorder(BorderFactory.createLineBorder(Color.GRAY, 2)),
                        WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)))))));

        Font labelFont = UIManager.getFont("Label.font");
        Font boldFont = labelFont.deriveFont(Font.BOLD, labelFont.getSize2D() * 1.2f);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(boldFont);
        add(titleLabel, BorderLayout.PAGE_START);

        centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.PAGE_AXIS));
        centerPanel.setBackground(Color.WHITE);
        add(centerPanel, BorderLayout.CENTER);

        createActionComponents();

        JButton actionButton = new JButton(buttonLabel);
        actionButton.addActionListener(this);
        add(actionButton, BorderLayout.PAGE_END);

        setMaximumSize(new Dimension(Short.MAX_VALUE, getPreferredSize().height));
    }

    protected void addActionComponent(JComponent comp) {
        comp.setAlignmentX(LEFT_ALIGNMENT);
        centerPanel.add(comp);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        try {
            ((JButton) e.getSource()).requestFocusInWindow();
            doAction();
        } catch (Exception ex) {
            ClientHelper.showError(null, ex);
        }
    }

    protected void reload(final boolean reloadObject) {
        if (model.getCurrentFolder() != null) {
            LoadFolderWorker worker = new LoadFolderWorker(ActionPanel.this, model, model.getCurrentFolder().getId()) {
                @Override
                protected void done() {
                    super.done();
                    if (reloadObject) {
                        LoadObjectWorker.reloadObject(ActionPanel.this, model);
                    }
                }
            };
            worker.executeTask();
        } else {
            if (reloadObject) {
                LoadObjectWorker.reloadObject(ActionPanel.this, model);
            }
        }
    }

    protected abstract void createActionComponents();

    public abstract boolean isAllowed();

    public abstract void doAction() throws Exception;

    protected JPanel createFilenamePanel(final JTextField filenameField) {
        JPanel filePanel = new JPanel(new BorderLayout());
        filePanel.setBackground(Color.WHITE);

        filePanel.add(new JLabel("File:"), BorderLayout.LINE_START);

        filePanel.add(filenameField, BorderLayout.CENTER);

        JButton browseButton = new JButton("Browse");
        browseButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                WorkbenchFileChooser fileChooser = new WorkbenchFileChooser();
                int chooseResult = fileChooser.showDialog(filenameField, "Select");
                if (chooseResult == WorkbenchFileChooser.APPROVE_OPTION) {
                    if (fileChooser.getSelectedFile().isFile()) {
                        filenameField.setText(fileChooser.getSelectedFile().getAbsolutePath());
                    }
                }
            }
        });
        filePanel.add(browseButton, BorderLayout.LINE_END);

        return filePanel;
    }
}

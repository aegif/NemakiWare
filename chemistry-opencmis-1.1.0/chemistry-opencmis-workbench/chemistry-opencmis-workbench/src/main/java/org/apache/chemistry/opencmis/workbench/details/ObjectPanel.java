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

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNullOrEmpty;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.SecondaryType;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.workbench.ClientHelper;
import org.apache.chemistry.opencmis.workbench.ConsoleHelper;
import org.apache.chemistry.opencmis.workbench.WorkbenchScale;
import org.apache.chemistry.opencmis.workbench.checks.ObjectComplianceTestGroup;
import org.apache.chemistry.opencmis.workbench.checks.SwingReport;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.model.ClientModelEvent;
import org.apache.chemistry.opencmis.workbench.model.ObjectListener;
import org.apache.chemistry.opencmis.workbench.swing.BaseTypeLabel;
import org.apache.chemistry.opencmis.workbench.swing.InfoPanel;
import org.apache.chemistry.opencmis.workbench.worker.LoadObjectWorker;
import org.apache.chemistry.opencmis.workbench.worker.TempFileContentWorker;

import groovy.ui.Console;

public class ObjectPanel extends InfoPanel implements ObjectListener {

    private static final long serialVersionUID = 1L;

    private final Set<String> scriptExtensions;

    private JTextField nameField;
    private JTextField idField;
    private JTextField latestAccessibleStateIdField;
    private JTextField typeField;
    private BaseTypeLabel basetypeField;
    private InfoList secondaryTypesList;
    private JTextField versionLabelField;
    private JTextField pwcField;
    private JTextField mimeTypeField;
    private JTextField sizeField;
    private JTextField contentUrlField;
    private InfoList pathsList;
    private InfoList allowableActionsList;
    private JTextField aclExactField;
    private JPanel buttonPanel;
    private JButton refreshButton;
    private JButton checkButton;
    private JButton consoleButton;
    private JPanel scriptPanel;
    private JButton scriptOpenButton;
    private JButton scriptRunButton;
    private JTextArea scriptOutput;
    private JTextAreaWriter scriptOutputWriter;

    public ObjectPanel(ClientModel model) {
        super(model);

        model.addObjectListener(this);

        // get all installed script engines
        scriptExtensions = new HashSet<String>();
        ScriptEngineManager mgr = new ScriptEngineManager();
        for (ScriptEngineFactory sef : mgr.getEngineFactories()) {
            scriptExtensions.addAll(sef.getExtensions());
        }

        createGUI();
    }

    @Override
    public void objectLoaded(ClientModelEvent event) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                CmisObject object = getClientModel().getCurrentObject();

                if (object == null) {
                    nameField.setText("");
                    idField.setText("");
                    latestAccessibleStateIdField.setText("");
                    typeField.setText("");
                    basetypeField.setValue(null);
                    secondaryTypesList.removeAll();
                    versionLabelField.setText("");
                    pwcField.setText("");
                    pathsList.removeAll();
                    mimeTypeField.setText("");
                    sizeField.setText("");
                    contentUrlField.setText("");
                    allowableActionsList.removeAll();
                    aclExactField.setText("");
                    refreshButton.setEnabled(false);
                    checkButton.setEnabled(false);
                    consoleButton.setEnabled(false);
                    scriptPanel.setVisible(false);
                } else {
                    try {
                        nameField.setText(object.getName());
                        idField.setText(object.getId());

                        typeField.setText(object.getType().getId());
                        basetypeField.setValue(object.getBaseTypeId());

                        if (object.getSecondaryTypes() != null) {
                            List<String> secTypeIds = new ArrayList<String>();
                            for (SecondaryType type : object.getSecondaryTypes()) {
                                secTypeIds.add(type.getId());
                            }
                            secondaryTypesList.setList(secTypeIds);
                        } else {
                            secondaryTypesList.removeAll();
                        }

                        if (object instanceof Document) {
                            Document doc = (Document) object;

                            latestAccessibleStateIdField.setText(doc.getLatestAccessibleStateId());

                            try {
                                versionLabelField.setText(doc.getVersionLabel());
                            } catch (Exception e) {
                                versionLabelField.setText("???");
                            }

                            if (doc.isVersionSeriesCheckedOut() == null) {
                                pwcField.setText("");
                            } else if (doc.isVersionSeriesCheckedOut().booleanValue()) {
                                pwcField.setText(doc.getVersionSeriesCheckedOutId());
                            } else {
                                pwcField.setText("(not checked out)");
                            }

                            sizeField.setText(doc.getContentStreamLength() >= 0
                                    ? NumberFormat.getInstance().format(doc.getContentStreamLength()) + " bytes" : "");
                            mimeTypeField.setText(
                                    doc.getContentStreamMimeType() != null ? doc.getContentStreamMimeType() : "");
                        } else {
                            latestAccessibleStateIdField.setText("");
                            pwcField.setText("");
                            versionLabelField.setText("");
                            mimeTypeField.setText("");
                            sizeField.setText("");
                        }

                        if (object instanceof FileableCmisObject) {
                            if (object instanceof Folder) {
                                pathsList.setList(Collections.singletonList(((Folder) object).getPath()));
                            } else {
                                pathsList.setList(Collections.singletonList(""));
                                final FileableCmisObject pathObject = (FileableCmisObject) object;
                                SwingUtilities.invokeLater(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            List<String> paths = pathObject.getPaths();
                                            if (isNullOrEmpty(paths)) {
                                                pathsList.setList(Collections.singletonList("(unfiled)"));
                                            } else {
                                                pathsList.setList(paths);
                                            }
                                        } catch (Exception e) {
                                            pathsList.setList(Collections.singletonList("(???)"));
                                            // ClientHelper.showError(null, e);
                                        }
                                        ObjectPanel.this.revalidate();
                                    }
                                });
                            }
                        } else {
                            pathsList.setList(Collections.singletonList("(not filable)"));
                        }

                        String docUrl = getDocumentURL(object, getClientModel().getClientSession().getSession());
                        if (docUrl != null) {
                            contentUrlField.setText(docUrl);
                        } else {
                            contentUrlField.setText("(not available)");
                        }

                        if (object.getAllowableActions() != null) {
                            allowableActionsList.setList(object.getAllowableActions().getAllowableActions());
                        } else {
                            allowableActionsList.setList(Collections.singletonList("(missing)"));
                        }

                        if (object.getAcl() == null) {
                            aclExactField.setText("(no ACL)");
                        } else {
                            if (object.getAcl().isExact() == null) {
                                aclExactField.setText("exact flag not set");
                            } else if (object.getAcl().isExact().booleanValue()) {
                                aclExactField.setText("is exact");
                            } else {
                                aclExactField.setText("is not exact");
                            }
                        }

                        refreshButton.setEnabled(true);
                        checkButton.setEnabled(true);
                        consoleButton.setEnabled(true);

                        if (object instanceof Document) {
                            String name = object.getName().toLowerCase(Locale.ENGLISH);
                            int x = name.lastIndexOf('.');
                            if ((x > -1) && (scriptExtensions.contains(name.substring(x + 1)))) {
                                scriptPanel.setVisible(true);
                                scriptOutput.setVisible(false);
                            } else {
                                scriptPanel.setVisible(false);
                            }
                        }
                    } catch (Exception e) {
                        ClientHelper.showError(ObjectPanel.this, e);
                    }
                }

                regenerateGUI();
            }
        });
    }

    private void createGUI() {
        setupGUI();

        nameField = addLine("Name:", true);
        addSeparator();
        idField = addId("Object ID:");
        latestAccessibleStateIdField = addId("Latest State ID:");
        typeField = addLine("Type:");
        basetypeField = addBaseTypeLabel("Base Type:");
        secondaryTypesList = addComponent("Secondary Types:", new InfoList());
        addSeparator();
        pathsList = addComponent("Paths:", new InfoList());
        addSeparator();
        mimeTypeField = addLine("MIME Type:");
        sizeField = addLine("Size:");
        versionLabelField = addLine("Version Label:");
        pwcField = addId("PWC:");
        contentUrlField = addLink("Content URL:");
        addSeparator();
        allowableActionsList = addComponent("Allowable Actions:", new InfoList());
        aclExactField = addLine("ACL:");

        buttonPanel = addComponent("", new JPanel(new BorderLayout()));
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.LINE_AXIS));
        buttonPanel.setOpaque(false);

        refreshButton = new JButton("Refresh");
        refreshButton.setEnabled(false);
        buttonPanel.add(refreshButton);
        checkButton = new JButton("Check specification compliance");
        checkButton.setEnabled(false);
        buttonPanel.add(checkButton);
        consoleButton = new JButton("Open console");
        consoleButton.setEnabled(false);
        buttonPanel.add(consoleButton);

        scriptPanel = addComponent("", new JPanel(new BorderLayout()));
        scriptPanel.setOpaque(false);
        scriptPanel.setVisible(false);

        JPanel scriptButtonPanel = new JPanel();
        scriptButtonPanel.setLayout(new BoxLayout(scriptButtonPanel, BoxLayout.LINE_AXIS));
        scriptButtonPanel.setOpaque(false);
        scriptPanel.add(scriptButtonPanel, BorderLayout.PAGE_START);
        scriptOpenButton = new JButton("Open Script");
        scriptButtonPanel.add(scriptOpenButton);
        scriptRunButton = new JButton("Run Script");
        scriptButtonPanel.add(scriptRunButton);

        scriptOutput = new JTextArea(null, 1, 80);
        scriptOutput.setEditable(false);
        scriptOutput.setFont(Font.decode("Monospaced"));
        scriptOutput.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createTitledBorder("")));
        scriptOutputWriter = new JTextAreaWriter(scriptOutput);
        scriptPanel.add(scriptOutput, BorderLayout.CENTER);

        refreshButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                LoadObjectWorker.reloadObject(ObjectPanel.this, getClientModel());
            }
        });

        checkButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

                    Map<String, String> parameters = new HashMap<String, String>(
                            getClientModel().getClientSession().getSessionParameters());
                    parameters.put(SessionParameter.REPOSITORY_ID, getClientModel().getRepositoryInfo().getId());
                    String objectId = getClientModel().getCurrentObject().getId();

                    ObjectComplianceTestGroup octg = new ObjectComplianceTestGroup(parameters, objectId);
                    octg.run();

                    List<CmisTestGroup> groups = new ArrayList<CmisTestGroup>();
                    groups.add(octg);
                    SwingReport report = new SwingReport(null, 700, 500);
                    report.createReport(parameters, groups, (Writer) null);
                } catch (Exception ex) {
                    ClientHelper.showError(null, ex);
                } finally {
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            }
        });

        consoleButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

                    ConsoleHelper.openConsole(ObjectPanel.this, getClientModel(),
                            createGroovySourceCode(getClientModel().getCurrentObject()));
                } catch (Exception ex) {
                    ClientHelper.showError(null, ex);
                } finally {
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            }
        });

        scriptOpenButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                    Document doc = (Document) getClientModel().getCurrentObject();

                    String name = doc.getName().toLowerCase(Locale.ENGLISH);
                    if (name.endsWith(".groovy")) {
                        // download and execute Groovy file
                        TempFileContentWorker worker = new TempFileContentWorker(ObjectPanel.this, doc) {
                            @Override
                            protected void processTempFile(File file) {
                                Console console = ConsoleHelper.openConsole(ObjectPanel.this, getClientModel(),
                                        (String) null);
                                if (console != null) {
                                    console.loadScriptFile(file);
                                }
                            }
                        };
                        worker.executeTask();
                    } else {
                        ClientHelper.open(ObjectPanel.this, doc, null);
                    }
                } catch (Exception ex) {
                    ClientHelper.showError(null, ex);
                } finally {
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            }
        });

        scriptRunButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                final Document doc = (Document) getClientModel().getCurrentObject();
                TempFileContentWorker worker = new TempFileContentWorker(ObjectPanel.this, doc) {
                    @Override
                    protected void processTempFile(File file) {
                        String name = doc.getName().toLowerCase(Locale.ENGLISH);
                        String ext = name.substring(name.lastIndexOf('.') + 1);

                        scriptOutput.setText("");
                        scriptOutput.setVisible(true);
                        scriptOutput.invalidate();

                        ConsoleHelper.runJSR223Script(ObjectPanel.this, getClientModel(), file, ext,
                                scriptOutputWriter);
                    }
                };
                worker.executeTask();
            }
        });

        regenerateGUI();
    }

    private String getDocumentURL(final CmisObject document, final Session session) {
        if (!(document instanceof Document)) {
            return null;
        }

        return ((Document) document).getContentUrl();
    }

    private String createGroovySourceCode(CmisObject object) {
        StringBuilder sb = new StringBuilder(512);

        sb.append("import org.apache.chemistry.opencmis.commons.*\n");
        sb.append("import org.apache.chemistry.opencmis.commons.data.*\n");
        sb.append("import org.apache.chemistry.opencmis.commons.enums.*\n");
        sb.append("import org.apache.chemistry.opencmis.client.api.*\n");
        sb.append("import org.apache.chemistry.opencmis.client.util.*\n\n");

        sb.append("// ");
        sb.append(object.getName());
        sb.append('\n');

        switch (object.getBaseTypeId()) {
        case CMIS_DOCUMENT:
            sb.append("Document doc = (Document)");
            break;
        case CMIS_FOLDER:
            sb.append("Folder folder = (Folder)");
            break;
        case CMIS_POLICY:
            sb.append("Policy policy = (Policy)");
            break;
        case CMIS_RELATIONSHIP:
            sb.append("Relationship rel = (Relationship)");
            break;
        case CMIS_ITEM:
            sb.append("Item item = (Item)");
            break;
        default:
            sb.append("CmisObject obj =");
            break;
        }

        sb.append(" session.getObject(\"");
        sb.append(object.getId().replaceAll("\"", "\\\""));
        sb.append("\");\n\n");

        return sb.toString();
    }

    private static class JTextAreaWriter extends Writer {
        private final JTextArea textArea;

        public JTextAreaWriter(JTextArea textArea) {
            this.textArea = textArea;
        }

        @Override
        public void write(final char[] cbuf, final int off, final int len) throws IOException {
            final String s = new String(cbuf, off, len);
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    textArea.append(s);
                }
            });
        }

        @Override
        public void flush() throws IOException {
        }

        @Override
        public void close() throws IOException {
        }
    }
}
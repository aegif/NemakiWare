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
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;

import org.apache.chemistry.opencmis.client.api.CmisEndpointDocumentReader;
import org.apache.chemistry.opencmis.client.bindings.spi.ClientCertificateAuthenticationProvider;
import org.apache.chemistry.opencmis.client.bindings.spi.OAuthAuthenticationProvider;
import org.apache.chemistry.opencmis.client.runtime.CmisEndpointDocumentReaderImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.endpoints.CmisAuthentication;
import org.apache.chemistry.opencmis.commons.endpoints.CmisEndpointsDocument;
import org.apache.chemistry.opencmis.commons.impl.endpoints.CmisEndpointsDocumentHelper;
import org.apache.chemistry.opencmis.commons.impl.endpoints.CmisEndpointsDocumentImpl;
import org.apache.chemistry.opencmis.workbench.model.ClientSession;

public class DiscoverLoginTab extends AbstractLoginTab {

    private static final long serialVersionUID = 1L;

    private static final String[] COLUMN_NAMES = { "Preference", "CMIS", "Binding", "Authentication", "Details" };
    private static final int[] COLUMN_WIDTHS = { 30, 40, 70, 100, 440 };
    private static final int DETAILS_COLUMN = 4;

    public static final String SYSPROP_URL = ClientSession.WORKBENCH_PREFIX + "url";

    private JTextField urlField;
    private CmisAuthenticationTable authTable;

    private CmisEndpointDocumentReader reader = new CmisEndpointDocumentReaderImpl();

    public DiscoverLoginTab() {
        super();
        createGUI();
    }

    private void createGUI() {
        setLayout(new BorderLayout());
        setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5)));

        JPanel urlPanel = new JPanel(new BorderLayout());
        urlPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0)));
        urlPanel.add(new JLabel("Endpoint Document URL:"), BorderLayout.LINE_START);

        urlField = new JTextField();
        urlPanel.add(urlField, BorderLayout.CENTER);

        JButton loadButton = new JButton("Load");
        urlPanel.add(loadButton, BorderLayout.LINE_END);

        add(urlPanel, BorderLayout.PAGE_START);

        authTable = new CmisAuthenticationTable();

        add(new JScrollPane(authTable), BorderLayout.CENTER);

        ActionListener loadActionListener = new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

                    URL url = new URL(urlField.getText());

                    // read the endpoint document from URL
                    CmisEndpointsDocument doc = null;
                    try {
                        doc = reader.read(url);
                    } catch (Exception re1) {
                        // there was no endpoint document at this URL
                        // try adding "cmis-endpoints.json" to the URL
                        if (!urlField.getText().endsWith("/cmis-endpoints.json")) {
                            String newUrl = urlField.getText();
                            if (newUrl.endsWith("/")) {
                                newUrl = newUrl + "cmis-endpoints.json";
                            } else {
                                newUrl = newUrl + "/cmis-endpoints.json";
                            }

                            try {
                                doc = reader.read(new URL(newUrl));
                                urlField.setText(newUrl);
                            } catch (Exception re2) {
                                // ignore second exception
                                throw re1;
                            }
                        } else {
                            throw re1;
                        }
                    }

                    // fill the table
                    ((CmisAuthenticationModel) authTable.getModel()).setCmisEndpointDocument(doc);

                    // select first row
                    if (authTable.getModel().getRowCount() > 0) {
                        authTable.setRowSelectionInterval(0, 0);
                    }
                } catch (Exception ex) {
                    ClientHelper.showError(null, ex);
                } finally {
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            }
        };

        urlField.addActionListener(loadActionListener);
        loadButton.addActionListener(loadActionListener);
    }

    @Override
    public String getTabTitle() {
        return "Discover";
    }

    @Override
    public boolean transferSessionParametersToExpertTab() {
        return true;
    }

    @Override
    public Map<String, String> getSessionParameters() {
        int row = authTable.getSelectedRow();
        if (row < 0) {
            return Collections.emptyMap();
        }

        // compile session parameters
        CmisAuthentication auth = (CmisAuthentication) authTable.getValueAt(row,
                authTable.convertColumnIndexToView(DETAILS_COLUMN));
        Map<String, String> parameters = reader.pepareSessionParameters(auth);

        // add other required parameters
        if (CmisAuthentication.AUTH_BASIC.equals(auth.getType())
                || CmisAuthentication.AUTH_USERNAME_TOKEN.equals(auth.getType())
                || CmisAuthentication.AUTH_NTLM.equals(auth.getType())) {
            // these authentication methods need a user and password
            parameters.put(SessionParameter.USER, "");
            parameters.put(SessionParameter.PASSWORD, "");
        } else if (CmisAuthentication.AUTH_OAUTH.equals(auth.getType())) {
            // OAuth need some extra parameters
            parameters.put(SessionParameter.OAUTH_TOKEN_ENDPOINT, "");
            parameters.put(SessionParameter.OAUTH_CLIENT_ID, "");
            parameters.put(SessionParameter.OAUTH_CLIENT_SECRET, "");
            parameters.put(SessionParameter.OAUTH_CODE, "");
            parameters.put(SessionParameter.AUTHENTICATION_PROVIDER_CLASS, OAuthAuthenticationProvider.class.getName());
        } else if (CmisAuthentication.AUTH_CERT.equals(auth.getType())) {
            // client cert parameters
            parameters.put(SessionParameter.CLIENT_CERT_KEYFILE, "");
            parameters.put(SessionParameter.CLIENT_CERT_PASSPHRASE, "");
            parameters.put(SessionParameter.AUTHENTICATION_PROVIDER_CLASS,
                    ClientCertificateAuthenticationProvider.class.getName());
        } else if (!CmisAuthentication.AUTH_NONE.equals(auth.getType())
                && !parameters.containsKey(SessionParameter.AUTHENTICATION_PROVIDER_CLASS)) {
            // a custom authentication provider is required here
            parameters.put(SessionParameter.AUTHENTICATION_PROVIDER_CLASS, "");
        }

        return parameters;
    }

    static class CmisAuthenticationTable extends JTable {

        private static final long serialVersionUID = 1L;

        public CmisAuthenticationTable() {
            super();

            setModel(new CmisAuthenticationModel());
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            setAutoCreateRowSorter(true);

            setRowHeight((int) (getFontMetrics(getFont()).getHeight() * 1.1));

            setFillsViewportHeight(true);

            setDefaultRenderer(CmisAuthentication.class, new CmisAuthenticationRenderer());

            for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
                TableColumn column = getColumnModel().getColumn(i);
                column.setPreferredWidth(WorkbenchScale.scaleInt(COLUMN_WIDTHS[i]));
            }

            setPreferredScrollableViewportSize(new Dimension(Short.MAX_VALUE, getRowHeight() * 4));

            final JPopupMenu popup = new JPopupMenu();

            // copy to expert login
            JMenuItem expertLoginMenuItem = new JMenuItem("Transfer to expert login tab");
            popup.add(expertLoginMenuItem);

            expertLoginMenuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    ((LoginDialog) SwingUtilities.getRoot(CmisAuthenticationTable.this)).switchToExpertTab();
                }
            });

            popup.addSeparator();

            // copy all endpoints to clipboard
            JMenuItem allEnpointsMenuItem = new JMenuItem("Copy all endpoints to clipboard");
            popup.add(allEnpointsMenuItem);

            allEnpointsMenuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    String json = CmisEndpointsDocumentHelper
                            .write(((CmisAuthenticationModel) getModel()).getCmisEndpointsDocument());
                    copyTableToClipboard(json);
                }
            });

            // copy selected endpoint to clipboard
            JMenuItem selectedEnpointsMenuItem = new JMenuItem("Copy selected endpoint to clipboard");
            popup.add(selectedEnpointsMenuItem);

            selectedEnpointsMenuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    int row = CmisAuthenticationTable.this.getSelectedRow();
                    if (row < 0) {
                        return;
                    }

                    CmisAuthentication auth = (CmisAuthentication) CmisAuthenticationTable.this.getValueAt(row,
                            convertColumnIndexToView(DETAILS_COLUMN));
                    if (auth != null) {
                        String json = CmisEndpointsDocumentHelper.write(auth.getEndpoint());
                        copyTableToClipboard(json);
                    }
                }
            });

            // copy selected authentication to clipboard
            JMenuItem selectedAuthMenuItem = new JMenuItem("Copy selected authentication to clipboard");
            popup.add(selectedAuthMenuItem);

            selectedAuthMenuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    int row = CmisAuthenticationTable.this.getSelectedRow();
                    if (row < 0) {
                        return;
                    }

                    CmisAuthentication auth = (CmisAuthentication) CmisAuthenticationTable.this.getValueAt(row,
                            convertColumnIndexToView(DETAILS_COLUMN));
                    if (auth != null) {
                        String json = CmisEndpointsDocumentHelper.write(auth);
                        copyTableToClipboard(json);
                    }
                }
            });

            // open documentation URL
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                popup.addSeparator();

                JMenuItem docMenuItem = new JMenuItem("Open documentation URL");
                popup.add(docMenuItem);

                docMenuItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        int row = CmisAuthenticationTable.this.getSelectedRow();
                        if (row < 0) {
                            return;
                        }

                        CmisAuthentication auth = (CmisAuthentication) CmisAuthenticationTable.this.getValueAt(row,
                                convertColumnIndexToView(DETAILS_COLUMN));
                        if (auth != null && auth.getDocumentationUrl() != null) {
                            try {
                                Desktop.getDesktop().browse(new URI(auth.getDocumentationUrl()));
                            } catch (Exception ex) {
                                ClientHelper.showError(CmisAuthenticationTable.this, ex);
                            }
                        }
                    }
                });
            }

            setComponentPopupMenu(popup);
        }

        private void copyTableToClipboard(String s) {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable transferable = new StringSelection(s);
            clipboard.setContents(transferable, null);
        }
    }

    static class CmisAuthenticationModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private CmisEndpointsDocument endpointDocument;
        private List<CmisAuthentication> authentications;

        public CmisAuthenticationModel() {
            this.endpointDocument = new CmisEndpointsDocumentImpl();
            this.authentications = Collections.emptyList();
        }

        public CmisEndpointsDocument getCmisEndpointsDocument() {
            return endpointDocument;
        }

        public void setCmisEndpointDocument(CmisEndpointsDocument endpointDocument) {
            this.endpointDocument = endpointDocument;
            this.authentications = endpointDocument.getAuthenticationsSortedByPreference();
            fireTableDataChanged();
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
            return authentications.size();
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            CmisAuthentication auth = authentications.get(rowIndex);

            switch (columnIndex) {
            case 0:
                return auth.getPreference();
            case 1:
                return auth.getEndpoint().getCmisVersion() == null ? "?" : auth.getEndpoint().getCmisVersion();
            case 2:
                return auth.getEndpoint().getBinding() == null ? "?" : auth.getEndpoint().getBinding();
            case 3:
                return auth.getType() == null ? "?" : auth.getType();
            case 4:
                return auth;
            default:
                return "";
            }
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            switch (columnIndex) {
            case 0:
                return Integer.class;
            case 4:
                return CmisAuthentication.class;
            default:
                return String.class;
            }
        }
    }

    static class CmisAuthenticationRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        public CmisAuthenticationRenderer() {
            super();
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            Component comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            // make sure that the text fit into the row
            int height = (int) getPreferredSize().getHeight();
            if (height > (getFontMetrics(getFont()).getHeight() + getInsets().bottom + getInsets().top)) {
                if (table.getRowHeight(row) != height) {
                    table.setRowHeight(row, height);
                }
            }

            return comp;
        }

        @Override
        public void setValue(Object value) {
            StringBuilder text = new StringBuilder(128);

            if (value instanceof CmisAuthentication) {
                CmisAuthentication auth = (CmisAuthentication) value;

                text.append("<html>");

                if (auth.getDisplayName() != null) {
                    text.append("<b>");
                    text.append(auth.getDisplayName());
                    text.append("</b>");
                } else {
                    text.append("???");
                }

                text.append("<br>");

                if (auth.getEndpoint().getDisplayName() != null) {
                    text.append(auth.getEndpoint().getDisplayName());
                } else {
                    text.append("???");
                }

                if (auth.getEndpoint().getUrl() != null) {
                    text.append("<br>Endpoint URL: ");
                    text.append(auth.getEndpoint().getUrl());
                } else if (auth.getEndpoint().getRepositoryServiceWdsl() != null) {
                    text.append("<br>Endpoint WSDL: ");
                    text.append(auth.getEndpoint().getRepositoryServiceWdsl());
                }

                if (auth.getDocumentationUrl() != null) {
                    text.append("<br>Documentation URL: ");
                    text.append(auth.getDocumentationUrl());
                }
            }

            setText(text.toString());
        }
    }
}

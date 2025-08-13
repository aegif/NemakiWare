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
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.TransferHandler;
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import javax.swing.undo.UndoManager;

import org.apache.chemistry.opencmis.client.SessionParameterMap;
import org.apache.chemistry.opencmis.client.bindings.spi.ClientCertificateAuthenticationProvider;
import org.apache.chemistry.opencmis.client.bindings.spi.OAuthAuthenticationProvider;
import org.apache.chemistry.opencmis.client.bindings.spi.http.ApacheClientHttpInvoker;
import org.apache.chemistry.opencmis.client.bindings.spi.http.DefaultHttpInvoker;
import org.apache.chemistry.opencmis.client.bindings.spi.http.OkHttpHttpInvoker;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.workbench.ClientHelper.FileEntry;
import org.apache.chemistry.opencmis.workbench.model.ClientSession;

public class ExpertLoginTab extends AbstractLoginTab {

    private static final long serialVersionUID = 1L;

    public static final String SYSPROP_CONFIGS = ClientSession.WORKBENCH_PREFIX + "configs";

    private static final String CONFIGS_FOLDER = "/configs/";
    private static final String CONFIGS_LIBRARY = "config-library.properties";

    private JComboBox<FileEntry> configs;
    private JTextArea sessionParameterTextArea;
    private UndoManager undoManager;
    private List<FileEntry> sessionConfigurations;

    public ExpertLoginTab() {
        super();
        createGUI();
    }

    private void createGUI() {
        setLayout(new BorderLayout());
        setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5)));

        URI propFile = null;

        String externalConfigs = System.getProperty(SYSPROP_CONFIGS);
        if (externalConfigs == null) {
            propFile = ClientHelper.getClasspathURI(CONFIGS_FOLDER + CONFIGS_LIBRARY);
        } else {
            propFile = (new File(externalConfigs)).toURI();
        }

        sessionConfigurations = ClientHelper.readFileProperties(propFile);

        configs = new JComboBox<FileEntry>();
        configs.setMaximumRowCount(20);

        configs.addItem(new FileEntry("", null));
        if (sessionConfigurations != null) {
            for (FileEntry fe : sessionConfigurations) {
                configs.addItem(fe);
            }
        }

        configs.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                if (e.getStateChange() == ItemEvent.SELECTED) {
                    FileEntry fe = (FileEntry) e.getItem();

                    sessionParameterTextArea.setText(ClientHelper.readFileAndRemoveHeader(fe.getFile()));
                    sessionParameterTextArea.setCaretPosition(0);
                }
            }
        });

        add(configs, BorderLayout.PAGE_START);

        sessionParameterTextArea = new JTextArea();
        sessionParameterTextArea
                .setFont(new Font(Font.MONOSPACED, Font.PLAIN, sessionParameterTextArea.getFont().getSize()));
        add(new JScrollPane(sessionParameterTextArea), BorderLayout.CENTER);

        // undo
        undoManager = new UndoManager();
        sessionParameterTextArea.getDocument().addUndoableEditListener(new UndoableEditListener() {
            public void undoableEditHappened(UndoableEditEvent e) {
                undoManager.addEdit(e.getEdit());
            }
        });

        AbstractAction undoAction = ClientHelper.createAndAttachUndoAction(undoManager, sessionParameterTextArea);
        AbstractAction redoAction = ClientHelper.createAndAttachRedoAction(undoManager, sessionParameterTextArea);

        // drag and drop support
        sessionParameterTextArea.setTransferHandler(new TextAreaTransferHandler());
        sessionParameterTextArea.setDragEnabled(true);
        sessionParameterTextArea.setDropMode(DropMode.INSERT);

        // context menu
        final JPopupMenu popup = new JPopupMenu("Session Parameters");

        final JMenuItem cutItem = new JMenuItem(new DefaultEditorKit.CutAction());
        cutItem.setText("Cut");
        popup.add(cutItem);

        final JMenuItem copyItem = new JMenuItem(new DefaultEditorKit.CopyAction());
        copyItem.setText("Copy");
        popup.add(copyItem);

        final JMenuItem pasteItem = new JMenuItem(new DefaultEditorKit.PasteAction());
        pasteItem.setText("Paste");
        popup.add(pasteItem);

        popup.addSeparator();

        final JMenuItem undoItem = new JMenuItem(undoAction);
        undoItem.setText("Undo");
        popup.add(undoItem);

        final JMenuItem redoItem = new JMenuItem(redoAction);
        redoItem.setText("Redo");
        popup.add(redoItem);

        final JMenuItem clearItem = new JMenuItem("Clear History");
        clearItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                undoManager.discardAllEdits();
            }
        });
        popup.add(clearItem);

        popup.addSeparator();

        popup.add(createMenuGroup("Binding", SessionParameter.BINDING_TYPE, SessionParameter.ATOMPUB_URL,
                SessionParameter.BROWSER_URL, SessionParameter.BROWSER_SUCCINCT,
                SessionParameter.BROWSER_DATETIME_FORMAT));

        popup.add(createMenuGroup("Authentictaion", SessionParameter.USER, SessionParameter.PASSWORD,
                SessionParameter.AUTH_HTTP_BASIC, SessionParameter.AUTH_OAUTH_BEARER,
                SessionParameter.AUTH_SOAP_USERNAMETOKEN, SessionParameter.AUTHENTICATION_PROVIDER_CLASS));

        popup.add(createMenuGroup("Connection", SessionParameter.COMPRESSION, SessionParameter.CLIENT_COMPRESSION,
                SessionParameter.COOKIES, SessionParameter.HEADER, SessionParameter.CSRF_HEADER,
                SessionParameter.USER_AGENT, SessionParameter.PROXY_USER, SessionParameter.PROXY_PASSWORD,
                SessionParameter.CONNECT_TIMEOUT, SessionParameter.READ_TIMEOUT));

        popup.add(createMenuGroup("OAuth",
                SessionParameter.AUTHENTICATION_PROVIDER_CLASS + "=" + OAuthAuthenticationProvider.class.getName(),
                SessionParameter.OAUTH_CLIENT_ID, SessionParameter.OAUTH_CLIENT_SECRET, SessionParameter.OAUTH_CODE,
                SessionParameter.OAUTH_TOKEN_ENDPOINT, SessionParameter.OAUTH_REDIRECT_URI));

        popup.add(createMenuGroup("Client Certificate",
                SessionParameter.AUTHENTICATION_PROVIDER_CLASS + "="
                        + ClientCertificateAuthenticationProvider.class.getName(),
                SessionParameter.CLIENT_CERT_KEYFILE, SessionParameter.CLIENT_CERT_PASSPHRASE));

        popup.add(createMenuGroup("HTTP Invoker",
                SessionParameter.HTTP_INVOKER_CLASS + "=" + DefaultHttpInvoker.class.getName(),
                SessionParameter.HTTP_INVOKER_CLASS + "=" + ApacheClientHttpInvoker.class.getName(),
                SessionParameter.HTTP_INVOKER_CLASS + "=" + OkHttpHttpInvoker.class.getName()));

        sessionParameterTextArea.addMouseListener(new MouseAdapter() {
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
                    if (sessionParameterTextArea.getSelectedText() != null) {
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

                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });
    }

    private JMenu createMenuGroup(String name, String... subs) {
        JMenu result = new JMenu(name);

        PopupMenuActionListener listener = new PopupMenuActionListener(sessionParameterTextArea);

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

    public void setSessionParameters(Map<String, String> parameters) {
        configs.setSelectedIndex(0);

        StringBuilder sb = new StringBuilder(128);
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            sb.append(parameter.getKey());
            sb.append('=');
            sb.append(parameter.getValue());
            sb.append('\n');
        }

        sessionParameterTextArea.setText(sb.toString());
        sessionParameterTextArea.setCaretPosition(0);
    }

    @Override
    public String getTabTitle() {
        return "Expert";
    }

    @Override
    public Map<String, String> getSessionParameters() {
        SessionParameterMap result = new SessionParameterMap();
        result.parse(sessionParameterTextArea.getText());

        return result;
    }

    @Override
    public boolean transferSessionParametersToExpertTab() {
        return false;
    }

    private class TextAreaTransferHandler extends TransferHandler {

        private static final long serialVersionUID = 1L;

        public TextAreaTransferHandler() {
            super();
        }

        @Override
        public boolean canImport(TransferSupport support) {
            // we support files and strings
            if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                    && !support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return super.canImport(support);
            }

            return true;
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }

            if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                // we have a file
                File file = null;
                try {
                    List<File> fileList = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);

                    // one file only
                    if (fileList == null || fileList.size() != 1 || fileList.get(0) == null
                            || !fileList.get(0).isFile()) {
                        return false;
                    }

                    file = fileList.get(0);
                } catch (Exception ex) {
                    ClientHelper.showError(null, ex);
                    return false;
                }

                // read the first 100 lines
                InputStream stream = null;
                try {
                    stream = new BufferedInputStream(new FileInputStream(file));
                    sessionParameterTextArea.setText(IOUtils.readAllLines(stream, 100));
                } catch (IOException e) {
                    ClientHelper.showError(ExpertLoginTab.this, e);
                } finally {
                    IOUtils.closeQuietly(stream);
                }
            } else if (support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                // we have string
                try {
                    String s = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);

                    if (support.isDrop()) {
                        int index = ((JTextComponent.DropLocation) support.getDropLocation()).getIndex();
                        sessionParameterTextArea.insert(s, index);
                    } else {
                        int start = sessionParameterTextArea.getSelectionStart();
                        int end = sessionParameterTextArea.getSelectionEnd();

                        if (start == end) {
                            sessionParameterTextArea.insert(s, sessionParameterTextArea.getCaretPosition());
                        } else {
                            sessionParameterTextArea.replaceRange(s, start, end);
                        }
                    }
                } catch (Exception e) {
                    ClientHelper.showError(ExpertLoginTab.this, e);
                }
            } else {
                return false;
            }

            return true;
        }

        @Override
        public int getSourceActions(JComponent c) {
            return COPY_OR_MOVE;
        }

        @Override
        protected void exportDone(JComponent c, Transferable data, int action) {
            if ((action & MOVE) > 0) {
                int start = sessionParameterTextArea.getSelectionStart();
                int end = sessionParameterTextArea.getSelectionEnd();
                sessionParameterTextArea.replaceRange("", start, end);
            }
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            return new Transferable() {
                @Override
                public boolean isDataFlavorSupported(DataFlavor flavor) {
                    return flavor == DataFlavor.stringFlavor;
                }

                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[] { DataFlavor.stringFlavor };
                }

                @Override
                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
                    if (flavor != DataFlavor.stringFlavor) {
                        throw new UnsupportedFlavorException(flavor);
                    }

                    String s = sessionParameterTextArea.getSelectedText();
                    if (s == null) {
                        s = sessionParameterTextArea.getText();
                    }

                    return s;
                }
            };
        }
    }
}

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

import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Properties;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.DefaultEditorKit;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.workbench.swing.WorkbenchFileChooser;
import org.apache.chemistry.opencmis.workbench.worker.OpenContentWorker;
import org.apache.chemistry.opencmis.workbench.worker.StoreWorker;
import org.apache.chemistry.opencmis.workbench.worker.TempFileContentWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClientHelper {

    public static final Color LINK_COLOR = new Color(105, 29, 21);
    public static final Color LINK_SELECTED_COLOR = new Color(255, 255, 255);

    public static final int TOOLBAR_ICON_SIZE = 20;
    public static final int BUTTON_ICON_SIZE = 11;
    public static final int OBJECT_ICON_SIZE = 16;
    public static final int ICON_BUTTON_ICON_SIZE = 16;

    public static final String UNDO_ACTION_KEY = "Undo";
    public static final String REDO_ACTION_KEY = "Redo";

    private static final Logger LOG = LoggerFactory.getLogger(ClientHelper.class);

    private static final ImageIcon CMIS_ICON = getIcon("icon256.png");
    private static final List<BufferedImage> CMIS_ICON_LIST = new ArrayList<BufferedImage>();

    static {
        try {
            CMIS_ICON_LIST.add(ImageIO.read(ClientHelper.class.getResource("/images/icon256.png")));
            CMIS_ICON_LIST.add(ImageIO.read(ClientHelper.class.getResource("/images/icon128.png")));
            CMIS_ICON_LIST.add(ImageIO.read(ClientHelper.class.getResource("/images/icon64.png")));
            CMIS_ICON_LIST.add(ImageIO.read(ClientHelper.class.getResource("/images/icon48.png")));
            CMIS_ICON_LIST.add(ImageIO.read(ClientHelper.class.getResource("/images/icon32.png")));
            CMIS_ICON_LIST.add(ImageIO.read(ClientHelper.class.getResource("/images/icon16.png")));
        } catch (Exception e) {
            LOG.error("Icons cannot be loaded!", e);
        }
    }

    private ClientHelper() {
    }

    public static void logError(Throwable t) {
        if (LOG.isErrorEnabled()) {
            LOG.error(t.getClass().getSimpleName() + ": " + t.getMessage(), t);

            if (t instanceof CmisBaseException) {
                CmisBaseException cex = (CmisBaseException) t;

                if (cex.getCode() != null) {
                    LOG.error("Error code: " + cex.getCode());
                }

                if (cex.getErrorContent() != null) {
                    LOG.error("Error content: " + cex.getErrorContent());
                }

                if (LOG.isDebugEnabled() && cex.getCause() != null) {
                    LOG.debug("Cause: " + cex.getCause().toString(), cex.getCause());
                }
            }
        }
    }

    public static void showError(Component parent, Throwable t) {
        logError(t);

        if (parent == null) {
            new ExceptionDialog((Frame) null, t);
        } else {
            Window window = (Window) SwingUtilities.getRoot(parent);
            if (window instanceof Frame) {
                new ExceptionDialog((Frame) window, t);
            } else if (window instanceof Dialog) {
                new ExceptionDialog((Dialog) window, t);
            } else {
                new ExceptionDialog((Frame) null, t);
            }
        }
    }

    public static boolean isMacOSX() {
        String osname = System.getProperty("os.name");
        return osname == null ? false : osname.startsWith("Mac OS X");
    }

    public static void installKeyBindings() {
        if (isMacOSX()) {
            final KeyStroke copyKeyStroke = KeyStroke.getKeyStroke("meta pressed C");
            final KeyStroke pasteKeyStroke = KeyStroke.getKeyStroke("meta pressed V");
            final KeyStroke cutKeyStroke = KeyStroke.getKeyStroke("meta pressed X");
            final KeyStroke allKeyStroke = KeyStroke.getKeyStroke("meta pressed A");

            InputMap textFieldMap = (InputMap) UIManager.get("TextField.focusInputMap");
            textFieldMap.put(copyKeyStroke, DefaultEditorKit.copyAction);
            textFieldMap.put(pasteKeyStroke, DefaultEditorKit.pasteAction);
            textFieldMap.put(cutKeyStroke, DefaultEditorKit.cutAction);
            textFieldMap.put(allKeyStroke, DefaultEditorKit.selectAllAction);

            InputMap formattedTextFieldMap = (InputMap) UIManager.get("FormattedTextField.focusInputMap");
            formattedTextFieldMap.put(copyKeyStroke, DefaultEditorKit.copyAction);
            formattedTextFieldMap.put(pasteKeyStroke, DefaultEditorKit.pasteAction);
            formattedTextFieldMap.put(cutKeyStroke, DefaultEditorKit.cutAction);
            formattedTextFieldMap.put(allKeyStroke, DefaultEditorKit.selectAllAction);

            InputMap textAreaMap = (InputMap) UIManager.get("TextArea.focusInputMap");
            textAreaMap.put(copyKeyStroke, DefaultEditorKit.copyAction);
            textAreaMap.put(pasteKeyStroke, DefaultEditorKit.pasteAction);
            textAreaMap.put(cutKeyStroke, DefaultEditorKit.cutAction);
            textAreaMap.put(allKeyStroke, DefaultEditorKit.selectAllAction);

            InputMap editorPaneMap = (InputMap) UIManager.get("EditorPane.focusInputMap");
            editorPaneMap.put(copyKeyStroke, DefaultEditorKit.copyAction);
            editorPaneMap.put(pasteKeyStroke, DefaultEditorKit.pasteAction);
            editorPaneMap.put(cutKeyStroke, DefaultEditorKit.cutAction);
            editorPaneMap.put(allKeyStroke, DefaultEditorKit.selectAllAction);

            InputMap passwordFieldMap = (InputMap) UIManager.get("PasswordField.focusInputMap");
            passwordFieldMap.put(pasteKeyStroke, DefaultEditorKit.pasteAction);
        }
    }

    public static void installEscapeBinding(final Window window, final JRootPane rootPane, final boolean dispose) {
        final KeyStroke stroke = KeyStroke.getKeyStroke("ESCAPE");
        final InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        inputMap.put(stroke, "ESCAPE");
        rootPane.getActionMap().put("ESCAPE", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                if (dispose) {
                    window.dispose();
                } else {
                    window.setVisible(false);
                }
            }
        });
    }

    public static AbstractAction createAndAttachUndoAction(final UndoManager undoManager, JComponent component) {
        AbstractAction undoAction = new AbstractAction(UNDO_ACTION_KEY) {
            private static final long serialVersionUID = 1L;

            public void actionPerformed(ActionEvent evt) {
                try {
                    if (undoManager.canUndo()) {
                        undoManager.undo();
                    }
                } catch (CannotUndoException e) {
                }
            }
        };

        component.getActionMap().put(UNDO_ACTION_KEY, undoAction);

        KeyStroke undoKey = isMacOSX() ? KeyStroke.getKeyStroke("meta pressed Z")
                : KeyStroke.getKeyStroke("control pressed Z");
        component.getInputMap().put(undoKey, UNDO_ACTION_KEY);

        return undoAction;
    }

    public static AbstractAction createAndAttachRedoAction(final UndoManager undoManager, JComponent component) {
        AbstractAction redoAction = new AbstractAction(REDO_ACTION_KEY) {
            private static final long serialVersionUID = 1L;

            public void actionPerformed(ActionEvent evt) {
                try {
                    if (undoManager.canRedo()) {
                        undoManager.redo();
                    }
                } catch (CannotUndoException e) {
                }
            }
        };

        component.getActionMap().put(REDO_ACTION_KEY, redoAction);

        KeyStroke redoKey = isMacOSX() ? KeyStroke.getKeyStroke("meta shift pressed Z")
                : KeyStroke.getKeyStroke("control shift pressed Z");
        component.getInputMap().put(redoKey, REDO_ACTION_KEY);

        return redoAction;
    }

    public static ImageIcon getIcon(String name) {
        URL imageURL = ClientHelper.class.getResource("/images/" + name);
        if (imageURL != null) {
            return WorkbenchScale.scaleIcon(new ImageIcon(imageURL));
        }

        return null;
    }

    public static ImageIcon getCmisIconImage() {
        return CMIS_ICON;
    }

    public static List<? extends Image> getCmisIconImages() {
        return CMIS_ICON_LIST;
    }

    public static String getDateString(GregorianCalendar cal) {
        if (cal == null) {
            return "";
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss ZZZ");
        sdf.setTimeZone(cal.getTimeZone());

        return sdf.format(cal.getTime());
    }

    public static void download(Component component, CmisObject object, String streamId) {
        OpenContentWorker worker = new OpenContentWorker(null, object, streamId) {
            @Override
            protected void processStream(Component comp, ContentStream contentStream, String filename) {
                WorkbenchFileChooser fileChooser = new WorkbenchFileChooser();
                fileChooser.setSelectedFile(new File(filename));

                int chooseResult = fileChooser.showDialog(comp, "Download");
                if (chooseResult == WorkbenchFileChooser.APPROVE_OPTION) {
                    (new StoreWorker(contentStream, fileChooser.getSelectedFile(), filename)).executeTask();
                } else {
                    IOUtils.closeQuietly(contentStream);
                }
            }
        };
        worker.executeTask();
    }

    public static void open(Component component, CmisObject object, String streamId) {
        if (!Desktop.isDesktopSupported()) {
            download(component, object, streamId);
            return;
        }

        final Desktop desktop = Desktop.getDesktop();

        if (!desktop.isSupported(Desktop.Action.OPEN)) {
            download(component, object, streamId);
            return;
        }

        TempFileContentWorker worker = new TempFileContentWorker(component, object, streamId) {
            @Override
            protected void processTempFile(File file) {
                try {
                    desktop.open(file);
                } catch (Exception e) {
                    if (e instanceof IOException) {
                        copy(file);
                    } else {
                        showError(e);
                    }
                }
            }

            private void copy(File file) {
                WorkbenchFileChooser fileChooser = new WorkbenchFileChooser();
                fileChooser.setSelectedFile(new File(file.getName()));

                int chooseResult = fileChooser.showDialog(getComponent(), "Download");
                if (chooseResult == WorkbenchFileChooser.APPROVE_OPTION
                        && !file.equals(fileChooser.getSelectedFile())) {
                    try {
                        InputStream in = null;
                        OutputStream out = null;
                        try {
                            in = new FileInputStream(file);
                            out = new FileOutputStream(fileChooser.getSelectedFile());
                            IOUtils.copy(in, out, 64 * 1024);
                        } finally {
                            IOUtils.closeQuietly(in);
                            IOUtils.closeQuietly(out);
                        }
                    } catch (Exception e) {
                        showError(e);
                    }
                }
            }
        };
        worker.executeTask();
    }

    public static File createTempFile(String filename) {
        String tempDir = System.getProperty("java.io.tmpdir");
        File clientTempDir = new File(tempDir, "cmisworkbench");
        if (!clientTempDir.exists() && !clientTempDir.mkdirs()) {
            throw new CmisRuntimeException("Could not create directory for temp file!");
        }
        clientTempDir.deleteOnExit();

        File tempFile = new File(clientTempDir, filename);
        tempFile.deleteOnExit();

        return tempFile;
    }

    public static void copyTableToClipboard(JTable table, boolean onlySelected) {
        final String newline = System.getProperty("line.separator");

        final StringBuilder sb = new StringBuilder(1024);
        final int rows = table.getModel().getRowCount();
        final int cols = table.getModel().getColumnCount();

        for (int col = 0; col < cols; col++) {
            if (col > 0) {
                sb.append(',');
            }

            sb.append(formatCSVValue(table.getModel().getColumnName(col)));
        }

        sb.append(newline);

        int[] seletedRows = table.getSelectedRows();
        Arrays.sort(seletedRows);

        for (int row = 0; row < rows; row++) {

            if (onlySelected) {
                if (Arrays.binarySearch(seletedRows, row) < 0) {
                    continue;
                }
            }

            for (int col = 0; col < cols; col++) {
                if (col > 0) {
                    sb.append(',');
                }

                Object value = table.getModel().getValueAt(row, col);
                sb.append(formatCSVValue(value));
            }
            sb.append(newline);
        }

        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable transferable = new StringSelection(sb.toString());
        clipboard.setContents(transferable, null);
    }

    public static String encodeHtml(StringBuilder sb, String s) {
        if (s == null) {
            return "";
        }

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else if (c == '"') {
                sb.append("&quot;");
            } else if (c == '\'') {
                sb.append("&#39;");
            } else if (c > 127) {
                sb.append("&#" + (int) c + ";");
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    private static String formatCSVValue(Object value) {
        if (value == null) {
            return "";
        } else if (value instanceof GregorianCalendar) {
            return getDateString((GregorianCalendar) value);
        } else if (value instanceof String) {
            String s = value.toString();

            StringBuilder sb = new StringBuilder(s.length() + 16);
            sb.append('\"');

            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                sb.append(c);
                if (c == '\"') {
                    sb.append('\"');
                }
            }

            sb.append('\"');

            return sb.toString();
        } else if (value instanceof Collection<?>) {
            StringBuilder sb = new StringBuilder(((Collection<?>) value).size() * 16 + 16);
            sb.append('[');

            for (Object v : (Collection<?>) value) {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                sb.append(formatCSVValue(v));
            }

            sb.append(']');

            return sb.toString();
        } else if (value instanceof ObjectId) {
            return formatCSVValue(((ObjectId) value).getId());
        } else if (value instanceof Icon) {
            return "<icon>";
        }

        return value.toString();
    }

    public static URI getClasspathURI(String path) {
        try {
            return ClientHelper.class.getResource(path).toURI();
        } catch (URISyntaxException e) {
            // not very likely
            LOG.error(e.getMessage(), e);
            return null;
        }
    }

    public static String readFileAndRemoveHeader(final URI file) {
        if (file == null) {
            return "";
        }

        final InputStream stream;
        try {
            stream = file.toURL().openStream();
        } catch (Exception e) {
            return "";
        }

        final String result = readStreamAndRemoveHeader(stream);

        IOUtils.closeQuietly(stream);

        return result;
    }

    public static String readStreamAndRemoveHeader(final InputStream stream) {
        if (stream == null) {
            return "";
        }

        try {
            return IOUtils.readAllLinesAndRemoveHeader(stream, 10000);
        } catch (IOException e1) {
            return "";
        }
    }

    public static List<FileEntry> readFileProperties(URI propertiesFile) {

        final InputStream stream;
        try {
            stream = propertiesFile.toURL().openStream();
            if (stream == null) {
                return null;
            }
        } catch (Exception e) {
            LOG.error("Cannot open library file: {}", propertiesFile, e);
            return null;
        }

        String classpathParent = null;
        if ("classpath".equalsIgnoreCase(propertiesFile.getScheme())) {
            String path = propertiesFile.getSchemeSpecificPart();
            int x = path.lastIndexOf('/');
            if (x > -1) {
                classpathParent = path.substring(0, x);
            }
        }

        if ("jar".equalsIgnoreCase(propertiesFile.getScheme())) {
            String path = propertiesFile.getSchemeSpecificPart();
            int x = path.lastIndexOf('/');
            if (x > -1) {
                path = path.substring(0, x);
                x = path.indexOf("!/");
                if (x > -1) {
                    classpathParent = path.substring(x + 1);
                }
            }
        }

        String fileParent = null;
        if ("file".equalsIgnoreCase(propertiesFile.getScheme())) {
            fileParent = (new File(propertiesFile)).getParent();
        }

        try {
            Properties properties = new Properties();
            properties.load(stream);
            stream.close();

            final List<FileEntry> result = new ArrayList<FileEntry>();
            for (String file : properties.stringPropertyNames()) {

                try {
                    URI uri = null;

                    if (classpathParent != null) {
                        URL url = ClientHelper.class.getResource(classpathParent + "/" + file);
                        if (url != null) {
                            uri = url.toURI();
                        }
                    }

                    if (fileParent != null) {
                        uri = (new File(fileParent, file)).toURI();
                    }

                    if (uri != null) {
                        result.add(new FileEntry(properties.getProperty(file), uri));
                    } else {
                        LOG.error("Cannot find library entry: {}", file);
                    }
                } catch (URISyntaxException e) {
                    // ignore entry
                }
            }
            Collections.sort(result);

            return result;
        } catch (IOException e) {
            LOG.error("Cannot read library file: {}", propertiesFile);
            return null;
        } finally {
            IOUtils.closeQuietly(stream);
        }
    }

    public static class FileEntry implements Comparable<FileEntry> {
        private final String name;
        private final URI file;

        public FileEntry(String name, URI file) {
            this.name = name;
            this.file = file;
        }

        public String getName() {
            return name;
        }

        public URI getFile() {
            return file;
        }

        @Override
        public String toString() {
            return name;
        }

        @Override
        public int compareTo(FileEntry o) {
            return name.compareToIgnoreCase(o.getName());
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof FileEntry)) {
                return false;
            }

            return name.equals(((FileEntry) obj).getName());
        }
    }
}

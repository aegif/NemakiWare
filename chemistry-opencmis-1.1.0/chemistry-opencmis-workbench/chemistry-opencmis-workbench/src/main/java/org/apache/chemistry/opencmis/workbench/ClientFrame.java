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
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.prefs.Preferences;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;

import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.workbench.ClientHelper.FileEntry;
import org.apache.chemistry.opencmis.workbench.details.DetailsTabs;
import org.apache.chemistry.opencmis.workbench.icons.ChangeLogIcon;
import org.apache.chemistry.opencmis.workbench.icons.ConnectIcon;
import org.apache.chemistry.opencmis.workbench.icons.ConsoleIcon;
import org.apache.chemistry.opencmis.workbench.icons.CreateObjectIcon;
import org.apache.chemistry.opencmis.workbench.icons.InfoIcon;
import org.apache.chemistry.opencmis.workbench.icons.LogIcon;
import org.apache.chemistry.opencmis.workbench.icons.QueryIcon;
import org.apache.chemistry.opencmis.workbench.icons.RepositoryInfoIcon;
import org.apache.chemistry.opencmis.workbench.icons.TckIcon;
import org.apache.chemistry.opencmis.workbench.icons.TypesIcon;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.model.ClientSession;
import org.apache.chemistry.opencmis.workbench.types.TypesFrame;
import org.apache.chemistry.opencmis.workbench.worker.LoadFolderWorker;
import org.apache.chemistry.opencmis.workbench.worker.LoadObjectWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientFrame extends JFrame implements WindowListener {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(ClientFrame.class);

    private static final String WINDOW_TITLE = "CMIS Workbench";

    private static final int BUTTON_CONNECT = 0;
    private static final int BUTTON_REPOSITORY_INFO = 1;
    private static final int BUTTON_TYPES = 2;
    private static final int BUTTON_QUERY = 3;
    private static final int BUTTON_CHANGELOG = 4;
    private static final int BUTTON_CONSOLE = 5;
    private static final int BUTTON_TCK = 6;
    private static final int BUTTON_CREATE = 7;
    private static final int BUTTON_LOG = 8;
    private static final int BUTTON_INFO = 9;

    private static final String PREFS_X = "x";
    private static final String PREFS_Y = "y";
    private static final String PREFS_WIDTH = "width";
    private static final String PREFS_HEIGHT = "height";
    private static final String PREFS_DIV = "div";

    private final Preferences prefs = Preferences.userNodeForPackage(this.getClass());

    private LoginDialog loginDialog;
    private LogFrame logFrame;
    private InfoDialog infoDialog;

    private JToolBar toolBar;
    private JButton[] toolbarButton;
    private JPopupMenu toolbarConsolePopup;
    private JPopupMenu toolbarCreatePopup;
    private JMenuItem documentMenuItem;
    private JMenuItem itemMenuItem;
    private JMenuItem folderMenuItem;
    private JMenuItem relationshipMenuItem;
    private JMenuItem policyMenuItem;

    private JSplitPane split;
    private FolderPanel folderPanel;
    private DetailsTabs detailsTabs;

    private final ClientModel model;

    public ClientFrame() {
        super();

        model = new ClientModel();
        createGUI();
        showLoginForm();
    }

    private void createGUI() {
        setTitle(WINDOW_TITLE);

        setIconImages(ClientHelper.getCmisIconImages());

        // Mac OS X goodies
        if (ClientHelper.isMacOSX()) {
            try {
                Class<?> macAppClass = Class.forName("com.apple.eawt.Application");
                Method macAppGetApp = macAppClass.getMethod("getApplication", (Class<?>[]) null);
                Object macApp = macAppGetApp.invoke(null, (Object[]) null);

                ImageIcon icon = ClientHelper.getCmisIconImage();
                if (icon != null) {
                    try {
                        macAppClass.getMethod("setDockIconImage", new Class<?>[] { Image.class }).invoke(macApp,
                                new Object[] { icon.getImage() });
                    } catch (Exception e) {
                        LOG.debug("Could not set dock icon!", e);
                    }
                }

                try {
                    Class<?> fullscreenClass = Class.forName("com.apple.eawt.FullScreenUtilities");
                    fullscreenClass.getMethod("setWindowCanFullScreen", new Class<?>[] { Window.class, Boolean.TYPE })
                            .invoke(fullscreenClass, this, true);
                } catch (Exception e) {
                    LOG.debug("Could not add fullscreen button!", e);
                }
            } catch (Exception e) {
                LOG.debug("Could not get com.apple.eawt.Application object!", e);
            }
        }

        setLayout(new BorderLayout());

        final ClientFrame thisFrame = this;
        loginDialog = new LoginDialog(this);
        logFrame = new LogFrame();
        infoDialog = new InfoDialog(this);

        Container pane = getContentPane();

        toolBar = new JToolBar("CMIS Toolbar", SwingConstants.HORIZONTAL);

        toolbarButton = new JButton[10];

        toolbarButton[BUTTON_CONNECT] = new JButton("Connection",
                new ConnectIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE));
        toolbarButton[BUTTON_CONNECT].setDisabledIcon(
                new ConnectIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE, false));
        toolbarButton[BUTTON_CONNECT].addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showLoginForm();
            }
        });

        toolBar.add(toolbarButton[BUTTON_CONNECT]);

        toolBar.addSeparator();

        toolbarButton[BUTTON_REPOSITORY_INFO] = new JButton("Repository Info",
                new RepositoryInfoIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE));
        toolbarButton[BUTTON_REPOSITORY_INFO].setDisabledIcon(
                new RepositoryInfoIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE, false));
        toolbarButton[BUTTON_REPOSITORY_INFO].setEnabled(false);
        toolbarButton[BUTTON_REPOSITORY_INFO].addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                new RepositoryInfoFrame(model);
            }
        });

        toolBar.add(toolbarButton[BUTTON_REPOSITORY_INFO]);

        toolbarButton[BUTTON_TYPES] = new JButton("Types",
                new TypesIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE));
        toolbarButton[BUTTON_TYPES]
                .setDisabledIcon(new TypesIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE, false));
        toolbarButton[BUTTON_TYPES].setEnabled(false);
        toolbarButton[BUTTON_TYPES].addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                new TypesFrame(model);
            }
        });

        toolBar.add(toolbarButton[BUTTON_TYPES]);

        toolbarButton[BUTTON_QUERY] = new JButton("Query",
                new QueryIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE));
        toolbarButton[BUTTON_QUERY]
                .setDisabledIcon(new QueryIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE, false));
        toolbarButton[BUTTON_QUERY].setEnabled(false);
        toolbarButton[BUTTON_QUERY].addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                new QueryFrame(model);
            }
        });

        toolBar.add(toolbarButton[BUTTON_QUERY]);

        toolbarButton[BUTTON_CHANGELOG] = new JButton("Change Log",
                new ChangeLogIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE));
        toolbarButton[BUTTON_CHANGELOG].setDisabledIcon(
                new ChangeLogIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE, false));
        toolbarButton[BUTTON_CHANGELOG].setEnabled(false);
        toolbarButton[BUTTON_CHANGELOG].addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                new ChangeLogFrame(model);
            }
        });

        toolBar.add(toolbarButton[BUTTON_CHANGELOG]);

        toolbarButton[BUTTON_CONSOLE] = new JButton("Console",
                new ConsoleIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE));
        toolbarButton[BUTTON_CONSOLE].setDisabledIcon(
                new ConsoleIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE, false));
        toolbarButton[BUTTON_CONSOLE].setEnabled(false);
        toolbarButton[BUTTON_CONSOLE].addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toolbarConsolePopup.show(toolbarButton[BUTTON_CONSOLE], 0, toolbarButton[BUTTON_CONSOLE].getHeight());
            }
        });

        toolBar.add(toolbarButton[BUTTON_CONSOLE]);

        toolbarConsolePopup = new JPopupMenu();
        for (FileEntry fe : ConsoleHelper.readScriptLibrary()) {
            JMenuItem menuItem = new JMenuItem(fe.getName());
            final URI file = fe.getFile();
            menuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    ConsoleHelper.openConsole(ClientFrame.this, model, file);
                }
            });
            toolbarConsolePopup.add(menuItem);
        }

        toolbarButton[BUTTON_TCK] = new JButton("TCK",
                new TckIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE));
        toolbarButton[BUTTON_TCK]
                .setDisabledIcon(new TckIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE, false));
        toolbarButton[BUTTON_TCK].setEnabled(false);
        toolbarButton[BUTTON_TCK].addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                new TckDialog(thisFrame, model);
            }
        });

        toolBar.add(toolbarButton[BUTTON_TCK]);

        toolBar.addSeparator();

        toolbarCreatePopup = new JPopupMenu();
        documentMenuItem = new JMenuItem("Document");
        documentMenuItem.setEnabled(true);
        toolbarCreatePopup.add(documentMenuItem);
        documentMenuItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                new CreateDocumentDialog(thisFrame, model);
            }
        });

        itemMenuItem = new JMenuItem("Item");
        itemMenuItem.setEnabled(false);
        toolbarCreatePopup.add(itemMenuItem);
        itemMenuItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                new CreateItemDialog(thisFrame, model);
            }
        });

        folderMenuItem = new JMenuItem("Folder");
        folderMenuItem.setEnabled(true);
        toolbarCreatePopup.add(folderMenuItem);
        folderMenuItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                new CreateFolderDialog(thisFrame, model);
            }
        });

        relationshipMenuItem = new JMenuItem("Relationship");
        relationshipMenuItem.setEnabled(false);
        toolbarCreatePopup.add(relationshipMenuItem);
        relationshipMenuItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                new CreateRelationshipDialog(thisFrame, model);
            }
        });

        policyMenuItem = new JMenuItem("Policy");
        policyMenuItem.setEnabled(false);
        toolbarCreatePopup.add(policyMenuItem);
        policyMenuItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                new CreatePolicyDialog(thisFrame, model);
            }
        });

        toolbarButton[BUTTON_CREATE] = new JButton("Create Object",
                new CreateObjectIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE));
        toolbarButton[BUTTON_CREATE].setDisabledIcon(
                new CreateObjectIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE, false));
        toolbarButton[BUTTON_CREATE].setEnabled(false);
        toolbarButton[BUTTON_CREATE].addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toolbarCreatePopup.show(toolbarButton[BUTTON_CREATE], 0, toolbarButton[BUTTON_CREATE].getHeight());
            }
        });

        toolBar.add(toolbarButton[BUTTON_CREATE]);

        toolBar.addSeparator();

        toolbarButton[BUTTON_LOG] = new JButton("Log",
                new LogIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE));
        toolbarButton[BUTTON_LOG]
                .setDisabledIcon(new LogIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE, false));
        toolbarButton[BUTTON_LOG].addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                logFrame.showFrame();
            }
        });

        toolBar.add(toolbarButton[BUTTON_LOG]);

        toolbarButton[BUTTON_INFO] = new JButton("Info",
                new InfoIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE));
        toolbarButton[BUTTON_INFO]
                .setDisabledIcon(new InfoIcon(ClientHelper.TOOLBAR_ICON_SIZE, ClientHelper.TOOLBAR_ICON_SIZE, false));
        toolbarButton[BUTTON_INFO].addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                infoDialog.showDialog();
            }
        });

        toolBar.add(toolbarButton[BUTTON_INFO]);

        pane.add(toolBar, BorderLayout.PAGE_START);

        folderPanel = new FolderPanel(model);
        detailsTabs = new DetailsTabs(model);

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, folderPanel, detailsTabs);

        pane.add(split, BorderLayout.CENTER);

        addWindowListener(this);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setPreferredSize(new Dimension(prefs.getInt(PREFS_WIDTH, (int) (screenSize.getWidth() / 1.5)),
                prefs.getInt(PREFS_HEIGHT, (int) (screenSize.getHeight() / 1.5))));
        setMinimumSize(new Dimension(200, 60));

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        pack();

        split.setDividerLocation(prefs.getInt(PREFS_DIV, getPreferredSize().width / 4));

        if (prefs.getInt(PREFS_X, Integer.MAX_VALUE) == Integer.MAX_VALUE) {
            setLocationRelativeTo(null);
        } else {
            setLocation(prefs.getInt(PREFS_X, 0), prefs.getInt(PREFS_Y, 0));
        }

        setVisible(true);
    }

    private void showLoginForm() {
        loginDialog.showDialog();
        if (!loginDialog.isCanceled()) {
            ClientSession clientSession = loginDialog.getClientSession();

            model.setClientSession(clientSession);

            try {
                setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

                folderPanel.clear();

                LoadFolderWorker.loadFolderById(ClientFrame.this, model, clientSession.getStartFolderId());
                LoadObjectWorker.loadObject(ClientFrame.this, model, clientSession.getStartFolderId());

                toolbarButton[BUTTON_REPOSITORY_INFO].setEnabled(true);
                toolbarButton[BUTTON_TYPES].setEnabled(true);
                toolbarButton[BUTTON_QUERY].setEnabled(model.supportsQuery());
                toolbarButton[BUTTON_CHANGELOG].setEnabled(model.supportsChangeLog());
                toolbarButton[BUTTON_CONSOLE].setEnabled(true);
                toolbarButton[BUTTON_TCK].setEnabled(true);
                toolbarButton[BUTTON_CREATE].setEnabled(true);

                itemMenuItem.setEnabled(model.supportsItems());
                relationshipMenuItem.setEnabled(model.supportsRelationships());
                policyMenuItem.setEnabled(model.supportsPolicies());

                String user = clientSession.getSessionParameters().get(SessionParameter.USER);
                if (user != null) {
                    user = " - (" + user + ")";
                } else {
                    user = "";
                }

                setTitle(WINDOW_TITLE + user + " - " + clientSession.getSession().getRepositoryInfo().getName());
            } catch (Exception ex) {
                toolbarButton[BUTTON_REPOSITORY_INFO].setEnabled(false);
                toolbarButton[BUTTON_TYPES].setEnabled(false);
                toolbarButton[BUTTON_QUERY].setEnabled(false);
                toolbarButton[BUTTON_CHANGELOG].setEnabled(false);
                toolbarButton[BUTTON_CONSOLE].setEnabled(false);
                toolbarButton[BUTTON_TCK].setEnabled(false);
                toolbarButton[BUTTON_CREATE].setEnabled(false);

                ClientHelper.showError(null, ex);

                setTitle(WINDOW_TITLE);
            } finally {
                setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            }
        }
    }

    @Override
    public void windowOpened(WindowEvent e) {
    }

    @Override
    public void windowClosing(WindowEvent e) {
        Point p = getLocation();
        prefs.putInt(PREFS_X, p.x);
        prefs.putInt(PREFS_Y, p.y);
        prefs.putInt(PREFS_WIDTH, getWidth());
        prefs.putInt(PREFS_HEIGHT, getHeight());
        prefs.putInt(PREFS_DIV, split.getDividerLocation());
    }

    @Override
    public void windowClosed(WindowEvent e) {
    }

    @Override
    public void windowIconified(WindowEvent e) {
    }

    @Override
    public void windowDeiconified(WindowEvent e) {
    }

    @Override
    public void windowActivated(WindowEvent e) {
    }

    @Override
    public void windowDeactivated(WindowEvent e) {
    }
}

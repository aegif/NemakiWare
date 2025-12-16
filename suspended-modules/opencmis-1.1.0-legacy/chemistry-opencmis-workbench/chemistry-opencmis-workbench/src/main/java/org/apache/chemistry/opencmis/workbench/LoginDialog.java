/*
x * Licensed to the Apache Software Foundation (ASF) under one
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
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.ListCellRenderer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.apache.chemistry.opencmis.client.api.Repository;
import org.apache.chemistry.opencmis.workbench.model.ClientSession;

public class LoginDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    public static final String SYSPROP_LOGIN_TAB = ClientSession.WORKBENCH_PREFIX + "logintab";

    private static final ServiceLoader<AbstractLoginTab> TAB_SERVICE_LOADER = ServiceLoader
            .load(AbstractLoginTab.class);

    private JTabbedPane loginTabs;
    private BasicLoginTab basicLoginTab;
    private ExpertLoginTab expertLoginTab;
    private DiscoverLoginTab discoverLoginTab;
    private JButton loadRepositoryButton;
    private JButton loginButton;
    private JComboBox<Repository> repositoryBox;
    private AbstractLoginTab currentTab;

    private boolean canceled = true;

    private ClientSession clientSession;

    public LoginDialog(Frame owner) {
        super(owner, "Login", true);
        createGUI();
    }

    private void createGUI() {
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setPreferredSize(new Dimension((int) (screenSize.getWidth() / 2), (int) (screenSize.getHeight() / 3)));
        setMinimumSize(new Dimension(700, 500));

        Container pane = getContentPane();
        pane.setLayout(new BorderLayout());

        loginTabs = new JTabbedPane();
        add(loginTabs, BorderLayout.CENTER);

        // add tabs
        addLoginTabs(loginTabs);

        // repository
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.PAGE_AXIS));
        buttonPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5)));
        add(buttonPanel, BorderLayout.PAGE_END);

        loadRepositoryButton = createButton("Load Repositories");
        buttonPanel.add(loadRepositoryButton);
        getRootPane().setDefaultButton(loadRepositoryButton);

        createRepositoryBox(buttonPanel);

        loginButton = createButton("Login");
        buttonPanel.add(loginButton);
        loginButton.setEnabled(false);

        // listeners
        loadRepositoryButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                repositoryBox.removeAllItems();

                try {
                    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

                    currentTab.beforeLoadRepositories();

                    createClientSession();

                    List<Repository> repositories = clientSession.getRepositories();

                    Collections.sort(repositories, new Comparator<Repository>() {
                        @Override
                        public int compare(Repository r1, Repository r2) {
                            if (r1 == null || r1.getName() == null) {
                                return 1;
                            }

                            if (r2 == null || r2.getName() == null) {
                                return -1;
                            }

                            return r1.getName().compareTo(r2.getName());
                        }
                    });

                    if (repositories.isEmpty()) {
                        repositoryBox.setEnabled(false);
                        loginButton.setEnabled(false);
                        getRootPane().setDefaultButton(loadRepositoryButton);

                        JOptionPane.showMessageDialog(getOwner(),
                                "The CMIS endpoint retruned an empty list of repositories.", "No repositories!",
                                JOptionPane.WARNING_MESSAGE);
                    } else {
                        for (Repository repository : repositories) {
                            repositoryBox.addItem(repository);
                        }

                        repositoryBox.setEnabled(true);
                        loginButton.setEnabled(true);
                        getRootPane().setDefaultButton(loginButton);
                    }

                    currentTab.afterLoadRepositories(repositories);
                } catch (Exception ex) {
                    repositoryBox.setEnabled(false);
                    loginButton.setEnabled(false);
                    getRootPane().setDefaultButton(loadRepositoryButton);

                    new ConnectionErrorDialog(LoginDialog.this, ex);
                } finally {
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            }
        });

        loginButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

                    currentTab.beforeLogin(clientSession.getRepositories().get(repositoryBox.getSelectedIndex()));

                    clientSession.createSession(repositoryBox.getSelectedIndex());

                    currentTab.afterLogin(clientSession.getSession());

                    clientSession.setStartFolderId(currentTab.getStartFolderId());

                    canceled = false;
                    hideDialog();
                } catch (Exception ex) {
                    repositoryBox.setEnabled(false);
                    loginButton.setEnabled(false);
                    getRootPane().setDefaultButton(loadRepositoryButton);

                    new ConnectionErrorDialog(LoginDialog.this, ex);
                } finally {
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                    getRootPane().setDefaultButton(loadRepositoryButton);
                }
            }
        });

        ClientHelper.installEscapeBinding(this, getRootPane(), false);

        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        pack();

        // if a login tab is too big...
        if (loginTabs.getPreferredSize().height - loginTabs.getSize().height > 0) {
            // ... resize the dialog
            setPreferredSize(new Dimension(getSize().width, getSize().height
                    + (loginTabs.getPreferredSize().height - loginTabs.getSize().height)));
            pack();
        }

        setLocationRelativeTo(null);
    }

    private void addLoginTabs(final JTabbedPane loginTabs) {

        for (AbstractLoginTab tab : TAB_SERVICE_LOADER) {
            loginTabs.add(tab.getTabTitle(), tab);
        }

        basicLoginTab = new BasicLoginTab();
        loginTabs.addTab(basicLoginTab.getTabTitle(), basicLoginTab);

        expertLoginTab = new ExpertLoginTab();
        loginTabs.addTab(expertLoginTab.getTabTitle(), expertLoginTab);

        discoverLoginTab = new DiscoverLoginTab();
        loginTabs.addTab(discoverLoginTab.getTabTitle(), discoverLoginTab);

        loginTabs.setSelectedIndex(0);

        String startTab = System.getProperty(SYSPROP_LOGIN_TAB, "0");
        try {
            int tab = Integer.parseInt(startTab);
            if (tab >= 0 && tab < loginTabs.getTabCount()) {
                loginTabs.setSelectedIndex(tab);
            }
        } catch (NumberFormatException nfe) {
            // do nothing
        }

        currentTab = (AbstractLoginTab) loginTabs.getSelectedComponent();

        loginTabs.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                if (loginTabs.getSelectedComponent() == expertLoginTab) {
                    if (currentTab.transferSessionParametersToExpertTab()) {
                        expertLoginTab.setSessionParameters(currentTab.getSessionParameters());
                    }
                }

                if (loginTabs.getSelectedComponent() == discoverLoginTab) {
                    loadRepositoryButton.setEnabled(false);
                } else {
                    loadRepositoryButton.setEnabled(true);
                }

                currentTab = (AbstractLoginTab) loginTabs.getSelectedComponent();
            }
        });
    }

    private JButton createButton(String title) {
        JButton button = new JButton(title);

        int height = 30;
        height = Math.max(height,
                getFontMetrics(button.getFont()).getHeight() + button.getInsets().top + button.getInsets().bottom);

        button.setPreferredSize(new Dimension(Short.MAX_VALUE, height));
        button.setMaximumSize(new Dimension(Short.MAX_VALUE, Short.MAX_VALUE));
        button.setAlignmentX(Component.CENTER_ALIGNMENT);

        return button;
    }

    private void createRepositoryBox(Container pane) {
        repositoryBox = new JComboBox<Repository>();

        RepositoryRenderer renderer = new RepositoryRenderer();

        int height = (int) (renderer.getPreferredSize().height * 1.1);

        repositoryBox.setEnabled(false);
        repositoryBox.setRenderer(renderer);
        repositoryBox.setPreferredSize(new Dimension(Short.MAX_VALUE, height));
        repositoryBox.setAlignmentX(Component.CENTER_ALIGNMENT);

        pane.add(repositoryBox);
    }

    protected void setClientSession(ClientSession clientSession) {
        this.clientSession = clientSession;
    }

    public void createClientSession() {
        setClientSession(new ClientSession(currentTab.getSessionParameters(), currentTab.getObjectFactory(),
                currentTab.getAuthenticationProvider(), currentTab.getCache(), currentTab.getTypeDefinitionCache()));
    }

    public void showDialog() {
        clientSession = null;
        canceled = true;

        repositoryBox.removeAllItems();
        repositoryBox.setEnabled(false);
        loginButton.setEnabled(false);
        getRootPane().setDefaultButton(loadRepositoryButton);

        setLocationRelativeTo(getOwner());
        setVisible(true);
    }

    public void hideDialog() {
        setVisible(false);
    }

    public ClientSession getClientSession() {
        return clientSession;
    }

    public void switchToBasicTab() {
        loginTabs.setSelectedComponent(basicLoginTab);
    }

    public void switchToExpertTab() {
        if (currentTab.transferSessionParametersToExpertTab()) {
            expertLoginTab.setSessionParameters(currentTab.getSessionParameters());
        }
        loginTabs.setSelectedComponent(expertLoginTab);
    }

    public void switchToDiscoverTab() {
        loginTabs.setSelectedComponent(discoverLoginTab);
    }

    public boolean isCanceled() {
        return canceled;
    }

    static class RepositoryRenderer extends JPanel implements ListCellRenderer<Repository> {
        private static final long serialVersionUID = 1L;

        private final JLabel nameLabel;
        private final JLabel idLabel;
        private final JLabel descriptionLabel;

        public RepositoryRenderer() {
            super();
            setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
            setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3)));

            Font labelFont = UIManager.getFont("Label.font");

            nameLabel = new JLabel();
            nameLabel.setFont(labelFont.deriveFont(Font.BOLD));
            add(nameLabel);

            idLabel = new JLabel();
            add(idLabel);

            descriptionLabel = new JLabel();
            add(descriptionLabel);

            setPreferredSize(new Dimension(Short.SIZE, (getFontMetrics(labelFont).getHeight() * 3)
                    + WorkbenchScale.scaleInt(6)));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Repository> list, Repository value, int index,
                boolean isSelected, boolean cellHasFocus) {
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                setForeground(list.getForeground());
            }

            if (value == null) {
                nameLabel.setText("");
                idLabel.setText("");
                descriptionLabel.setText("");
            } else {
                nameLabel.setText(value.getName());
                idLabel.setText(value.getId());
                descriptionLabel.setText(value.getDescription());
            }

            return this;
        }
    }
}

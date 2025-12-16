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
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.text.JTextComponent;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.chemistry.opencmis.workbench.icons.AddIcon;
import org.apache.chemistry.opencmis.workbench.icons.RemoveIcon;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.worker.LoadObjectWorker;

public class AclEditorFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final String WINDOW_TITLE = "ACL Editor";
    private static final Icon ICON_ADD = new AddIcon(ClientHelper.ICON_BUTTON_ICON_SIZE,
            ClientHelper.ICON_BUTTON_ICON_SIZE);

    private final ClientModel model;
    private final CmisObject object;

    private final AceList addAceList;
    private final AceList removeAceList;

    private JRadioButton propagationRepositoryButton;
    private JRadioButton propagationObjectOnlyButton;
    private JRadioButton propagationPropagteButton;

    public AclEditorFrame(final ClientModel model, final CmisObject object) {
        super();

        this.model = model;
        this.object = object;

        // get users
        List<String> princiaplList = new ArrayList<String>();
        try {
            princiaplList.add("");
            princiaplList.add("cmis:user");

            String user = model.getClientSession().getSessionParameters().get(SessionParameter.USER);
            if (user != null && user.length() > 0) {
                princiaplList.add(user);
            }

            String anonymous = model.getRepositoryInfo().getPrincipalIdAnonymous();
            if (anonymous != null && anonymous.length() > 0) {
                princiaplList.add(anonymous);
            }

            String anyone = model.getRepositoryInfo().getPrincipalIdAnyone();
            if (anyone != null && anyone.length() > 0) {
                princiaplList.add(anyone);
            }

            if (object.getAcl() != null && object.getAcl().getAces() != null) {
                List<String> aclPrinciaplList = new ArrayList<String>();

                for (Ace ace : object.getAcl().getAces()) {
                    String pid = ace.getPrincipalId();
                    if (!princiaplList.contains(pid) && !aclPrinciaplList.contains(pid)) {
                        aclPrinciaplList.add(pid);
                    }
                }

                Collections.sort(aclPrinciaplList);

                princiaplList.addAll(aclPrinciaplList);
            }
        } catch (Exception ex) {
            princiaplList = new ArrayList<String>();
            princiaplList.add("");
            princiaplList.add("cmis:user");
        }

        // get permissions
        List<String> permissionsList = new ArrayList<String>();
        try {
            permissionsList.add("");
            for (PermissionDefinition pd : model.getRepositoryInfo().getAclCapabilities().getPermissions()) {
                permissionsList.add(pd.getId());
            }
        } catch (Exception ex) {
            permissionsList = new ArrayList<String>();
            permissionsList.add("");
            permissionsList.add("cmis:read");
            permissionsList.add("cmis:write");
            permissionsList.add("cmis:all");
        }

        addAceList = new AceList(princiaplList, permissionsList);
        removeAceList = new AceList(princiaplList, permissionsList);

        createGUI();
    }

    private void createGUI() {
        setTitle(WINDOW_TITLE);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setPreferredSize(new Dimension((int) (screenSize.getWidth() / 1.5), (int) (screenSize.getHeight() / 1.5)));
        setMinimumSize(new Dimension(300, 120));

        setLayout(new BorderLayout());

        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));

        final Font labelFont = UIManager.getFont("Label.font");
        final Font boldFont = labelFont.deriveFont(Font.BOLD, labelFont.getSize2D() * 1.2f);

        final JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        final JLabel nameLabel = new JLabel(object.getName());
        nameLabel.setFont(boldFont);
        topPanel.add(nameLabel);
        topPanel.add(new JLabel(object.getId()));
        add(topPanel, BorderLayout.PAGE_START);

        // ACE panels
        final JPanel addAcePanel = createAceListPanel("Add ACEs", addAceList);
        final JPanel removeAcePanel = createAceListPanel("Remove ACEs", removeAceList);

        JPanel centerPanel = new JPanel(new BorderLayout());

        final JSplitPane aceSplitPanel = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(addAcePanel),
                new JScrollPane(removeAcePanel));

        centerPanel.add(aceSplitPanel, BorderLayout.CENTER);

        // propagation buttons
        propagationRepositoryButton = new JRadioButton("repository determined", true);
        propagationObjectOnlyButton = new JRadioButton("object only", false);
        propagationPropagteButton = new JRadioButton("propagate", false);

        try {
            if (model.getRepositoryInfo().getAclCapabilities().getAclPropagation() == AclPropagation.OBJECTONLY) {
                propagationPropagteButton.setEnabled(false);
            }
        } catch (Exception e) {
            propagationPropagteButton.setEnabled(true);
        }

        ButtonGroup propagtionGroup = new ButtonGroup();
        propagtionGroup.add(propagationRepositoryButton);
        propagtionGroup.add(propagationObjectOnlyButton);
        propagtionGroup.add(propagationPropagteButton);

        JPanel propagtionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        propagtionPanel.add(new JLabel("ACL Propagation:"));
        propagtionPanel.add(propagationRepositoryButton);
        propagtionPanel.add(propagationObjectOnlyButton);
        propagtionPanel.add(propagationPropagteButton);

        centerPanel.add(propagtionPanel, BorderLayout.PAGE_END);

        add(centerPanel, BorderLayout.CENTER);

        // update button
        JButton updateButton = new JButton("Update");
        updateButton.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        updateButton.setDefaultCapable(true);
        updateButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (doApply()) {
                    dispose();
                }
            }
        });

        add(updateButton, BorderLayout.PAGE_END);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);

        aceSplitPanel.setDividerLocation(0.5f);
    }

    private JPanel createAceListPanel(final String title, final AceList list) {
        final Font labelFont = UIManager.getFont("Label.font");
        final Font boldFont = labelFont.deriveFont(Font.BOLD, labelFont.getSize2D() * 1.2f);

        final JPanel result = new JPanel();
        result.setLayout(new BoxLayout(result, BoxLayout.PAGE_AXIS));

        final JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.LINE_AXIS));
        topPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)));

        final JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(boldFont);
        topPanel.add(titleLabel, BorderLayout.LINE_START);

        topPanel.add(Box.createHorizontalGlue());

        final JButton addButton = new JButton(ICON_ADD);
        addButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                list.addNewAce();
            }
        });

        topPanel.add(addButton, BorderLayout.LINE_END);

        result.add(topPanel);

        result.add(list);

        return result;
    }

    /**
     * Applies the ACEs.
     */
    private boolean doApply() {
        try {
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

            List<Ace> adds = addAceList.getAces();
            List<Ace> removes = removeAceList.getAces();

            if (adds != null || removes != null) {
                AclPropagation aclPropagation = AclPropagation.REPOSITORYDETERMINED;
                if (propagationObjectOnlyButton.isSelected()) {
                    aclPropagation = AclPropagation.OBJECTONLY;
                }

                if (propagationPropagteButton.isSelected()) {
                    aclPropagation = AclPropagation.PROPAGATE;
                }

                object.applyAcl(adds, removes, aclPropagation);
                LoadObjectWorker.reloadObject(this, model);
            }

            return true;
        } catch (Exception ex) {
            ClientHelper.showError(this, ex);
            return false;
        } finally {
            setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        }
    }

    /**
     * ACE list panel.
     */
    private static class AceList extends JPanel {

        private static final long serialVersionUID = 1L;

        private final List<AceInputPanel> panels;
        private final List<String> principals;
        private final List<String> permissions;

        public AceList(final List<String> principals, final List<String> permissions) {
            super();

            panels = new ArrayList<AceInputPanel>();
            this.principals = principals;
            this.permissions = permissions;

            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        public synchronized void addNewAce() {
            AceInputPanel acePanel = new AceInputPanel(this, principals, permissions, panels.size());
            panels.add(acePanel);

            add(acePanel);
        }

        public synchronized void removeAce(int position) {
            panels.remove(position);
            for (int i = position; i < panels.size(); i++) {
                panels.get(i).updatePosition(i);
            }

            removeAll();
            for (AceInputPanel p : panels) {
                add(p);
            }

            revalidate();
        }

        public synchronized List<Ace> getAces() {
            List<Ace> result = new ArrayList<Ace>();

            for (AceInputPanel p : panels) {
                result.add(p.getAce());
            }

            return result.isEmpty() ? null : result;
        }
    }

    /**
     * ACE input panel.
     */
    public static class AceInputPanel extends JPanel {

        private static final long serialVersionUID = 1L;

        private static final Color BACKGROUND1 = UIManager.getColor("Table:\"Table.cellRenderer\".background");
        private static final Color BACKGROUND2 = UIManager.getColor("Table.alternateRowColor");
        private static final Color LINE = new Color(0xB8, 0xB8, 0xB8);

        private static final Icon ICON_REMOVE = new RemoveIcon(ClientHelper.ICON_BUTTON_ICON_SIZE,
                ClientHelper.ICON_BUTTON_ICON_SIZE);
        private static final Icon ICON_REMOVE_DISABLED = new RemoveIcon(ClientHelper.ICON_BUTTON_ICON_SIZE,
                ClientHelper.ICON_BUTTON_ICON_SIZE, false);

        private final List<String> permissions;

        private int position;
        private final JComboBox<String> principalBox;
        private final JPanel permissionsPanel;
        private final List<JComboBox<String>> permissionBoxes;

        public AceInputPanel(final AceList list, final List<String> principals, final List<String> permissions,
                int position) {
            super();

            this.permissions = permissions;

            updatePosition(position);

            setLayout(new GridBagLayout());
            setBorder(WorkbenchScale.scaleBorder(BorderFactory.createCompoundBorder(
                    WorkbenchScale.scaleBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE)),
                    WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(10, 5, 10, 5)))));

            GridBagConstraints c = new GridBagConstraints();
            c.gridheight = 1;
            c.gridwidth = 1;

            // col 1
            c.gridx = 0;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.LINE_START;

            c.gridy = 0;
            add(new JLabel("Principal:"), c);

            c.gridy = 1;
            add(new JSeparator(SwingConstants.HORIZONTAL), c);

            c.gridy = 2;
            add(new JLabel("Permissions:"), c);

            // col 2
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.LINE_START;

            principalBox = new JComboBox<String>(principals.toArray(new String[0]));
            principalBox.setEditable(true);
            principalBox.setPrototypeDisplayValue("1234567890123456789012345");

            c.gridy = 0;
            add(principalBox, c);

            c.gridy = 1;
            add(new JSeparator(SwingConstants.HORIZONTAL), c);

            permissionsPanel = new JPanel();
            permissionsPanel.setLayout(new BoxLayout(permissionsPanel, BoxLayout.Y_AXIS));
            permissionsPanel.setOpaque(false);

            permissionBoxes = new ArrayList<JComboBox<String>>();

            updatePermissionsPanel(false);

            c.gridy = 2;
            add(permissionsPanel, c);

            // col 3
            c.gridx = 2;
            c.weightx = 1;
            c.fill = GridBagConstraints.NONE;
            c.anchor = GridBagConstraints.LINE_END;

            c.gridy = 0;
            JButton removeButton = new JButton(ICON_REMOVE);
            removeButton.setDisabledIcon(ICON_REMOVE_DISABLED);
            removeButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    list.removeAce(getPosition());
                }
            });

            add(removeButton, c);
        }

        private JComboBox<String> createPermissionBox() {
            JComboBox<String> result = new JComboBox<String>(permissions.toArray(new String[0]));
            result.setEditable(true);
            result.setPrototypeDisplayValue("1234567890123456789012345");

            JTextComponent editor = (JTextComponent) result.getEditor().getEditorComponent();
            editor.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent event) {
                    updatePermissionsPanel(true);
                }
            });

            editor.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_TAB) {
                        updatePermissionsPanel(true);
                    }
                }
            });

            return result;
        }

        private void updatePermissionsPanel(boolean focus) {
            boolean changed = false;

            if (!permissionBoxes.isEmpty()) {
                int i = 0;
                while (i < permissionBoxes.size() - 1) {
                    if (permissionBoxes.get(i).getSelectedItem().toString().trim().length() == 0) {
                        permissionBoxes.remove(i);
                        changed = true;
                    } else {
                        i++;
                    }
                }

                if (permissionBoxes.get(permissionBoxes.size() - 1).getSelectedItem().toString().trim().length() > 0) {
                    permissionBoxes.add(createPermissionBox());
                    changed = true;
                }
            } else {
                permissionBoxes.add(createPermissionBox());
                changed = true;
            }

            if (changed) {
                permissionsPanel.removeAll();

                for (JComboBox<String> box : permissionBoxes) {
                    permissionsPanel.add(box);
                }

                revalidate();

                if (focus) {
                    permissionBoxes.get(permissionBoxes.size() - 1).requestFocusInWindow();
                }
            }
        }

        private void updatePosition(int position) {
            this.position = position;
            setBackground(position % 2 == 0 ? BACKGROUND1 : BACKGROUND2);
        }

        public int getPosition() {
            return position;
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Short.MAX_VALUE, getPreferredSize().height);
        }

        public Ace getAce() {
            List<String> permissionsList = new ArrayList<String>();

            for (JComboBox<String> box : permissionBoxes) {
                String permission = box.getSelectedItem().toString().trim();
                if (permission.length() > 0) {
                    permissionsList.add(permission);
                }
            }

            return new AccessControlEntryImpl(new AccessControlPrincipalDataImpl(principalBox.getSelectedItem()
                    .toString()), permissionsList);
        }
    }
}

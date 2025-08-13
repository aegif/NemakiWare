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

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNotEmpty;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.GregorianCalendar;
import java.util.Map;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.WindowConstants;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.data.AclCapabilities;
import org.apache.chemistry.opencmis.commons.data.ExtensionFeature;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.RepositoryCapabilities;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.workbench.icons.ExtensionIcon;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.swing.ExtensionsTree;
import org.apache.chemistry.opencmis.workbench.swing.InfoPanel;

public class RepositoryInfoFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final String WINDOW_TITLE = "CMIS Repository Info";

    private final ClientModel model;
    private JComponent infoPanel;

    public RepositoryInfoFrame(ClientModel model) {
        super();

        this.model = model;
        createGUI();
    }

    private void createGUI() {
        setTitle(WINDOW_TITLE + " - " + model.getRepositoryName());
        setIconImages(ClientHelper.getCmisIconImages());

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setPreferredSize(new Dimension((int) (screenSize.getWidth() / 2.5), (int) (screenSize.getHeight() / 1.5)));
        setMinimumSize(new Dimension(200, 60));

        setLayout(new BorderLayout());

        RepositoryInfo repInfo = null;
        try {
            repInfo = model.getRepositoryInfo();
        } catch (Exception e) {
            ClientHelper.showError(this, e);
            dispose();
            return;
        }

        infoPanel = new JScrollPane(new RepositoryInfoPanel(model, repInfo, -1));
        add(infoPanel, BorderLayout.CENTER);

        final JPanel inputPanel = new JPanel(new FlowLayout());
        add(inputPanel, BorderLayout.PAGE_END);

        JButton reloadButton = new JButton("Reload");
        reloadButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

                    Session session = model.getClientSession().getSession();
                    String repId = session.getRepositoryInfo().getId();
                    RepositoryInfo repInfo = session.getBinding().getRepositoryService().getRepositoryInfo(repId, null);

                    remove(infoPanel);
                    infoPanel = new JScrollPane(new RepositoryInfoPanel(model, repInfo, System.currentTimeMillis()));
                    add(infoPanel, BorderLayout.CENTER);

                    validate();
                } finally {
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            }
        });
        inputPanel.add(reloadButton, BorderLayout.LINE_END);

        getRootPane().setDefaultButton(reloadButton);

        ClientHelper.installEscapeBinding(this, getRootPane(), true);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        pack();

        setLocationRelativeTo(null);
        setVisible(true);
    }

    static class RepositoryInfoPanel extends InfoPanel {

        private static final long serialVersionUID = 1L;

        private final RepositoryInfo repInfo;
        private final GregorianCalendar lastUpdate;

        public RepositoryInfoPanel(ClientModel model, RepositoryInfo repInfo, long lastUpdate) {
            super(model);

            this.repInfo = repInfo;
            if (lastUpdate >= 0) {
                this.lastUpdate = new GregorianCalendar();
                this.lastUpdate.setTimeInMillis(lastUpdate);
            } else {
                this.lastUpdate = null;
            }
            createGUI();
        }

        private void createGUI() {
            setupGUI();

            addLine("Name:", true).setText(repInfo.getName());
            addSeparator();
            addLine("Id:").setText(repInfo.getId());
            addLine("Description:").setText(repInfo.getDescription());
            addLine("Vendor:").setText(repInfo.getVendorName());
            addLine("Product:").setText(repInfo.getProductName() + " " + repInfo.getProductVersion());
            addLine("CMIS Version:").setText(repInfo.getCmisVersionSupported());
            addId("Root folder Id:").setText(repInfo.getRootFolderId());
            addLine("Latest change token:").setText(repInfo.getLatestChangeLogToken());
            addLink("Thin client URI:").setText(repInfo.getThinClientUri());
            addLine("Principal id anonymous:").setText(repInfo.getPrincipalIdAnonymous());
            addLine("Principal id anyone:").setText(repInfo.getPrincipalIdAnyone());
            addYesNoLabel("Changes incomplete:").setValue(is(repInfo.getChangesIncomplete()));

            StringBuilder sb = new StringBuilder(64);
            if (repInfo.getChangesOnType() != null) {
                for (BaseTypeId bt : repInfo.getChangesOnType()) {
                    appendToString(sb, bt.value());
                }
            }
            addLine("Changes on type:").setText(sb.toString());

            if (repInfo.getCapabilities() != null) {
                RepositoryCapabilities cap = repInfo.getCapabilities();

                addSeparator();
                addLine("Capabilities:", true).setText("");

                addYesNoLabel("Get descendants supported:").setValue(is(cap.isGetDescendantsSupported()));
                addYesNoLabel("Get folder tree supported:").setValue(is(cap.isGetFolderTreeSupported()));
                addYesNoLabel("Unfiling supported:").setValue(is(cap.isUnfilingSupported()));
                addYesNoLabel("Multifiling supported:").setValue(is(cap.isMultifilingSupported()));
                addYesNoLabel("Version specific filing supported:")
                        .setValue(is(cap.isVersionSpecificFilingSupported()));
                addLine("Order by:").setText(str(cap.getOrderByCapability()));
                addLine("Query:").setText(str(cap.getQueryCapability()));
                addLine("Joins:").setText(str(cap.getJoinCapability()));
                addYesNoLabel("All versions searchable:").setValue(is(cap.isAllVersionsSearchableSupported()));
                addYesNoLabel("PWC searchable:").setValue(is(cap.isPwcSearchableSupported()));
                addYesNoLabel("PWC updatable:").setValue(is(cap.isPwcUpdatableSupported()));
                addLine("Content stream updates:").setText(str(cap.getContentStreamUpdatesCapability()));
                addLine("Renditions:").setText(str(cap.getRenditionsCapability()));
                addLine("Changes:").setText(str(cap.getChangesCapability()));
                addLine("ACLs:").setText(str(cap.getAclCapability()));

                sb = new StringBuilder(128);
                if (cap.getNewTypeSettableAttributes() != null) {
                    if (Boolean.TRUE.equals(cap.getNewTypeSettableAttributes().canSetId())) {
                        appendToString(sb, "id");
                    }

                    if (Boolean.TRUE.equals(cap.getNewTypeSettableAttributes().canSetLocalName())) {
                        appendToString(sb, "localName");
                    }

                    if (Boolean.TRUE.equals(cap.getNewTypeSettableAttributes().canSetLocalNamespace())) {
                        appendToString(sb, "localNamespace");
                    }

                    if (Boolean.TRUE.equals(cap.getNewTypeSettableAttributes().canSetDisplayName())) {
                        appendToString(sb, "displayName");
                    }

                    if (Boolean.TRUE.equals(cap.getNewTypeSettableAttributes().canSetQueryName())) {
                        appendToString(sb, "queryName");
                    }

                    if (Boolean.TRUE.equals(cap.getNewTypeSettableAttributes().canSetDescription())) {
                        appendToString(sb, "description");
                    }

                    if (Boolean.TRUE.equals(cap.getNewTypeSettableAttributes().canSetCreatable())) {
                        appendToString(sb, "creatable");
                    }

                    if (Boolean.TRUE.equals(cap.getNewTypeSettableAttributes().canSetFileable())) {
                        appendToString(sb, "fileable");
                    }

                    if (Boolean.TRUE.equals(cap.getNewTypeSettableAttributes().canSetQueryable())) {
                        appendToString(sb, "queryable");
                    }

                    if (Boolean.TRUE.equals(cap.getNewTypeSettableAttributes().canSetFulltextIndexed())) {
                        appendToString(sb, "fulltextIndexed");
                    }

                    if (Boolean.TRUE.equals(cap.getNewTypeSettableAttributes().canSetIncludedInSupertypeQuery())) {
                        appendToString(sb, "includedInSupertypeQuery");
                    }

                    if (Boolean.TRUE.equals(cap.getNewTypeSettableAttributes().canSetControllablePolicy())) {
                        appendToString(sb, "controllablePolicy");
                    }

                    if (Boolean.TRUE.equals(cap.getNewTypeSettableAttributes().canSetControllableAcl())) {
                        appendToString(sb, "controllableACL");
                    }
                }

                addLine("New type settable attributes:").setText(sb.toString());

                sb = new StringBuilder(64);
                if (cap.getCreatablePropertyTypes() != null && cap.getCreatablePropertyTypes().canCreate() != null) {
                    for (PropertyType pt : cap.getCreatablePropertyTypes().canCreate()) {
                        appendToString(sb, pt.value());
                    }
                }

                addLine("Creatable property types:").setText(sb.toString());
            }

            if (repInfo.getAclCapabilities() != null) {
                AclCapabilities cap = repInfo.getAclCapabilities();

                addSeparator();
                addLine("ACL Capabilities:", true).setText("");

                addLine("Supported permissions:").setText(str(cap.getSupportedPermissions()));
                addLine("ACL propagation:").setText(str(cap.getAclPropagation()));

                if (cap.getPermissions() != null) {
                    String[][] data = new String[cap.getPermissions().size()][2];

                    int i = 0;
                    for (PermissionDefinition pd : cap.getPermissions()) {
                        data[i][0] = pd.getId();
                        data[i][1] = pd.getDescription();
                        i++;
                    }

                    JTable permTable = new JTable(data, new String[] { "Permission", "Description" });
                    permTable.setFillsViewportHeight(true);
                    permTable.setRowHeight((int) (getFontMetrics(getFont()).getHeight() * 1.1));
                    addComponent("Permissions:", createScrollePane(permTable));
                }

                if (cap.getPermissionMapping() != null) {
                    String[][] data = new String[cap.getPermissionMapping().size()][2];

                    int i = 0;
                    for (PermissionMapping pm : cap.getPermissionMapping().values()) {
                        data[i][0] = pm.getKey();
                        data[i][1] = (pm.getPermissions() == null ? "" : pm.getPermissions().toString());
                        i++;
                    }

                    JTable permMapTable = new JTable(data, new String[] { "Key", "Permissions" });
                    permMapTable.setFillsViewportHeight(true);
                    permMapTable.setRowHeight((int) (getFontMetrics(getFont()).getHeight() * 1.1));
                    addComponent("Permission mapping:", createScrollePane(permMapTable));
                }
            }

            if (isNotEmpty(repInfo.getExtensionFeatures())) {
                JTree extensionFeaturesTree = new JTree();
                extensionFeaturesTree.setRootVisible(false);
                extensionFeaturesTree.setCellRenderer(new ExtensionFeatureCellRenderer());
                extensionFeaturesTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);

                DefaultMutableTreeNode extFeatRootNode = new DefaultMutableTreeNode("Extensions");

                for (ExtensionFeature ef : repInfo.getExtensionFeatures()) {
                    DefaultMutableTreeNode efNode = new DefaultMutableTreeNode(ef);
                    extFeatRootNode.add(efNode);

                    if (ef.getCommonName() != null) {
                        efNode.add(new DefaultMutableTreeNode("Common Name: " + ef.getCommonName()));
                    }

                    if (ef.getVersionLabel() != null) {
                        efNode.add(new DefaultMutableTreeNode("Version Label: " + ef.getVersionLabel()));
                    }

                    if (ef.getDescription() != null) {
                        efNode.add(new DefaultMutableTreeNode("Description: " + ef.getDescription()));
                    }

                    if (ef.getUrl() != null) {
                        efNode.add(new DefaultMutableTreeNode("URL: " + ef.getUrl()));
                    }

                    if (isNotEmpty(ef.getFeatureData())) {
                        DefaultMutableTreeNode dataNode = new DefaultMutableTreeNode("Feature Data");
                        efNode.add(dataNode);

                        for (Map.Entry<String, String> e : ef.getFeatureData().entrySet()) {
                            dataNode.add(new DefaultMutableTreeNode(e.getKey() + ": " + e.getValue()));
                        }
                    }
                }

                extensionFeaturesTree.setModel(new DefaultTreeModel(extFeatRootNode));

                addComponent("Extension Features:", createScrollePane(extensionFeaturesTree));
            }

            if (isNotEmpty(repInfo.getExtensions())) {
                addComponent("Extensions:", createScrollePane(new ExtensionsTree(repInfo.getExtensions())));
            }

            addSeparator();
            addLine("Last update:").setText(lastUpdate != null ? ClientHelper.getDateString(lastUpdate) : "(session)");

            regenerateGUI();
        }
        
        private JScrollPane createScrollePane(Component comp) {
            JScrollPane pane = new JScrollPane(comp);
            pane.setPreferredSize(new Dimension(pane.getPreferredSize().width, WorkbenchScale.scaleInt(200)));
            
            return pane;
        }

        private void appendToString(StringBuilder sb, String str) {
            if (sb.length() > 0) {
                sb.append(", ");
            }

            sb.append(str);
        }

        private boolean is(Boolean b) {
            if (b == null) {
                return false;
            }

            return b.booleanValue();
        }

        private String str(Object o) {
            if (o == null) {
                return "?";
            }

            return o.toString();
        }

        static class ExtensionFeatureCellRenderer extends DefaultTreeCellRenderer {

            private static final long serialVersionUID = 1L;

            private static final Icon EXTENSION_ICON = new ExtensionIcon(ClientHelper.OBJECT_ICON_SIZE,
                    ClientHelper.OBJECT_ICON_SIZE);

            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                    boolean leaf, int row, boolean hasFocus) {
                Component comp = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

                Object node = ((DefaultMutableTreeNode) value).getUserObject();
                if (node instanceof ExtensionFeature) {
                    setText(((ExtensionFeature) node).getId());
                    setIcon(EXTENSION_ICON);
                } else {
                    setIcon(null);
                }

                return comp;
            }
        }
    }
}

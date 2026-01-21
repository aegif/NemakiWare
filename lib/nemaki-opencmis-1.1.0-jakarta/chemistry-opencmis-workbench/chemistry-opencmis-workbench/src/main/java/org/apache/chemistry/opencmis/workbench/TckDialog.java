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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellEditor;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreeNode;

import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestProgressMonitor;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.CmisTestResultStatus;
import org.apache.chemistry.opencmis.tck.impl.TestParameters;
import org.apache.chemistry.opencmis.tck.runner.AbstractRunner;
import org.apache.chemistry.opencmis.workbench.checks.SwingReport;
import org.apache.chemistry.opencmis.workbench.icons.TckIcon;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;

/**
 * TCK dialog and runner.
 */
public class TckDialog {

    private static final ServiceLoader<AbstractTckRunnerConfigurator> TCK_RUNNER_SERVICE_LOADER = ServiceLoader
            .load(AbstractTckRunnerConfigurator.class);

    private final Frame owner;
    private final ClientModel model;
    private final TckDialogRunner runner;

    private Map<CmisTestResultStatus, Integer> status;

    private JProgressBar groupsProgressBar;
    private JProgressBar testsProgressBar;
    private JLabel statusLabel;

    public TckDialog(Frame owner, ClientModel model) {
        this.owner = owner;
        this.model = model;
        this.runner = new TckDialogRunner(model, this);

        status = new HashMap<CmisTestResultStatus, Integer>();
        status.put(CmisTestResultStatus.INFO, 0);
        status.put(CmisTestResultStatus.SKIPPED, 0);
        status.put(CmisTestResultStatus.OK, 0);
        status.put(CmisTestResultStatus.WARNING, 0);
        status.put(CmisTestResultStatus.FAILURE, 0);
        status.put(CmisTestResultStatus.UNEXPECTED_EXCEPTION, 0);

        try {
            boolean configured = false;
            for (AbstractTckRunnerConfigurator configurator : TCK_RUNNER_SERVICE_LOADER) {
                configurator.configureRunner(runner);
                configured = true;
            }

            if (!configured) {
                runner.loadDefaultTckGroups();
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(owner, "Error: " + e.getMessage(), "TCK Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        new TckSelectDialog();
    }

    private class TckSelectDialog extends JDialog {
        private static final long serialVersionUID = 1L;

        public TckSelectDialog() {
            super(owner, "TCK", true);

            createGUI();
        }

        private void createGUI() {
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            setPreferredSize(new Dimension((int) (screenSize.getWidth() / 3), (int) (screenSize.getHeight() / 1.5)));
            setMinimumSize(new Dimension(600, 500));

            setLayout(new BorderLayout());

            // tree
            final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Groups");
            final JTree groupTree = new JTree(rootNode);

            for (CmisTestGroup group : runner.getGroups()) {
                final TestTreeNode groupNode = new TestTreeNode(groupTree, group);
                rootNode.add(groupNode);
                for (CmisTest test : group.getTests()) {
                    final TestTreeNode testNode = new TestTreeNode(groupTree, test);
                    groupNode.add(testNode);
                }
            }

            ((DefaultTreeModel) groupTree.getModel()).reload();

            groupTree.setRootVisible(false);
            groupTree.setCellRenderer(new TestTreeNodeRender());
            groupTree.setCellEditor(new TestTreeNodeEditor());
            groupTree.setEditable(true);
            ToolTipManager.sharedInstance().registerComponent(groupTree);

            for (int i = 0; i < groupTree.getRowCount(); i++) {
                groupTree.expandRow(i);
            }

            final JPopupMenu treePopup = new JPopupMenu();

            final JMenuItem selectItem = new JMenuItem("Select all");
            selectItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    selectAll(groupTree, true);
                }
            });
            treePopup.add(selectItem);

            final JMenuItem deselectItem = new JMenuItem("Deselect all");
            deselectItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    selectAll(groupTree, false);
                }
            });
            treePopup.add(deselectItem);

            groupTree.addMouseListener(new MouseAdapter() {
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
                        treePopup.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            });

            // config panel
            final JPanel configPanel = new JPanel();
            configPanel.setLayout(new BoxLayout(configPanel, BoxLayout.PAGE_AXIS));
            configPanel.setPreferredSize(new Dimension(getWidth() / 2, 500));
            configPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0)));

            final JComboBox<String> folderComboBox = addComboBox(configPanel, "Test folder type:",
                    BaseTypeId.CMIS_FOLDER.value(), TestParameters.DEFAULT_FOLDER_TYPE_VALUE, true);
            configPanel.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(1, 10))));

            final JComboBox<String> documentComboBox = addComboBox(configPanel, "Test document type:",
                    BaseTypeId.CMIS_DOCUMENT.value(), TestParameters.DEFAULT_DOCUMENT_TYPE_VALUE, true);
            configPanel.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(1, 10))));

            final JComboBox<String> relationshipComboBox = addComboBox(configPanel, "Test relationship type:",
                    BaseTypeId.CMIS_RELATIONSHIP.value(), TestParameters.DEFAULT_RELATIONSHIP_TYPE_VALUE, true);
            configPanel.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(1, 10))));

            final JComboBox<String> policyComboBox = addComboBox(configPanel, "Test policy type:",
                    BaseTypeId.CMIS_POLICY.value(), TestParameters.DEFAULT_POLICY_TYPE_VALUE, true);
            configPanel.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(1, 10))));

            final JComboBox<String> itemComboBox = addComboBox(configPanel, "Test item type:",
                    BaseTypeId.CMIS_ITEM.value(), TestParameters.DEFAULT_ITEM_TYPE_VALUE, true);
            configPanel.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(1, 10))));

            final JComboBox<String> secondaryComboBox = addComboBox(configPanel, "Test secondary type:",
                    BaseTypeId.CMIS_SECONDARY.value(), TestParameters.DEFAULT_SECONDARY_TYPE_VALUE, false);
            configPanel.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(1, 10))));

            configPanel.add(new JLabel("Test folder path:"));
            final JTextField testParentFolderField = new JTextField(TestParameters.DEFAULT_TEST_FOLDER_PARENT_VALUE);
            testParentFolderField.setMaximumSize(new Dimension(Short.MAX_VALUE, WorkbenchScale.scaleInt(10)));
            testParentFolderField.setAlignmentX(Component.LEFT_ALIGNMENT);
            configPanel.add(testParentFolderField);

            configPanel.add(Box.createVerticalGlue());

            add(configPanel);

            final JPanel mainPanel = new JPanel(new BorderLayout());
            mainPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)));
            mainPanel.add(new JScrollPane(groupTree), BorderLayout.CENTER);
            mainPanel.add(configPanel, BorderLayout.LINE_END);
            add(mainPanel, BorderLayout.CENTER);

            final JButton runButton = new JButton("Run TCK", new TckIcon(ClientHelper.BUTTON_ICON_SIZE,
                    ClientHelper.BUTTON_ICON_SIZE));
            runButton.setDefaultCapable(true);
            runButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    int answer = JOptionPane
                            .showConfirmDialog(
                                    owner,
                                    "Running the TCK may take a long time and may add, remove and alter data in the repository!\n"
                                            + "It also puts at a strain on the repository, performing several thousand calls!\n"
                                            + "\nAre you sure you want to proceed?", "TCK", JOptionPane.YES_NO_OPTION,
                                    JOptionPane.WARNING_MESSAGE);

                    if (answer == JOptionPane.YES_OPTION) {
                        Map<String, String> parameters = runner.getParameters();
                        parameters.put(TestParameters.DEFAULT_FOLDER_TYPE, (String) folderComboBox.getSelectedItem());
                        parameters.put(TestParameters.DEFAULT_DOCUMENT_TYPE,
                                (String) documentComboBox.getSelectedItem());
                        if (relationshipComboBox.isEnabled()) {
                            parameters.put(TestParameters.DEFAULT_RELATIONSHIP_TYPE,
                                    (String) relationshipComboBox.getSelectedItem());
                        }
                        if (policyComboBox.isEnabled()) {
                            parameters.put(TestParameters.DEFAULT_POLICY_TYPE,
                                    (String) policyComboBox.getSelectedItem());
                        }
                        if (itemComboBox.isEnabled()) {
                            parameters.put(TestParameters.DEFAULT_ITEM_TYPE, (String) itemComboBox.getSelectedItem());
                        }
                        if (secondaryComboBox.isEnabled()) {
                            parameters.put(TestParameters.DEFAULT_SECONDARY_TYPE,
                                    (String) secondaryComboBox.getSelectedItem());
                        }
                        parameters.put(TestParameters.DEFAULT_TEST_FOLDER_PARENT, testParentFolderField.getText());

                        runner.setParameters(parameters);

                        dispose();
                        new TckRunDialog();
                    }
                }
            });

            int height = 30;
            height = Math.max(height, getFontMetrics(runButton.getFont()).getHeight() + runButton.getInsets().top
                    + runButton.getInsets().bottom);

            final JPanel runButtonPanel = new JPanel();
            runButtonPanel.setLayout(new BoxLayout(runButtonPanel, BoxLayout.PAGE_AXIS));
            runButtonPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(0, 3, 3, 3)));
            runButton.setMaximumSize(WorkbenchScale.scaleDimension(new Dimension(Short.MAX_VALUE, height)));
            runButtonPanel.add(runButton);

            add(runButtonPanel, BorderLayout.PAGE_END);

            getRootPane().setDefaultButton(runButton);

            ClientHelper.installEscapeBinding(this, getRootPane(), true);

            setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            pack();
            setLocationRelativeTo(null);
            setVisible(true);
        }

        private void selectAll(final JTree tree, boolean select) {
            for (CmisTestGroup group : runner.getGroups()) {
                group.setEnabled(select);
                for (CmisTest test : group.getTests()) {
                    test.setEnabled(select);
                }
            }

            DefaultTreeModel treeModel = ((DefaultTreeModel) tree.getModel());
            treeModel.nodeChanged((TreeNode) treeModel.getRoot());
        }

        private JComboBox<String> addComboBox(JPanel panel, String title, String rootTypeId, String defaultTypeId,
                boolean creatableOnly) {
            final JLabel label = new JLabel(title);
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(label);

            List<ObjectType> types = model.getTypesAsList(rootTypeId, creatableOnly);
            String[] typeIds = new String[types.size()];

            int i = 0;
            for (ObjectType type : types) {
                typeIds[i++] = type.getId();
            }

            final JComboBox<String> comboBox = new JComboBox<String>(typeIds);
            comboBox.setSelectedItem(defaultTypeId);
            comboBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            comboBox.setMaximumSize(new Dimension(Short.MAX_VALUE, 10));
            comboBox.setEnabled(typeIds.length > 0);
            panel.add(comboBox);

            return comboBox;
        }
    }

    private static class TestTreeNode extends DefaultMutableTreeNode {
        private static final long serialVersionUID = 1L;

        private final JTree tree;
        private final CmisTestGroup group;
        private final CmisTest test;

        public TestTreeNode(JTree tree, CmisTestGroup group) {
            this.tree = tree;
            this.group = group;
            this.test = null;
        }

        public TestTreeNode(JTree tree, CmisTest test) {
            this.tree = tree;
            this.test = test;
            this.group = null;
        }

        public CmisTestGroup getGroup() {
            return group;
        }

        public String getName() {
            if (group != null) {
                return group.getName();
            }

            return test.getName();
        }

        public String getDescription() {
            if (group != null) {
                return group.getDescription();
            }

            return test.getDescription();
        }

        public boolean isEnabled() {
            if (group != null) {
                return group.isEnabled();
            }

            return test.isEnabled();
        }

        public void setEnabled(boolean enabled) {
            DefaultTreeModel treeModel = ((DefaultTreeModel) tree.getModel());

            if (group != null) {
                group.setEnabled(enabled);

                for (int i = 0; i < getChildCount(); i++) {
                    TestTreeNode node = (TestTreeNode) getChildAt(i);
                    node.setEnabled(enabled);
                    treeModel.nodeChanged(node);
                }

                return;
            }

            test.setEnabled(enabled);

            if (enabled) {
                TestTreeNode node = (TestTreeNode) getParent();
                node.getGroup().setEnabled(true);
                treeModel.nodeChanged(node);
            }
        }
    }

    private static class TestTreeNodeRender extends JCheckBox implements TreeCellRenderer {
        private static final long serialVersionUID = 1L;

        private final Color textSelectionColor;
        private final Color textNonSelectionColor;
        private final Color backgroundSelectionColor;
        private final Color backgroundNonSelectionColor;

        public TestTreeNodeRender() {
            textSelectionColor = UIManager.getDefaults().getColor("Tree.selectionForeground");
            textNonSelectionColor = UIManager.getDefaults().getColor("Tree.textForeground");
            backgroundSelectionColor = UIManager.getDefaults().getColor("Tree.selectionBackground");
            backgroundNonSelectionColor = UIManager.getDefaults().getColor("Tree.textBackground");

            Insets margins = UIManager.getDefaults().getInsets("Tree.rendererMargins");
            if (margins != null) {
                setBorder(new EmptyBorder(margins.top, margins.left, margins.bottom, margins.right));
            }
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                boolean leaf, int row, boolean hasFocus) {

            if (sel) {
                setForeground(textSelectionColor);
                setBackground(backgroundSelectionColor);
            } else {
                setForeground(textNonSelectionColor);
                setBackground(backgroundNonSelectionColor);
            }

            if (value instanceof TestTreeNode) {
                TestTreeNode node = (TestTreeNode) value;
                setText(node.getName());
                setSelected(node.isEnabled());
                setToolTipText(node.getDescription());
            } else {
                setText(value == null ? "" : value.toString());
                setToolTipText(null);
            }

            return this;
        }

        @Override
        public void validate() {
        }

        @Override
        public void invalidate() {
        }

        @Override
        public void revalidate() {
        }

        @Override
        public void repaint(long tm, int x, int y, int width, int height) {
        }

        @Override
        public void repaint(Rectangle r) {
        }

        @Override
        public void repaint() {
        }
    }

    private static class TestTreeNodeEditor extends AbstractCellEditor implements TreeCellEditor {
        private static final long serialVersionUID = 1L;

        private TestTreeNodeRender lastObject;

        @Override
        public Object getCellEditorValue() {
            return lastObject;
        }

        @Override
        public Component getTreeCellEditorComponent(JTree tree, final Object value, boolean isSelected,
                boolean expanded, boolean leaf, int row) {

            lastObject = new TestTreeNodeRender();
            lastObject.getTreeCellRendererComponent(tree, value, true, expanded, leaf, row, false);
            lastObject.addItemListener(new ItemListener() {
                @Override
                public void itemStateChanged(ItemEvent itemEvent) {
                    ((TestTreeNode) value).setEnabled(((JCheckBox) itemEvent.getItem()).isSelected());
                    fireEditingStopped();
                }
            });

            return lastObject;
        }
    }

    private class TckRunDialog extends JDialog {
        private static final long serialVersionUID = 1L;

        private final TckTask task;

        public TckRunDialog() {
            super(owner, "TCK");

            createGUI();

            task = new TckTask(this, runner);
            task.execute();
        }

        private void createGUI() {
            setPreferredSize(WorkbenchScale.scaleDimension(new Dimension(500, 200)));
            setMinimumSize(WorkbenchScale.scaleDimension(new Dimension(500, 200)));

            setLayout(new BorderLayout());

            JPanel progressPanel = new JPanel();
            progressPanel.setLayout(new BoxLayout(progressPanel, BoxLayout.Y_AXIS));
            progressPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)));

            progressPanel.add(Box.createRigidArea(new Dimension(0, 10)));

            JLabel groupsLabel = new JLabel("Groups:");
            groupsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            progressPanel.add(groupsLabel);

            groupsProgressBar = new JProgressBar();
            groupsProgressBar.setMinimumSize(new Dimension(500, 30));
            groupsProgressBar.setPreferredSize(new Dimension(500, 30));
            groupsProgressBar.setMaximumSize(new Dimension(Short.MAX_VALUE, 30));
            groupsProgressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
            groupsProgressBar.setAlignmentY(Component.CENTER_ALIGNMENT);
            progressPanel.add(groupsProgressBar);

            progressPanel.add(Box.createRigidArea(new Dimension(0, 10)));

            JLabel testsLabel = new JLabel("Tests:");
            testsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            progressPanel.add(testsLabel);

            testsProgressBar = new JProgressBar();
            testsProgressBar.setMinimumSize(new Dimension(500, 30));
            testsProgressBar.setPreferredSize(new Dimension(500, 30));
            testsProgressBar.setMaximumSize(new Dimension(Short.MAX_VALUE, 30));
            testsProgressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
            testsProgressBar.setAlignmentY(Component.CENTER_ALIGNMENT);
            progressPanel.add(testsProgressBar);

            progressPanel.add(Box.createRigidArea(new Dimension(0, 10)));

            statusLabel = new JLabel();
            progressPanel.add(statusLabel);

            add(progressPanel, BorderLayout.CENTER);

            JButton cancelButton = new JButton("Cancel");
            cancelButton.setDefaultCapable(true);
            cancelButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    task.cancel(true);
                }
            });

            final JPanel cancelButtonPanel = new JPanel();
            cancelButtonPanel.setLayout(new BoxLayout(cancelButtonPanel, BoxLayout.PAGE_AXIS));
            cancelButtonPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(0, 3, 3, 3)));
            cancelButton.setMaximumSize(new Dimension(Short.MAX_VALUE, 30));
            cancelButtonPanel.add(cancelButton);

            add(cancelButtonPanel, BorderLayout.PAGE_END);

            getRootPane().setDefaultButton(cancelButton);

            setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            pack();
            setLocationRelativeTo(null);
            setVisible(true);
        }
    }

    private static class TckDialogRunner extends AbstractRunner {
        public TckDialogRunner(ClientModel model, TckDialog tckDialog) {
            Map<String, String> parameters = new HashMap<String, String>(model.getClientSession()
                    .getSessionParameters());
            parameters.put(SessionParameter.REPOSITORY_ID, model.getClientSession().getSession().getRepositoryInfo()
                    .getId());

            setParameters(parameters);
        }
    }

    private class DialogProgressMonitor implements CmisTestProgressMonitor {

        public DialogProgressMonitor(int numberOfGroups) {
            groupsProgressBar.setStringPainted(true);
            groupsProgressBar.setMinimum(0);
            groupsProgressBar.setMaximum(numberOfGroups);
            groupsProgressBar.setValue(0);
        }

        @Override
        public void startGroup(CmisTestGroup group) {
            groupsProgressBar.setString(group.getName());

            testsProgressBar.setStringPainted(true);
            testsProgressBar.setMinimum(0);
            testsProgressBar.setMaximum(group.getTests().size());
            testsProgressBar.setValue(0);
        }

        @Override
        public void endGroup(CmisTestGroup group) {
            groupsProgressBar.setString("");
            groupsProgressBar.setValue(groupsProgressBar.getValue() + 1);
        }

        @Override
        public void startTest(CmisTest test) {
            testsProgressBar.setString(test.getName());
        }

        @Override
        public void endTest(CmisTest test) {
            testsProgressBar.setString("");
            testsProgressBar.setValue(testsProgressBar.getValue() + 1);

            for (CmisTestResult tr : test.getResults()) {
                int x = status.get(tr.getStatus());
                status.put(tr.getStatus(), x + 1);
            }

            StringBuilder sb = new StringBuilder(128);
            int x;

            sb.append("<html>");

            x = status.get(CmisTestResultStatus.INFO);
            if (x > 0) {
                sb.append("<font color='#000000'>[Info: " + x + "]</font>  ");
            }

            x = status.get(CmisTestResultStatus.SKIPPED);
            if (x > 0) {
                sb.append("<font color='#444444'>[Skipped: " + x + "]</font>  ");
            }

            x = status.get(CmisTestResultStatus.OK);
            if (x > 0) {
                sb.append("<font color='#009900'>[Ok: " + x + "]</font>  ");
            }

            x = status.get(CmisTestResultStatus.WARNING);
            if (x > 0) {
                sb.append("<font color='#999900'>[Warning: " + x + "]</font>  ");
            }

            x = status.get(CmisTestResultStatus.FAILURE);
            if (x > 0) {
                sb.append("<font color='#996000'>[Failure: " + x + "]</font>  ");
            }

            x = status.get(CmisTestResultStatus.UNEXPECTED_EXCEPTION);
            if (x > 0) {
                sb.append("<font color='#990000'>[Exception: " + x + "]</font>  ");
            }

            sb.append("</html>");

            statusLabel.setText(sb.toString());
        }

        @Override
        public void message(String msg) {
        }
    }

    class TckTask extends SwingWorker<Void, Void> {
        private final JDialog dialog;
        private final TckDialogRunner runner;

        public TckTask(JDialog dialog, TckDialogRunner runner) {
            this.dialog = dialog;
            this.runner = runner;
        }

        @Override
        public Void doInBackground() {
            for (AbstractTckRunnerConfigurator configurator : TCK_RUNNER_SERVICE_LOADER) {
                configurator.beforeRun(runner);
            }

            try {
                runner.run(new DialogProgressMonitor(runner.getGroups().size()));
            } catch (InterruptedException ie) {
                runner.cancel();
            } catch (Exception e) {
                JOptionPane
                        .showMessageDialog(owner, "Error: " + e.getMessage(), "TCK Error", JOptionPane.ERROR_MESSAGE);
            }

            return null;
        }

        @Override
        public void done() {
            if (isCancelled()) {
                runner.cancel();
            }

            for (AbstractTckRunnerConfigurator configurator : TCK_RUNNER_SERVICE_LOADER) {
                configurator.afterRun(runner);
            }

            try {
                SwingReport report = new SwingReport(null, 700, 500);
                report.createReport(runner.getParameters(), runner.getGroups(), (Writer) null);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(owner, "Error: " + e.getMessage(), "Report Error",
                        JOptionPane.ERROR_MESSAGE);
            } finally {
                dialog.dispose();
            }
        }
    }
}

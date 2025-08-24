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
package org.apache.chemistry.opencmis.workbench.checks;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;

import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestReport;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.report.AbstractCmisTestReport;
import org.apache.chemistry.opencmis.tck.report.HtmlReport;
import org.apache.chemistry.opencmis.tck.report.JsonReport;
import org.apache.chemistry.opencmis.tck.report.TextReport;
import org.apache.chemistry.opencmis.tck.report.XmlReport;
import org.apache.chemistry.opencmis.workbench.ClientHelper;
import org.apache.chemistry.opencmis.workbench.WorkbenchScale;

/**
 * Swing Report.
 */
public class SwingReport extends AbstractCmisTestReport {

    private static final Icon GROUP_ICON = new ColorIcon(Color.GRAY);
    private static final Icon TEST_ICON = new ColorIcon(Color.BLUE);
    private static final Icon STATUS_OK_ICON = new ColorIcon(Color.GREEN);
    private static final Icon STATUS_WARNING_ICON = new ColorIcon(Color.YELLOW);
    private static final Icon STATUS_FAILURE_ICON = new ColorIcon(Color.RED);
    private static final Icon OTHER_ICON = new ColorIcon(Color.LIGHT_GRAY);

    private final Frame owner;
    private final int width;
    private final int height;
    private Map<String, String> parameters;
    private List<CmisTestGroup> groups;

    public SwingReport(Frame owner, int width, int height) {
        this.owner = owner;
        this.width = width;
        this.height = height;
    }

    @Override
    public void createReport(Map<String, String> parameters, List<CmisTestGroup> groups, Writer writer) {
        this.parameters = parameters;
        this.groups = groups;
        new SwingReportDialog();
    }

    /**
     * Report dialog.
     */
    private class SwingReportDialog extends JDialog {

        private static final long serialVersionUID = 1L;

        public SwingReportDialog() {
            super(owner, "Test Report");
            createGUI();
        }

        private void createGUI() {
            setIconImages(ClientHelper.getCmisIconImages());

            setPreferredSize(WorkbenchScale.scaleDimension(new Dimension(width, height)));
            setMinimumSize(WorkbenchScale.scaleDimension(new Dimension(width, height)));

            setLayout(new BorderLayout());

            DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Groups");

            for (CmisTestGroup group : groups) {
                if (!group.isEnabled()) {
                    continue;
                }

                DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(group);
                rootNode.add(groupNode);
                for (CmisTest test : group.getTests()) {
                    if (!test.isEnabled()) {
                        continue;
                    }

                    DefaultMutableTreeNode testNode = new DefaultMutableTreeNode(test);
                    groupNode.add(testNode);
                    populateResultBranch(testNode, test.getResults());
                }
            }

            JTree groupTree = new JTree(rootNode);
            groupTree.setRootVisible(false);
            groupTree.setCellRenderer(new ReportTreeCellRenderer());

            for (int i = 0; i < groupTree.getRowCount(); i++) {
                groupTree.expandRow(i);
            }

            add(new JScrollPane(groupTree), BorderLayout.CENTER);

            final JPanel reportPanel = new JPanel();
            reportPanel.add(new JLabel("Open report as "));
            final JComboBox<String> reportType = new JComboBox<String>(new String[] { "HTML", "Text", "XML", "JSON" });
            reportPanel.add(reportType);
            final JButton reportButton = new JButton("go");
            reportPanel.add(reportButton);

            reportButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    try {
                        // create report
                        File tempReportFile = null;
                        CmisTestReport report = null;

                        switch (reportType.getSelectedIndex()) {
                        case 1:
                            tempReportFile = File.createTempFile("cmistck", ".txt");
                            report = new TextReport();
                            break;
                        case 2:
                            tempReportFile = File.createTempFile("cmistck", ".xml");
                            report = new XmlReport();
                            break;
                        case 3:
                            tempReportFile = File.createTempFile("cmistck", ".json");
                            report = new JsonReport();
                            break;
                        default:
                            tempReportFile = File.createTempFile("cmistck", ".html");
                            report = new HtmlReport();
                            break;
                        }

                        tempReportFile.deleteOnExit();
                        report.createReport(parameters, groups, tempReportFile);

                        // show report
                        Desktop desktop = Desktop.getDesktop();
                        if (!desktop.isSupported(Desktop.Action.OPEN)) {
                            JOptionPane.showMessageDialog(owner, "Report: " + tempReportFile.getAbsolutePath(),
                                    "Report", JOptionPane.INFORMATION_MESSAGE);
                        } else {
                            desktop.open(tempReportFile);
                        }
                    } catch (Exception e) {
                        JOptionPane.showMessageDialog(owner, "Error: " + e.getMessage(), "Report Error",
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            });

            add(reportPanel, BorderLayout.PAGE_END);

            ClientHelper.installEscapeBinding(this, getRootPane(), true);

            setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            pack();
            setLocationRelativeTo(null);
            setVisible(true);
        }

        private void populateResultBranch(DefaultMutableTreeNode parent, List<CmisTestResult> results) {
            if (results == null) {
                return;
            }

            for (CmisTestResult result : results) {
                DefaultMutableTreeNode resultNode = new DefaultMutableTreeNode(result);
                parent.add(resultNode);
                populateResultBranch(resultNode, result.getChildren());
            }
        }

        private class ReportTreeCellRenderer extends DefaultTreeCellRenderer {

            private static final long serialVersionUID = 1L;

            public ReportTreeCellRenderer() {
            }

            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                    boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, "", sel, expanded, leaf, row, hasFocus);

                DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;

                if (node.getUserObject() instanceof CmisTestGroup) {
                    setText(((CmisTestGroup) node.getUserObject()).getName());
                    setIcon(GROUP_ICON);
                } else if (node.getUserObject() instanceof CmisTest) {
                    setText(((CmisTest) node.getUserObject()).getName());
                    setIcon(TEST_ICON);
                } else if (node.getUserObject() instanceof CmisTestResult) {
                    CmisTestResult result = (CmisTestResult) node.getUserObject();

                    String text = "<html><b>" + result.getStatus() + ": " + result.getMessage() + "</b>";
                    if ((result.getStackTrace() != null) && (result.getStackTrace().length > 0)) {
                        text += " (" + result.getStackTrace()[0].getFileName() + ":"
                                + result.getStackTrace()[0].getLineNumber() + ")";
                    }
                    setText(text);

                    switch (result.getStatus()) {
                    case OK:
                        setIcon(STATUS_OK_ICON);
                        break;
                    case WARNING:
                        setIcon(STATUS_WARNING_ICON);
                        break;
                    case FAILURE:
                    case UNEXPECTED_EXCEPTION:
                        setIcon(STATUS_FAILURE_ICON);
                        break;
                    default:
                        setIcon(OTHER_ICON);
                    }
                } else {
                    setText(value.toString());
                    setIcon(OTHER_ICON);
                }

                setVerticalTextPosition(SwingConstants.TOP);

                return this;
            }
        }
    }

    private static class ColorIcon implements Icon {

        private final Color color;

        public ColorIcon(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(color);
            g.fillRect(0, 0, getIconWidth(), getIconHeight());
        }

        @Override
        public int getIconWidth() {
            return WorkbenchScale.scaleInt(8);
        }

        @Override
        public int getIconHeight() {
            return WorkbenchScale.scaleInt(18);
        }
    }
}

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

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;

import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.Spring;
import javax.swing.SpringLayout;
import javax.swing.SwingConstants;
import javax.swing.ToolTipManager;

import org.apache.chemistry.opencmis.workbench.icons.HelpIcon;

/**
 * Convenience methods for spring layout tabs.
 */
public abstract class AbstractSpringLoginTab extends AbstractLoginTab {

    private static final long serialVersionUID = 1L;

    protected static final Icon HELP_ICON = new HelpIcon(WorkbenchScale.scaleInt(12), WorkbenchScale.scaleInt(12));

    protected JTextField createTextField(Container pane, String label) {
        return createTextField(pane, label, null);
    }

    protected JTextField createTextField(Container pane, String label, String help) {
        JTextField textField = new JTextField(60);
        JLabel textLabel = new JLabel(label, SwingConstants.TRAILING);
        textLabel.setLabelFor(textField);

        pane.add(textLabel);
        pane.add(createHelp(help));
        pane.add(textField);

        return textField;
    }

    protected JFormattedTextField createIntegerField(Container pane, String label) {
        return createIntegerField(pane, label, null);
    }

    protected JFormattedTextField createIntegerField(Container pane, String label, String help) {
        NumberFormat format = NumberFormat.getIntegerInstance();
        JFormattedTextField intField = new JFormattedTextField(format);
        JLabel intLabel = new JLabel(label, SwingConstants.TRAILING);
        intLabel.setLabelFor(intField);

        pane.add(intLabel);
        pane.add(createHelp(help));
        pane.add(intField);

        return intField;
    }

    protected JPasswordField createPasswordField(Container pane, String label) {
        return createPasswordField(pane, label, null);
    }

    protected JPasswordField createPasswordField(Container pane, String label, String help) {
        JPasswordField textField = new JPasswordField(60);
        JLabel textLabel = new JLabel(label, SwingConstants.TRAILING);
        textLabel.setLabelFor(textField);

        pane.add(textLabel);
        pane.add(createHelp(help));
        pane.add(textField);

        return textField;
    }

    protected JPanel createCheckBoxPanel(Container pane, String label, String help, JCheckBox... buttons) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel panelLabel = new JLabel(label, SwingConstants.TRAILING);
        panelLabel.setLabelFor(panel);

        for (JCheckBox button : buttons) {
            panel.add(button);
            if (button != buttons[buttons.length - 1]) {
                panel.add(new JPanel()).setMinimumSize(new Dimension(10, 10));
            }
        }

        pane.add(panelLabel);
        pane.add(createHelp(help));
        pane.add(panel);

        return panel;
    }

    protected JComponent createHelp(String help) {
        if (help == null) {
            return new JLabel("");
        } else {
            JLabel label = new JLabel(HELP_ICON);
            label.setToolTipText(help);

            label.addMouseListener(new MouseAdapter() {
                private final int defaultDismissDelay = ToolTipManager.sharedInstance().getDismissDelay();
                private static final int DISMIASS_DELAY = 120 * 1000;

                @Override
                public void mouseEntered(MouseEvent me) {
                    ToolTipManager.sharedInstance().setDismissDelay(DISMIASS_DELAY);
                }

                @Override
                public void mouseExited(MouseEvent me) {
                    ToolTipManager.sharedInstance().setDismissDelay(defaultDismissDelay);
                }
            });

            return label;
        }
    }

    private SpringLayout.Constraints getConstraintsForCell(int row, int col, Container parent, int cols) {
        SpringLayout layout = (SpringLayout) parent.getLayout();
        Component c = parent.getComponent(row * cols + col);
        return layout.getConstraints(c);
    }

    protected void makeCompactGrid(int rows) {
        makeCompactGrid(this, rows, 3, 5, 10, 5, 5);
    }

    protected void makeCompactGrid(Container parent, int rows, int cols, int initialX, int initialY, int xPad, int yPad) {
        SpringLayout layout = (SpringLayout) parent.getLayout();

        Spring x = Spring.constant(initialX);
        for (int c = 0; c < cols; c++) {
            Spring width = Spring.constant(0);
            for (int r = 0; r < rows; r++) {
                width = Spring.max(width, getConstraintsForCell(r, c, parent, cols).getWidth());
            }
            for (int r = 0; r < rows; r++) {
                SpringLayout.Constraints constraints = getConstraintsForCell(r, c, parent, cols);
                constraints.setX(x);
                constraints.setWidth(width);
            }
            x = Spring.sum(x, Spring.sum(width, Spring.constant(xPad)));
        }

        Spring y = Spring.constant(initialY);
        for (int r = 0; r < rows; r++) {
            Spring height = Spring.constant(0);
            for (int c = 0; c < cols; c++) {
                height = Spring.max(height, getConstraintsForCell(r, c, parent, cols).getHeight());
            }
            for (int c = 0; c < cols; c++) {
                SpringLayout.Constraints constraints = getConstraintsForCell(r, c, parent, cols);
                constraints.setY(y);
                constraints.setHeight(height);
            }
            y = Spring.sum(y, Spring.sum(height, Spring.constant(yPad)));
        }

        layout.getConstraints(parent).setConstraint(SpringLayout.EAST, x);
        layout.getConstraints(parent).setConstraint(SpringLayout.NORTH, y);
        parent.setPreferredSize(new Dimension(x.getPreferredValue(), y.getPreferredValue()));
    }
}

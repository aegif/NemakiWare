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
package org.apache.chemistry.opencmis.workbench.worker;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.NumberFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import org.apache.chemistry.opencmis.workbench.ClientHelper;
import org.apache.chemistry.opencmis.workbench.WorkbenchScale;

public abstract class WorkbenchWorker<T> extends SwingWorker<T, Long> {

    private Window parent;
    private WorkbenchWorkerDialog dialog;
    private long progessMax = -1;

    public WorkbenchWorker() {
        this.parent = null;
    }

    public WorkbenchWorker(Window parent) {
        this.parent = parent;
    }

    public WorkbenchWorker(Component comp) {
        this.parent = (comp == null ? null : SwingUtilities.getWindowAncestor(comp));
    }

    protected Window getParent() {
        return parent;
    }

    protected abstract String getTitle();

    protected abstract String getMessage();

    protected abstract boolean hasDialog();

    protected int getDialogDelay() {
        return 1000;
    }

    public void executeTask() {
        try {
            disableParent();
            if (hasDialog()) {
                dialog = new WorkbenchWorkerDialog(parent, getTitle(), progessMax);
            }

            execute();
        } catch (Exception e) {
            showError(e);
        }
    }

    protected void disableParent() {
        if (parent != null) {
            parent.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        }
    }

    protected void enableParent() {
        if (parent != null) {
            parent.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        }
    }

    protected void setProgressMax(long max) {
        progessMax = max;
        if (dialog != null) {
            dialog.setProgressMax(max);
        }
    }

    @Override
    protected void process(List<Long> chunks) {
        if (dialog != null) {
            dialog.setProgressValue(chunks.get(chunks.size() - 1));
        }
    }

    protected void showError(Throwable t) {
        ClientHelper.showError(parent, t);
    }

    @Override
    protected void done() {
        try {
            finializeTask();
            enableParent();

            if (dialog != null) {
                dialog.setVisible(false);
                dialog.dispose();
                dialog = null;
            }

            if (!isCancelled()) {
                // throw exception, if any
                get();
            }
        } catch (ExecutionException ee) {
            showError(ee.getCause());
        } catch (Exception e) {
            showError(e);
        }
    }

    protected abstract void finializeTask();

    private class WorkbenchWorkerDialog extends JDialog {

        private static final long serialVersionUID = 1L;

        private JProgressBar progressBar;
        private long max;

        public WorkbenchWorkerDialog(Window parent, String title, long progressMax) {
            super(parent, title);
            createGUI();
            setProgressMax(progressMax);
        }

        private void createGUI() {
            setPreferredSize(WorkbenchScale.scaleDimension(new Dimension(500, 150)));
            setMinimumSize(WorkbenchScale.scaleDimension(new Dimension(500, 150)));

            JPanel panel = new JPanel();
            panel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)));
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

            panel.add(Box.createVerticalGlue());

            JLabel messageLabel = new JLabel(getMessage());
            messageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(messageLabel);

            panel.add(Box.createVerticalGlue());

            progressBar = new JProgressBar();
            progressBar.setMinimumSize(new Dimension(500, 30));
            progressBar.setPreferredSize(new Dimension(500, 30));
            progressBar.setMaximumSize(new Dimension(Short.MAX_VALUE, 30));
            progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
            progressBar.setAlignmentY(Component.CENTER_ALIGNMENT);
            progressBar.setString("");
            progressBar.setStringPainted(true);
            progressBar.setMinimum(0);
            progressBar.setIndeterminate(true);

            panel.add(progressBar);

            panel.add(Box.createVerticalGlue());

            JButton cancelButton = new JButton("Cancel");
            cancelButton.setDefaultCapable(true);
            cancelButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            cancelButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    WorkbenchWorker.this.cancel(true);
                }
            });

            panel.add(cancelButton);

            panel.add(Box.createVerticalGlue());

            add(panel);

            getRootPane().setDefaultButton(cancelButton);

            setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            pack();
            setLocationRelativeTo(null);

            final Timer timer = new Timer(getDialogDelay(), null);
            timer.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (!isDone() && !isCancelled()) {
                        setVisible(true);
                    }
                    timer.stop();
                }
            });
            timer.setRepeats(false);
            timer.start();
        }

        public void setProgressMax(long max) {
            this.max = max;
            if (max < 0 || max >= Integer.MAX_VALUE) {
                progressBar.setIndeterminate(true);
            } else {
                progressBar.setIndeterminate(false);
                progressBar.setMaximum((int) max);
            }
        }

        public void setProgressValue(long value) {
            if (!progressBar.isIndeterminate()) {
                progressBar.setValue((int) value);
            }

            NumberFormat format = NumberFormat.getInstance();
            progressBar.setString(format.format(value) + " / " + format.format(max));
        }
    }
}

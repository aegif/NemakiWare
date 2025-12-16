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
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.WindowConstants;

import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;

public class ExceptionDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final Throwable throwable;

    public ExceptionDialog(Frame owner, Throwable throwable) {
        super(owner, "Exception", true);
        this.throwable = throwable;

        createGUI();
    }

    public ExceptionDialog(Dialog owner, Throwable throwable) {
        super(owner, "Exception", true);
        this.throwable = throwable;

        createGUI();
    }

    private void createGUI() {
        setMinimumSize(WorkbenchScale.scaleDimension(new Dimension(600, 150)));
        setPreferredSize(WorkbenchScale.scaleDimension(new Dimension(600, 200)));

        setLayout(new BorderLayout());

        // exception name
        String exceptionName;
        if (throwable instanceof CmisBaseException) {
            exceptionName = "CMIS Exception: <em>" + ((CmisBaseException) throwable).getExceptionName() + "</em>";
        } else {
            exceptionName = "Exception: <em>" + throwable.getClass().getSimpleName() + "</em>";
        }

        StringBuilder exceptionText = new StringBuilder(1024);
        exceptionText.append("<h2><font color=\"red\">" + exceptionName + "</font><br>");
        ClientHelper.encodeHtml(exceptionText, throwable.getMessage());
        exceptionText.append("</h2>");
        if (throwable.getCause() != null) {
            exceptionText.append("<h3><font color=\"red\">Cause: <em>" + throwable.getCause().getClass().getSimpleName()
                    + "</em></font><br>");
            ClientHelper.encodeHtml(exceptionText, throwable.getCause().getMessage());
            exceptionText.append("</h3>");
        }
        if (throwable instanceof CmisBaseException) {
            Map<String, String> additionalData = ((CmisBaseException) throwable).getAdditionalData();
            if (additionalData != null && !additionalData.isEmpty()) {
                exceptionText.append("<hr><br><b>Additional data:</b><br><table>");
                for (Map.Entry<String, String> e : additionalData.entrySet()) {
                    exceptionText.append("<tr><td>");
                    ClientHelper.encodeHtml(exceptionText, e.getKey());
                    exceptionText.append(":</td><td>");
                    ClientHelper.encodeHtml(exceptionText, e.getValue());
                    exceptionText.append("</td></tr>");
                }
                exceptionText.append("</table>");
            }

            String errorContent = ((CmisBaseException) throwable).getErrorContent();
            if (errorContent != null && errorContent.length() > 0) {
                exceptionText.append("<hr><br><b>Error Content:</b><br>");
                ClientHelper.encodeHtml(exceptionText, errorContent);
                setPreferredSize(new Dimension(getPreferredSize().width, WorkbenchScale.scaleInt(250)));
            }
        }

        // exception panel
        JPanel exceptionPanel = new JPanel();
        exceptionPanel.setLayout(new BoxLayout(exceptionPanel, BoxLayout.PAGE_AXIS));
        exceptionPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        add(exceptionPanel, BorderLayout.CENTER);

        JEditorPane exceptionTextPane = new JEditorPane("text/html", exceptionText.toString());
        exceptionTextPane.setEditable(false);
        exceptionTextPane.setCaretPosition(0);

        exceptionPanel.add(new JScrollPane(exceptionTextPane, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED));

        // close button
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.PAGE_AXIS));
        buttonPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5)));
        add(buttonPanel, BorderLayout.PAGE_END);

        JButton closeButton = new JButton("Close");
        closeButton.setPreferredSize(new Dimension(Short.MAX_VALUE, WorkbenchScale.scaleInt(30)));
        closeButton.setMaximumSize(new Dimension(Short.MAX_VALUE, Short.MAX_VALUE));
        closeButton.setAlignmentX(Component.CENTER_ALIGNMENT);

        closeButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ExceptionDialog.this.dispose();
            }
        });

        buttonPanel.add(closeButton);

        getRootPane().setDefaultButton(closeButton);

        ClientHelper.installEscapeBinding(this, getRootPane(), true);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(getOwner());

        setVisible(true);
    }
}

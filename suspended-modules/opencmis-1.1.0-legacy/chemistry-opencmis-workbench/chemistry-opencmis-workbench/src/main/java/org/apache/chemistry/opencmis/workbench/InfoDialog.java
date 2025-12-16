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

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.TreeSet;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.workbench.icons.CmisLogoIcon;

public class InfoDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    public InfoDialog(Frame owner) {
        super(owner, "Info", true);
        createGUI();
    }

    private void createGUI() {
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setPreferredSize(new Dimension((int) (screenSize.getWidth() / 2), (int) (screenSize.getHeight() / 2)));
        setMinimumSize(new Dimension(600, 400));

        setLayout(new BoxLayout(getContentPane(), BoxLayout.PAGE_AXIS));

        JPanel topPanel = new JPanel(new FlowLayout());

        JLabel cmisLogo = new JLabel(new CmisLogoIcon(128, 128));
        topPanel.add(cmisLogo);

        Font labelFont = UIManager.getFont("Label.font");
        Font titleFont = labelFont.deriveFont(Font.BOLD, labelFont.getSize2D() * 2f);

        JLabel titleLabel = new JLabel("CMIS Workbench");
        titleLabel.setFont(titleFont);
        topPanel.add(titleLabel);

        add(topPanel);

        StringBuilder readme = new StringBuilder(1024);

        readme.append(loadText("/META-INF/README-cmis-workbench.txt", "CMIS Workbench"));
        readme.append("\n---------------------------------------------------------\n");

        readme.append("\nCurrent System Properties:\n\n");

        Properties sysProps = System.getProperties();
        for (Object key : new TreeSet<Object>(sysProps.keySet())) {
            readme.append(key).append(" = ").append(sysProps.get(key)).append('\n');
        }

        readme.append("\n---------------------------------------------------------\n");
        readme.append(loadText("/META-INF/build-timestamp.txt", ""));

        JTextArea ta = new JTextArea(readme.toString());
        ta.setEditable(false);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, ta.getFont().getSize()));
        JScrollPane readmePane = new JScrollPane(ta);
        readmePane.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5)));

        add(readmePane);

        ClientHelper.installEscapeBinding(this, getRootPane(), false);

        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
    }

    public void showDialog() {
        setVisible(true);
    }

    public void hideDialog() {
        setVisible(false);
    }

    private String loadText(String file, String defaultText) {
        InputStream stream = getClass().getResourceAsStream(file);
        if (stream != null) {
            try {
                return IOUtils.readAllLines(stream, 10000);
            } catch (IOException e) {
                return defaultText;
            }
        }

        return defaultText;
    }
}

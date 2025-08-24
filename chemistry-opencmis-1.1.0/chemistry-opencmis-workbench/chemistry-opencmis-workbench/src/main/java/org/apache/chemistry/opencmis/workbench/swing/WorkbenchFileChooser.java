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
package org.apache.chemistry.opencmis.workbench.swing;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.io.File;
import java.util.prefs.Preferences;

import javax.swing.JFileChooser;

public class WorkbenchFileChooser extends JFileChooser {

    private static final long serialVersionUID = 1L;

    private static final String PREFS_DIRECTORY = "directory";

    private final Preferences prefs = Preferences.userNodeForPackage(this.getClass());

    public WorkbenchFileChooser() {
        super();
        load();

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setPreferredSize(new Dimension((int) (screenSize.getWidth() / 2), (int) (screenSize.getHeight() / 2)));
    }

    @Override
    public int showDialog(Component parent, String approveButtonText) throws HeadlessException {
        int state = super.showDialog(parent, approveButtonText);

        if (state == JFileChooser.APPROVE_OPTION) {
            save();
        }

        return state;
    }

    private synchronized void load() {
        String fileChooserDirectory = prefs.get(PREFS_DIRECTORY, null);
        if (fileChooserDirectory != null) {
            setCurrentDirectory(new File(fileChooserDirectory));
        }
    }

    private synchronized void save() {
        File selectedFile = getSelectedFile();
        if (selectedFile != null) {
            prefs.put(PREFS_DIRECTORY, selectedFile.getParent());
        }
    }
}

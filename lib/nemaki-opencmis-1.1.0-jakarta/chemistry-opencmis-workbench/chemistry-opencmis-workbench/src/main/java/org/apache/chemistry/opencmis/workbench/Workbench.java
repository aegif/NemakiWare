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

import java.net.Authenticator;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UIManager.LookAndFeelInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Workbench {

    private static final Logger LOG = LoggerFactory.getLogger(Workbench.class);

    public Workbench() {
        // set Mac OS X name
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "CMIS Workbench");

        // turn off existing Authenticators (-> Web Start)
        Authenticator.setDefault(null);

        // set up Swing
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean nimbus = false;

                        for (LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                            if ("Nimbus".equals(info.getName())) {
                                if (WorkbenchScale.isScaling()) {
                                    UIManager.setLookAndFeel(new WorkbenchScale.ScaledNimbusLookAndFeel());
                                } else {
                                    UIManager.setLookAndFeel(info.getClassName());
                                }
                                nimbus = true;
                                break;
                            }
                        }

                        if (!nimbus) {
                            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                        }
                    } catch (Exception e) {
                        LOG.warn("Nimbus not available: {}", e.getMessage(), e);
                    }
                }
            });
        } catch (Exception e) {
            LOG.warn("Nimbus not available: {}", e.getMessage(), e);
        }

        JFrame.setDefaultLookAndFeelDecorated(true);
        JDialog.setDefaultLookAndFeelDecorated(true);
        ClientHelper.installKeyBindings();

        // show client frame
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new ClientFrame();
            }
        });
    }

    public static void main(String[] args) {
        new Workbench();
    }
}

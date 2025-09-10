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

import java.awt.Container;
import java.awt.Dimension;
import java.util.Locale;
import java.util.Map;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SpringLayout;
import javax.swing.SwingConstants;

import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.workbench.model.ClientSession;

public class BasicLoginTab extends AbstractSpringLoginTab {

    private static final long serialVersionUID = 1L;

    public static final String SYSPROP_URL = ClientSession.WORKBENCH_PREFIX + "url";
    public static final String SYSPROP_BINDING = ClientSession.WORKBENCH_PREFIX + "binding";
    public static final String SYSPROP_AUTHENTICATION = ClientSession.WORKBENCH_PREFIX + "authentication";
    public static final String SYSPROP_COMPRESSION = ClientSession.WORKBENCH_PREFIX + "compression";
    public static final String SYSPROP_CLIENTCOMPRESSION = ClientSession.WORKBENCH_PREFIX + "clientcompression";
    public static final String SYSPROP_COOKIES = ClientSession.WORKBENCH_PREFIX + "cookies";
    public static final String SYSPROP_CONN_TIMEOUT = ClientSession.WORKBENCH_PREFIX + "connecttimeout";
    public static final String SYSPROP_READ_TIMEOUT = ClientSession.WORKBENCH_PREFIX + "readtimeout";
    public static final String SYSPROP_USER = ClientSession.WORKBENCH_PREFIX + "user";
    public static final String SYSPROP_PASSWORD = ClientSession.WORKBENCH_PREFIX + "password";
    public static final String SYSPROP_CSRF_HEADER = ClientSession.WORKBENCH_PREFIX + "csrfheader";
    public static final String SYSPROP_LANGUAGE = ClientSession.WORKBENCH_PREFIX + "language";

    private JTextField urlField;
    private JRadioButton bindingAtomButton;
    private JRadioButton bindingWebServicesButton;
    private JRadioButton bindingBrowserButton;
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JRadioButton authenticationNoneButton;
    private JRadioButton authenticationStandardButton;
    private JRadioButton authenticationNTLMButton;
    private JRadioButton authenticationOAuthButton;
    private JRadioButton authenticationCertButton;
    private JRadioButton compressionOnButton;
    private JRadioButton compressionOffButton;
    private JRadioButton clientCompressionOnButton;
    private JRadioButton clientCompressionOffButton;
    private JRadioButton cookiesOnButton;
    private JRadioButton cookiesOffButton;
    private JTextField csrfHeaderField;
    private JTextField languageField;
    private JFormattedTextField connectTimeoutField;
    private JFormattedTextField readTimeoutField;

    public BasicLoginTab() {
        super();
        createGUI();
    }

    private void createGUI() {
        setLayout(new SpringLayout());

        urlField = createTextField(this, "URL:",
                "<html>Enter the URL of the CMIS endpoint. It depends on the selected binding. ");
        urlField.setText(System.getProperty(SYSPROP_URL, "").trim());

        createBindingButtons(this);

        usernameField = createTextField(this, "Username:",
                "<html>Enter the user name.<br>(Or bearer token for OAuth authentication or key file path for client certificate authentication.)");
        usernameField.setText(System.getProperty(SYSPROP_USER, ""));

        passwordField = createPasswordField(this, "Password:", "Enter the users password.");
        passwordField.setText(System.getProperty(SYSPROP_PASSWORD, ""));

        createAuthenticationButtons(this);

        createCompressionButtons(this);

        createClientCompressionButtons(this);

        createCookieButtons(this);

        csrfHeaderField = createTextField(this, "CSRF Header:", "<html>Enter <b>CSRF header</b> name.<br>"
                + "If the server needs a CSRF header, enter the name of the header, otherwise leave this field blank.");
        csrfHeaderField.setText(System.getProperty(SYSPROP_CSRF_HEADER, ""));

        languageField = createTextField(this, "Language:", "<html>Enter a <b>ISO 639 language</b> code.<br>"
                + "The language code is sent to the server but it may not be used by the server.");
        languageField.setText(System.getProperty(SYSPROP_LANGUAGE, Locale.getDefault().getLanguage()));

        connectTimeoutField = createIntegerField(this, "Connect timeout (secs):",
                "<html>Enter the <b>connect timeout</b> in seconds.<br>This is the time the client waits to connect to the server.");
        try {
            connectTimeoutField.setValue(Long.parseLong(System.getProperty(SYSPROP_CONN_TIMEOUT, "30")));
        } catch (NumberFormatException e) {
            connectTimeoutField.setValue(30);
        }

        readTimeoutField = createIntegerField(this, "Read timeout (secs):",
                "<html>Enter the <b>read timeout</b> in seconds.<br>This is the time the client waits for a response from the server.");
        try {
            readTimeoutField.setValue(Long.parseLong(System.getProperty(SYSPROP_READ_TIMEOUT, "600")));
        } catch (NumberFormatException e) {
            readTimeoutField.setValue(600);
        }

        makeCompactGrid(12);
    }

    private void createBindingButtons(Container pane) {
        JPanel bindingContainer = new JPanel();
        bindingContainer.setLayout(new BoxLayout(bindingContainer, BoxLayout.LINE_AXIS));
        char bc = System.getProperty(SYSPROP_BINDING, "atom").toLowerCase(Locale.ENGLISH).charAt(0);
        boolean atom = (bc == 'a');
        boolean ws = (bc == 'w');
        boolean browser = (bc == 'b');
        bindingAtomButton = new JRadioButton("AtomPub", atom);
        bindingWebServicesButton = new JRadioButton("Web Services", ws);
        bindingBrowserButton = new JRadioButton("Browser", browser);
        ButtonGroup bindingGroup = new ButtonGroup();
        bindingGroup.add(bindingAtomButton);
        bindingGroup.add(bindingWebServicesButton);
        bindingGroup.add(bindingBrowserButton);
        bindingContainer.add(bindingAtomButton);
        bindingContainer.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(10, 0))));
        bindingContainer.add(bindingWebServicesButton);
        bindingContainer.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(10, 0))));
        bindingContainer.add(bindingBrowserButton);
        JLabel bindingLabel = new JLabel("Binding:", SwingConstants.TRAILING);

        pane.add(bindingLabel);
        pane.add(createHelp("<html>Select the CMIS binding.<br>"
                + "The <b>Browser binding</b> is the fastest binding and should be used for CMIS 1.1 repositories.<br>"
                + "The <b>AtomPub binding</b> is the second fastest binding and should be used for CMIS 1.0 repositories.<br>"
                + "The <b>Web Services binding</b> is the last restort and should be avoided, if possible."));
        pane.add(bindingContainer);
    }

    private void createAuthenticationButtons(Container pane) {
        JPanel authenticationContainer = new JPanel();
        authenticationContainer.setLayout(new BoxLayout(authenticationContainer, BoxLayout.LINE_AXIS));
        boolean standard = (System.getProperty(SYSPROP_AUTHENTICATION, "standard").toLowerCase(Locale.ENGLISH)
                .equals("standard"));
        boolean ntlm = (System.getProperty(SYSPROP_AUTHENTICATION, "").toLowerCase(Locale.ENGLISH).equals("ntlm"));
        boolean oauth = (System.getProperty(SYSPROP_AUTHENTICATION, "").toLowerCase(Locale.ENGLISH).equals("oauth"));
        boolean cert = (System.getProperty(SYSPROP_AUTHENTICATION, "").toLowerCase(Locale.ENGLISH).equals("cert"));
        boolean none = !standard && !ntlm && !oauth && !cert;
        authenticationNoneButton = new JRadioButton("None", none);
        authenticationStandardButton = new JRadioButton("Standard", standard);
        authenticationNTLMButton = new JRadioButton("NTLM", ntlm);
        authenticationOAuthButton = new JRadioButton("OAuth 2.0 (Bearer Token)", oauth);
        authenticationCertButton = new JRadioButton("Client Certificate", cert);
        ButtonGroup authenticationGroup = new ButtonGroup();
        authenticationGroup.add(authenticationNoneButton);
        authenticationGroup.add(authenticationStandardButton);
        authenticationGroup.add(authenticationNTLMButton);
        authenticationGroup.add(authenticationOAuthButton);
        authenticationGroup.add(authenticationCertButton);
        authenticationContainer.add(authenticationNoneButton);
        authenticationContainer.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(10, 0))));
        authenticationContainer.add(authenticationStandardButton);
        authenticationContainer.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(10, 0))));
        authenticationContainer.add(authenticationNTLMButton);
        authenticationContainer.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(10, 0))));
        authenticationContainer.add(authenticationOAuthButton);
        authenticationContainer.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(10, 0))));
        authenticationContainer.add(authenticationCertButton);
        JLabel authenticatioLabel = new JLabel("Authentication:", SwingConstants.TRAILING);

        pane.add(authenticatioLabel);
        pane.add(createHelp("<html>Select the authentication method.<br>"
                + "The <b>Standard authentication</b> is Basic Auth and should work with most repositories.<br>"
                + "The <b>NTLM authentication</b> should be used with caution! It's very likely that some CMIS operations will fail.<br>"
                + "The <b>OAuth authentication</b> requires a bearer token in the username field. The token will not be refreshed when it expires. "
                + "Use the OAuthAuthenticationProvider for full OAuth support.<br>"
                + "The <b>Client Certificate authentication</b> requires a JKS key file path the username field and the passphrase in the password field."));
        pane.add(authenticationContainer);
    }

    private void createCompressionButtons(Container pane) {
        JPanel compressionContainer = new JPanel();
        compressionContainer.setLayout(new BoxLayout(compressionContainer, BoxLayout.LINE_AXIS));
        boolean compression = !(System.getProperty(SYSPROP_COMPRESSION, "on").equalsIgnoreCase("off"));
        compressionOnButton = new JRadioButton("On", compression);
        compressionOffButton = new JRadioButton("Off", !compression);
        ButtonGroup compressionGroup = new ButtonGroup();
        compressionGroup.add(compressionOnButton);
        compressionGroup.add(compressionOffButton);
        compressionContainer.add(compressionOnButton);
        compressionContainer.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(10, 0))));
        compressionContainer.add(compressionOffButton);
        JLabel compressionLabel = new JLabel("Compression:", SwingConstants.TRAILING);

        pane.add(compressionLabel);
        pane.add(createHelp("<html>Turn <b>server compression</b> on or off.<br>"
                + "It is save to turn server compression always on. The client will still work if the server doesn't support it."));
        pane.add(compressionContainer);
    }

    private void createClientCompressionButtons(Container pane) {
        JPanel clientCompressionContainer = new JPanel();
        clientCompressionContainer.setLayout(new BoxLayout(clientCompressionContainer, BoxLayout.LINE_AXIS));
        boolean clientCompression = (System.getProperty(SYSPROP_CLIENTCOMPRESSION, "off").equalsIgnoreCase("on"));
        clientCompressionOnButton = new JRadioButton("On", clientCompression);
        clientCompressionOffButton = new JRadioButton("Off", !clientCompression);
        ButtonGroup clientCompressionGroup = new ButtonGroup();
        clientCompressionGroup.add(clientCompressionOnButton);
        clientCompressionGroup.add(clientCompressionOffButton);
        clientCompressionContainer.add(clientCompressionOnButton);
        clientCompressionContainer.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(10, 0))));
        clientCompressionContainer.add(clientCompressionOffButton);
        JLabel clientCompressionLabel = new JLabel("Client Compression:", SwingConstants.TRAILING);

        pane.add(clientCompressionLabel);
        pane.add(createHelp("<html>Turn <b>client compression</b> on or off.<br>"
                + "If the server doesn't support client compression, the communication will fail."));
        pane.add(clientCompressionContainer);
    }

    private void createCookieButtons(Container pane) {
        JPanel cookiesContainer = new JPanel();
        cookiesContainer.setLayout(new BoxLayout(cookiesContainer, BoxLayout.LINE_AXIS));
        boolean cookies = (System.getProperty(SYSPROP_COOKIES, "on").equalsIgnoreCase("on"));
        cookiesOnButton = new JRadioButton("On", cookies);
        cookiesOffButton = new JRadioButton("Off", !cookies);
        ButtonGroup cookiesGroup = new ButtonGroup();
        cookiesGroup.add(cookiesOnButton);
        cookiesGroup.add(cookiesOffButton);
        cookiesContainer.add(cookiesOnButton);
        cookiesContainer.add(Box.createRigidArea(WorkbenchScale.scaleDimension(new Dimension(10, 0))));
        cookiesContainer.add(cookiesOffButton);
        JLabel cookiesLabel = new JLabel("Cookies:", SwingConstants.TRAILING);

        pane.add(cookiesLabel);
        pane.add(createHelp("<html>Turn <b>cookies</b> on or off.<br>"
                + "It is recommended to turn cookies always on. Nothing bad happens if the server doesn't need or support cookies."));
        pane.add(cookiesContainer);
    }

    @Override
    public String getTabTitle() {
        return "Basic";
    }

    @Override
    public Map<String, String> getSessionParameters() {
        String url = urlField.getText().trim();

        BindingType binding = BindingType.ATOMPUB;
        if (bindingWebServicesButton.isSelected()) {
            binding = BindingType.WEBSERVICES;
        } else if (bindingBrowserButton.isSelected()) {
            binding = BindingType.BROWSER;
        }

        String username = usernameField.getText();
        String password = new String(passwordField.getPassword());

        ClientSession.Authentication authentication = ClientSession.Authentication.NONE;
        if (authenticationStandardButton.isSelected()) {
            authentication = ClientSession.Authentication.STANDARD;
        } else if (authenticationNTLMButton.isSelected()) {
            authentication = ClientSession.Authentication.NTLM;
        } else if (authenticationOAuthButton.isSelected()) {
            authentication = ClientSession.Authentication.OAUTH_BEARER;
        } else if (authenticationCertButton.isSelected()) {
            authentication = ClientSession.Authentication.CLIENT_CERT;
        }

        Locale locale = null;
        String language = languageField.getText().trim();
        if (language.length() > 1 && language.charAt(0) != '-' && language.charAt(0) != '_') {
            int sep1 = language.indexOf('-');
            int sep2 = language.indexOf('_');

            if (sep1 > 0) {
                locale = new Locale(language.substring(0, sep1), language.substring(sep1 + 1));
            } else if (sep2 > 0) {
                locale = new Locale(language.substring(0, sep2), language.substring(sep2 + 1));
            } else {
                locale = new Locale(language);
            }
        }

        long connectTimeout = 0;
        if (connectTimeoutField.getValue() instanceof Number) {
            connectTimeout = ((Number) connectTimeoutField.getValue()).longValue() * 1000;
            if (connectTimeout < 0) {
                connectTimeoutField.setValue(0);
                connectTimeout = 0;
            }
        }

        long readTimeout = 0;
        if (readTimeoutField.getValue() instanceof Number) {
            readTimeout = ((Number) readTimeoutField.getValue()).longValue() * 1000;
            if (readTimeout < 0) {
                readTimeoutField.setValue(0);
                readTimeout = 0;
            }
        }

        return ClientSession.createSessionParameters(url, binding, username, password, authentication,
                compressionOnButton.isSelected(), clientCompressionOnButton.isSelected(), cookiesOnButton.isSelected(),
                csrfHeaderField.getText(), locale, connectTimeout, readTimeout);
    }

    @Override
    public boolean transferSessionParametersToExpertTab() {
        return true;
    }
}

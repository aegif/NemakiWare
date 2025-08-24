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
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

import javax.net.ssl.SSLException;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.WindowConstants;
import javax.xml.stream.XMLStreamException;

import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisProxyAuthenticationException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisTooManyRequestsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUnauthorizedException;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParseException;
import org.xml.sax.SAXParseException;

public class ConnectionErrorDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    public static final String HTTP_PROXY_HOST = "http.proxyHost";
    public static final String HTTP_PROXY_PORT = "http.proxyPort";
    public static final String HTTPS_PROXY_HOST = "https.proxyHost";
    public static final String HTTPS_PROXY_PORT = "https.proxyPort";
    public static final String HTTP_NON_PROXY_HOSTS = "http.nonProxyHosts";

    private final Exception exception;

    public ConnectionErrorDialog(JDialog owner, Exception exception) {
        super(owner, "Connection Error", true);
        this.exception = exception;

        ClientHelper.logError(exception);

        createGUI();
    }

    private void createGUI() {
        setMinimumSize(new Dimension(WorkbenchScale.scaleInt(600), WorkbenchScale.scaleInt(400)));
        setPreferredSize(new Dimension(WorkbenchScale.scaleInt(600), WorkbenchScale.scaleInt(450)));

        setLayout(new BorderLayout());

        StringBuilder hint = new StringBuilder(1024);
        hint.append("<h2><font color=\"red\">Exception: <em>" + exception.getClass().getSimpleName()
                + "</em></font><br>");
        ClientHelper.encodeHtml(hint, exception.getMessage());
        hint.append("</h2>");
        if (exception.getCause() != null) {
            hint.append("<h3><font color=\"red\">Cause: <em>" + exception.getCause().getClass().getSimpleName()
                    + "</em></font><br>");
            ClientHelper.encodeHtml(hint, exception.getCause().getMessage());
            hint.append("</h3>");
        }
        hint.append("<hr><br>");
        hint.append(getHint());

        // hint area
        JPanel hintsPanel = new JPanel();
        hintsPanel.setLayout(new BoxLayout(hintsPanel, BoxLayout.PAGE_AXIS));
        hintsPanel.setBorder(WorkbenchScale.scaleBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        add(hintsPanel, BorderLayout.CENTER);

        JEditorPane hints = new JEditorPane("text/html", hint.toString());
        hints.setEditable(false);
        hints.setCaretPosition(0);

        hintsPanel.add(new JScrollPane(hints, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));

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
                ConnectionErrorDialog.this.dispose();
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

    private String getHint() {
        if (exception instanceof CmisObjectNotFoundException || exception instanceof CmisNotSupportedException) {
            return "The CMIS Workbench could connect to the server but the provided URL is not a CMIS endpoint URL."
                    + "<br>Check your URL and proxy settings." + getProxyConfig();
        } else if (exception instanceof CmisUnauthorizedException) {
            return "The provided credentials are invalid.<br>Check your credentials.";
        } else if (exception instanceof CmisPermissionDeniedException) {
            return "The provided credentials are invalid or the user has no permission to connect."
                    + "<br>Check your credentials.";
        } else if (exception instanceof CmisProxyAuthenticationException) {
            return "The proxy server requires valid credentials.<br>Check the session parameters "
                    + "'org.apache.chemistry.opencmis.binding.proxyuser' and "
                    + "'org.apache.chemistry.opencmis.binding.proxypassword'." + getProxyConfig();
        } else if (exception instanceof CmisTooManyRequestsException) {
            return "The server indicated that you made too many request. Wait or contact the server administrator.";
        } else if (exception instanceof CmisRuntimeException) {
            return "Something fatal happend on the client or server side."
                    + "<br>Check your URL, the binding, and your proxy settings."
                    + "<br><br>Also see the CMIS Workbench log for more details." + getProxyConfig();
        } else if (exception instanceof CmisConnectionException) {
            Throwable cause = exception.getCause();
            while (cause instanceof CmisConnectionException) {
                cause = cause.getCause();
            }

            if (cause instanceof MalformedURLException || cause instanceof URISyntaxException) {
                return "The provided URL is not a valid URL.<br>Check your URL.";
            } else if (cause instanceof UnknownHostException) {
                return "The CMIS Workbench could not connect to the server."
                        + "<br>Check your URL and your network and proxy settings." + getProxyConfig();
            } else if (cause instanceof SSLException) {
                return "The CMIS Workbench could not establish a SSL connection to the server."
                        + "<br>Check your network and proxy settings."
                        + "<br><br>If you want to connect to a server with a self-signed certificate, "
                        + "add the parameter <code>-Dcmis.workbench.acceptSelfSignedCertificates=true</code> "
                        + "to the JAVA_OPTS in the CMIS Workbench start script and restart."
                        + "<br><b>WARNING:</b> It disables <em>all</em> SSL certificate checks!" + getProxyConfig();
            } else if (cause instanceof JSONParseException) {
                return "The provided URL does not return a JSON response."
                        + "<br>Check your URL, the binding, and your proxy settings."
                        + "<br><br>Some servers return a HTML login page if the credentials are incorrect."
                        + "<br>Check your credentials." + getProxyConfig();
            } else if (cause instanceof XMLStreamException) {
                return "The provided URL does not return an AtomPub response."
                        + "<br>Check your URL, the binding, and your proxy settings."
                        + "<br><br>Some servers return a HTML login page if the credentials are incorrect."
                        + "<br>Check your credentials." + getProxyConfig();
            } else if (cause instanceof SAXParseException) {
                return "The provided URL does not return a WSDL."
                        + "<br>Check your URL, the binding, and your proxy settings."
                        + "<br><br>Some servers return a HTML login page if the credentials are incorrect."
                        + "<br>Check your credentials." + getProxyConfig();
            } else if (cause instanceof IOException) {
                return "A network problem occured.<br>Check your URL and your network and proxy settings."
                        + getProxyConfig();
            }

            if (exception.getMessage().toLowerCase(Locale.ENGLISH).startsWith("unexpected document")) {
                return "The provided URL does not return a AtomPub response."
                        + "<br>Check your URL, the binding, and your proxy settings."
                        + "<br><br>Some servers return a HTML login page if the credentials are incorrect."
                        + "<br>Check your credentials." + getProxyConfig();
            }

            return "Check the URL, the binding, and the credentials.";
        }

        return exception.getMessage();
    }

    private String getProxyConfig() {
        StringBuilder sb = new StringBuilder(256);

        sb.append("<br><br><hr><br><em>Current proxy settings:</em><br><br>");

        if (System.getProperty(HTTP_PROXY_HOST) == null && System.getProperty(HTTPS_PROXY_HOST) == null) {
            sb.append("<b>- no proxy settings -</b>");
        } else {
            sb.append("<table>");
            if (System.getProperty(HTTP_PROXY_HOST) != null) {
                sb.append("<tr><td><b>HTTP proxy:</b></td><td>");
                sb.append(System.getProperty(HTTP_PROXY_HOST) + ":" + System.getProperty(HTTP_PROXY_PORT));
                sb.append("</td></tr>");
            }

            if (System.getProperty(HTTPS_PROXY_HOST) != null) {
                sb.append("<tr><td><b>HTTPS proxy:</b></td><td>");
                sb.append(System.getProperty(HTTPS_PROXY_HOST) + ":" + System.getProperty(HTTPS_PROXY_PORT));
                sb.append("</td></tr>");
            }

            if (System.getProperty(HTTP_NON_PROXY_HOSTS) != null) {
                sb.append("<tr><td><b>Non proxy hosts:</b></td><td>");
                sb.append(System.getProperty(HTTP_NON_PROXY_HOSTS));
                sb.append("</td></tr>");
            }
        }

        return sb.toString();
    }
}

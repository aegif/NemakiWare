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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.chemistry.opencmis.commons.impl.IOUtils;

/**
 * Tries to detect the OS proxy configuration.
 */
public class ProxyDetector {

    public static final String HTTP_PROXY_HOST = "http.proxyHost";
    public static final String HTTP_PROXY_PORT = "http.proxyPort";
    public static final String HTTP_PROXY_USER = "http.proxyUser";
    public static final String HTTP_PROXY_PASSWORD = "http.proxyPassword";
    public static final String HTTPS_PROXY_HOST = "https.proxyHost";
    public static final String HTTPS_PROXY_PORT = "https.proxyPort";
    public static final String HTTPS_PROXY_USER = "https.proxyUser";
    public static final String HTTPS_PROXY_PASSWORD = "https.proxyPassword";
    public static final String HTTP_NON_PROXY_HOSTS = "http.nonProxyHosts";

    public static final Pattern PROXY_ENV_VAR1 = Pattern.compile("http.?:\\/\\/(.+):(\\d+).*");
    public static final Pattern PROXY_ENV_VAR2 = Pattern.compile("(.+):(\\d+)");
    public static final Pattern PROXY_WIN_REG = Pattern.compile("\\s+Proxy(.+)\\s+REG.+\\s+(.+)");

    private static boolean debug = false;

    /**
     * Gets proxy settings from system properties.
     */
    public static ProxySettings getJavaProxySettings() {
        ProxySettings settings = new ProxySettings();

        settings.setHttpProxyHost(System.getProperty(HTTP_PROXY_HOST));
        settings.setHttpProxyPort(parsePort(System.getProperty(HTTP_PROXY_PORT)));
        settings.setHttpProxyUser(System.getProperty(HTTP_PROXY_USER));
        settings.setHttpProxyPassword(System.getProperty(HTTP_PROXY_PASSWORD));
        settings.setHttpsProxyHost(System.getProperty(HTTPS_PROXY_HOST));
        settings.setHttpsProxyPort(parsePort(System.getProperty(HTTPS_PROXY_PORT)));
        settings.setHttpsProxyUser(System.getProperty(HTTPS_PROXY_USER));
        settings.setHttpsProxyPassword(System.getProperty(HTTPS_PROXY_PASSWORD));

        String nonProxyHosts = System.getProperty(HTTP_NON_PROXY_HOSTS);
        if (nonProxyHosts != null) {
            List<String> noHosts = new ArrayList<String>();

            String nph = nonProxyHosts;
            int pp = nph.indexOf('|');
            while (pp > -1) {
                String noHost = nph.substring(0, pp).trim();
                if (noHost.length() > 0) {
                    noHosts.add(noHost);
                }

                nph = nph.substring(pp + 1);
                pp = nph.indexOf('|');
            }

            nph = nph.trim();
            if (nph.length() > 0) {
                noHosts.add(nph);
            }

            settings.setNonProxyHosts(noHosts);
        }

        if (settings.getHttpProxyHost() != null || settings.getHttpsProxyHost() != null) {
            return settings;
        }

        return null;
    }

    /**
     * Gets proxy settings from environment variables.
     */
    public static ProxySettings getEnvProxySettings() {
        ProxySettings settings = new ProxySettings();

        Map<String, String> env = System.getenv();

        for (Map.Entry<String, String> e : env.entrySet()) {
            String key = e.getKey().trim().toLowerCase(Locale.ENGLISH);

            if ("http_proxy".equals(key)) {
                Matcher m = PROXY_ENV_VAR1.matcher(e.getValue());
                if (m.matches()) {
                    settings.setHttpProxyHost(parseHost(m.group(1)));
                    settings.setHttpProxyPort(parsePort(m.group(2)));
                    settings.setHttpProxyUser(parseProxyUser(m.group(1)));
                    settings.setHttpProxyPassword(parseProxyPassword(m.group(1)));
                } else {
                    m = PROXY_ENV_VAR2.matcher(e.getValue());
                    if (m.matches()) {
                        settings.setHttpProxyHost(parseHost(m.group(1)));
                        settings.setHttpProxyPort(parsePort(m.group(2)));
                        settings.setHttpProxyUser(parseProxyUser(m.group(1)));
                        settings.setHttpProxyPassword(parseProxyPassword(m.group(1)));
                    }
                }
            } else if ("https_proxy".equals(key)) {
                Matcher m = PROXY_ENV_VAR1.matcher(e.getValue());
                if (m.matches()) {
                    settings.setHttpsProxyHost(parseHost(m.group(1)));
                    settings.setHttpsProxyPort(parsePort(m.group(2)));
                    settings.setHttpsProxyUser(parseProxyUser(m.group(1)));
                    settings.setHttpsProxyPassword(parseProxyPassword(m.group(1)));
                } else {
                    m = PROXY_ENV_VAR2.matcher(e.getValue());
                    if (m.matches()) {
                        settings.setHttpProxyHost(parseHost(m.group(1)));
                        settings.setHttpProxyPort(parsePort(m.group(2)));
                        settings.setHttpsProxyUser(parseProxyUser(m.group(1)));
                        settings.setHttpsProxyPassword(parseProxyPassword(m.group(1)));
                    }
                }
            } else if ("no_proxy".equals(key)) {
                List<String> noHosts = new ArrayList<String>();
                for (String noHost : e.getValue().split(",")) {
                    noHost = noHost.trim();
                    if (noHost.length() == 0) {
                        continue;
                    }

                    if (noHost.charAt(0) == '.') {
                        noHost = "*" + noHost;
                    }

                    if (noHost.charAt(noHost.length() - 1) == '.') {
                        noHost = noHost + "*";
                    }

                    noHosts.add(noHost);
                }

                settings.setNonProxyHosts(noHosts);
            }
        }

        if (settings.getHttpProxyHost() != null || settings.getHttpsProxyHost() != null) {
            return settings;
        }

        return null;
    }

    /**
     * Gets proxy settings from the Windows registry.
     */
    public static ProxySettings getRegistryProxySettings() {
        if (!isWindows()) {
            return null;
        }

        BufferedReader reader = null;
        try {
            ProxySettings settings = new ProxySettings();

            Process process = Runtime
                    .getRuntime()
                    .exec("reg query \"HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\" /v Proxy*");

            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = PROXY_WIN_REG.matcher(line);
                if (!m.matches()) {
                    continue;
                }

                String key = m.group(1).trim().toLowerCase(Locale.ENGLISH);
                String value = m.group(2).trim();

                if ("enable".equals(key)) {
                    if (!value.equals("0x1")) {
                        // proxies disabled
                        return null;
                    }
                } else if ("server".equals(key)) {
                    // server
                    String host = value;
                    int port = 80;

                    int x = value.indexOf(':');
                    if (x > 0) {
                        host = value.substring(0, x);
                        port = parsePort(value.substring(x + 1));
                    }

                    settings.setHttpProxyHost(host);
                    settings.setHttpProxyPort(port);
                    settings.setHttpsProxyHost(host);
                    settings.setHttpsProxyPort(port);
                } else if ("override".equals(key)) {
                    // no proxy
                    List<String> noHosts = new ArrayList<String>();
                    for (String noHost : value.split(";")) {
                        noHost = noHost.trim();
                        if (noHost.length() == 0) {
                            continue;
                        }
                        noHosts.add(noHost);
                    }

                    settings.setNonProxyHosts(noHosts);
                }
            }

            if (settings.getHttpProxyHost() != null || settings.getHttpsProxyHost() != null) {
                return settings;
            }
        } catch (IOException e) {
            // ignore
            if (debug) {
                e.printStackTrace();
            }
        } finally {
            IOUtils.closeQuietly(reader);
        }

        return null;
    }

    private static String parseHost(String host) {
        if (host == null) {
            return null;
        }

        int at = host.lastIndexOf('@');
        if (at > -1) {
            return host.substring(at + 1);
        }

        return host;
    }

    private static int parsePort(String port) {
        if (port == null) {
            return 80;
        }

        try {
            return Integer.parseInt(port);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid port: " + port);
        }
    }

    private static String parseProxyUser(String host) {
        if (host == null) {
            return null;
        }

        int at = host.lastIndexOf('@');
        if (at == -1) {
            return null;
        }

        int colon = host.indexOf(':');
        if (colon == -1 || colon > at) {
            return host.substring(0, at);
        }

        return host.substring(0, colon);
    }

    private static String parseProxyPassword(String host) {
        if (host == null) {
            return null;
        }

        int at = host.lastIndexOf('@');
        if (at == -1) {
            return null;
        }

        int colon = host.indexOf(':');
        if (colon == -1 || colon > at) {
            return null;
        }

        return host.substring(colon + 1, at);
    }

    private static boolean isWindows() {
        String osname = System.getProperty("os.name");
        return osname == null ? false : osname.startsWith("Windows");
    }

    /**
     * Main.
     */
    public static void main(String[] args) {
        boolean javaParams = false;
        boolean useSystemProxy = false;

        for (String arg : args) {
            if (arg.equalsIgnoreCase("-j")) {
                javaParams = true;
            } else if (arg.equalsIgnoreCase("-s")) {
                useSystemProxy = true;
            } else if (arg.equalsIgnoreCase("-d")) {
                debug = true;
            } else if (arg.equalsIgnoreCase("-h") || arg.equalsIgnoreCase("-?")) {
                System.out.println("Parameters:");
                System.out.println("-j  print Java system properties");
                System.out.println("-s  add java.net.useSystemProxies property");
                return;
            }
        }

        ProxySettings settings = null;

        settings = getJavaProxySettings();

        if (settings == null) {
            // try environment variables
            settings = getEnvProxySettings();
        }

        if (settings == null) {
            // try the Windows registry
            settings = getRegistryProxySettings();
        }

        // print proxy settings
        if (javaParams) {
            String proxyStr = "";

            if (settings != null) {
                proxyStr = settings.toJavaConfigString();
            }

            if (useSystemProxy) {
                if (proxyStr.length() > 0) {
                    proxyStr = proxyStr + " ";
                }
                proxyStr = proxyStr + "-Djava.net.useSystemProxies=true";
            }

            System.out.println(proxyStr);
        } else {
            if (settings != null) {
                System.out.println(settings.toString());
            } else {
                System.out.println("DIRECT");
            }
        }
    }

    /**
     * Proxy settings holder.
     */
    public static class ProxySettings {
        private String httpProxyHost;
        private int httpProxyPort;
        private String httpProxyUser;
        private String httpProxyPassword;

        private String httpsProxyHost;
        private int httpsProxyPort;
        private String httpsProxyUser;
        private String httpsProxyPassword;

        private List<String> nonProxyHosts;

        public ProxySettings() {
        }

        public String getHttpProxyHost() {
            return httpProxyHost;
        }

        public void setHttpProxyHost(String httpProxyHost) {
            this.httpProxyHost = httpProxyHost;
        }

        public int getHttpProxyPort() {
            return httpProxyPort;
        }

        public void setHttpProxyPort(int httpProxyPort) {
            this.httpProxyPort = httpProxyPort;
        }

        public String getHttpProxyUser() {
            return httpProxyUser;
        }

        public void setHttpProxyUser(String httpProxyUser) {
            this.httpProxyUser = httpProxyUser;
        }

        public String getHttpProxyPassword() {
            return httpProxyPassword;
        }

        public void setHttpProxyPassword(String httpProxyPassword) {
            this.httpProxyPassword = httpProxyPassword;
        }

        public String getHttpsProxyHost() {
            return httpsProxyHost;
        }

        public void setHttpsProxyHost(String httpsProxyHost) {
            this.httpsProxyHost = httpsProxyHost;
        }

        public int getHttpsProxyPort() {
            return httpsProxyPort;
        }

        public void setHttpsProxyPort(int httpsProxyPort) {
            this.httpsProxyPort = httpsProxyPort;
        }

        public String getHttpsProxyUser() {
            return httpsProxyUser;
        }

        public void setHttpsProxyUser(String httpsProxyUser) {
            this.httpsProxyUser = httpsProxyUser;
        }

        public String getHttpsProxyPassword() {
            return httpsProxyPassword;
        }

        public void setHttpsProxyPassword(String httpsProxyPassword) {
            this.httpsProxyPassword = httpsProxyPassword;
        }

        public List<String> getNonProxyHosts() {
            return nonProxyHosts;
        }

        public void setNonProxyHosts(List<String> nonProxyHosts) {
            this.nonProxyHosts = nonProxyHosts;
        }

        public String toJavaConfigString() {
            StringBuilder sb = new StringBuilder(128);

            if (httpProxyHost != null) {
                sb.append("-D" + HTTP_PROXY_HOST + "=" + httpProxyHost + " -D" + HTTP_PROXY_PORT + "=" + httpProxyPort);
            }

            if (httpProxyUser != null) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }

                sb.append("-D" + HTTP_PROXY_USER + "=" + httpProxyUser);
            }

            if (httpProxyPassword != null) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }

                sb.append("-D" + HTTP_PROXY_PASSWORD + "=" + httpProxyPassword);
            }

            if (httpsProxyHost != null) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }

                sb.append("-D" + HTTPS_PROXY_HOST + "=" + httpsProxyHost + " -D" + HTTPS_PROXY_PORT + "="
                        + httpsProxyPort);
            }

            if (httpsProxyUser != null) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }

                sb.append("-D" + HTTPS_PROXY_USER + "=" + httpsProxyUser);
            }

            if (httpsProxyPassword != null) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }

                sb.append("-D" + HTTPS_PROXY_PASSWORD + "=" + httpsProxyPassword);
            }

            if (nonProxyHosts != null && !nonProxyHosts.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }

                if (isWindows()) {
                    sb.append('\"');
                }

                sb.append("-D" + HTTP_NON_PROXY_HOSTS + "=");

                boolean first = true;
                for (String host : nonProxyHosts) {
                    if (!first) {
                        sb.append('|');
                    } else {
                        first = false;
                    }

                    sb.append(host);
                }

                if (isWindows()) {
                    sb.append('\"');
                }
            }

            return sb.toString();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);

            if (httpProxyHost != null) {
                sb.append("HTTP proxy: " + httpProxyHost + ":" + httpProxyPort);
            }

            if (httpProxyUser != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }

                sb.append("HTTP proxy user: " + httpProxyUser);
            }

            if (httpProxyPassword != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }

                sb.append("HTTP proxy password: " + httpProxyPassword);
            }

            if (httpsProxyHost != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }

                sb.append("HTTPS proxy: " + httpsProxyHost + ":" + httpsProxyPort);
            }

            if (httpsProxyUser != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }

                sb.append("HTTPS proxy user: " + httpsProxyUser);
            }

            if (httpsProxyPassword != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }

                sb.append("HTTPS proxy password: " + httpsProxyPassword);
            }

            if (nonProxyHosts != null && !nonProxyHosts.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }

                sb.append("Non proxy hosts: ");

                boolean first = true;
                for (String host : nonProxyHosts) {
                    if (!first) {
                        sb.append(",");
                    } else {
                        first = false;
                    }

                    sb.append(host);
                }
            }

            return sb.toString();
        }
    }
}

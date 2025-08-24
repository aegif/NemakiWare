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
package org.apache.chemistry.opencmis.client.bindings.spi;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Enumeration;
import java.util.Locale;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client Certificate Authentication Provider.
 * 
 * Enables the use of SSL client certificates for authentication. It requires
 * the path to a JKS key file and its pass phrase.
 * 
 * <pre>
 * {@code
 * SessionFactory factory = ...
 * 
 * Map<String, String> parameter = new HashMap<String, String>();
 * 
 * parameter.put(SessionParameter.ATOMPUB_URL, "https://localhost/cmis/atom");
 * parameter.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
 * parameter.put(SessionParameter.REPOSITORY_ID, "myRepository");
 * 
 * parameter.put(SessionParameter.AUTHENTICATION_PROVIDER_CLASS, "org.apache.chemistry.opencmis.client.bindings.spi.ClientCertificateAuthenticationProvider");
 * 
 * parameter.put(SessionParameter.CLIENT_CERT_KEYFILE, "/path/to/mycert.jks");
 * parameter.put(SessionParameter.CLIENT_CERT_PASSPHRASE, "changeme");
 * 
 * ...
 * Session session = factory.createSession(parameter);
 * }
 * </pre>
 * 
 */
public class ClientCertificateAuthenticationProvider extends StandardAuthenticationProvider {
    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(ClientCertificateAuthenticationProvider.class);

    private SSLSocketFactory socketFactory;

    @Override
    public void setSession(BindingSession session) {
        super.setSession(session);

        if (socketFactory == null) {
            Object keyfile = getSession().get(SessionParameter.CLIENT_CERT_KEYFILE);
            if (keyfile instanceof String) {
                Object passphrase = getSession().get(SessionParameter.CLIENT_CERT_PASSPHRASE);

                String keyfileStr = ((String) keyfile).trim();
                String passphraseStr = passphrase instanceof String ? ((String) passphrase).trim() : null;

                socketFactory = createSSLSocketFactory(keyfileStr, passphraseStr);
            }
        }
    }

    @Override
    public SSLSocketFactory getSSLSocketFactory() {
        return socketFactory;
    }

    protected SSLSocketFactory createSSLSocketFactory(String keyFile, String passphrase) {
        assert keyFile != null;

        if (LOG.isDebugEnabled()) {
            LOG.debug("Using key file '{}'", keyFile);
        }

        try {
            char[] passphraseChars = passphrase == null ? null : passphrase.toCharArray();

            KeyStore keyStore;

            String ext = getExtension(keyFile);
            if ("p12".equals(ext) || "pfx".equals(ext)) {
                keyStore = KeyStore.getInstance("PKCS12");
            } else {
                keyStore = KeyStore.getInstance("JKS");
            }

            // read key store
            InputStream keyStream = null;
            try {
                keyStream = new BufferedInputStream(new FileInputStream(keyFile));
                keyStore.load(keyStream, passphraseChars);
            } finally {
                IOUtils.closeQuietly(keyStream);
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Key store type: {}", keyStore.getType());

                StringBuilder sb = new StringBuilder();
                Enumeration<String> aliases = keyStore.aliases();
                while (aliases.hasMoreElements()) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(aliases.nextElement());
                }

                LOG.debug("Aliases in key store: {}", sb.toString());
            }

            // create socket factory
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
            keyManagerFactory.init(keyStore, passphraseChars);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagerFactory.getKeyManagers(), null, null);

            return context.getSocketFactory();
        } catch (FileNotFoundException fnfe) {
            throw new CmisRuntimeException("Key file '" + keyFile + "' not found!", fnfe);
        } catch (Exception e) {
            throw new CmisRuntimeException("Cannot set up client certificate: " + e.toString(), e);
        }
    }

    private String getExtension(String filename) {
        int x = filename.lastIndexOf('.');
        if (x > -1) {
            return filename.substring(x + 1).toLowerCase(Locale.ENGLISH);
        }

        return null;
    }
}

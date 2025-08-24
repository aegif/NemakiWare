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
package org.apache.chemistry.opencmis.commons.impl.dataobjects;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.chemistry.opencmis.commons.data.ContentStreamHash;

public class ContentStreamHashImpl implements ContentStreamHash {

    public static final String ALGORITHM_MD5 = "md5";
    public static final String ALGORITHM_SHA1 = "sha-1";
    public static final String ALGORITHM_SHA224 = "sha-224";
    public static final String ALGORITHM_SHA256 = "sha-256";
    public static final String ALGORITHM_SHA384 = "sha-384";
    public static final String ALGORITHM_SHA512 = "sha-512";
    public static final String ALGORITHM_SHA3 = "sha-3";

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private String propertyValue;
    private String algorithm = null;
    private String hash = null;

    /**
     * Constructs an object from the {@code cmis:contentStreamHash} property
     * value.
     * 
     * @param propertyValue
     *            the property value
     */
    public ContentStreamHashImpl(String propertyValue) {
        this.propertyValue = propertyValue;

        if (propertyValue == null) {
            return;
        }

        String pv = propertyValue.trim();
        int algEnd = pv.indexOf('}');
        if (pv.charAt(0) != '{' || algEnd < 1) {
            return;
        }

        this.algorithm = pv.substring(1, algEnd).toLowerCase(Locale.ENGLISH);
        this.hash = pv.substring(algEnd + 1).replaceAll("\\s", "").toLowerCase(Locale.ENGLISH);
    }

    /**
     * Constructs an object from the algorithm and hash.
     * 
     * @param algorithm
     *            the algorithm
     * @param hashStr
     *            the hash value
     */
    public ContentStreamHashImpl(String algorithm, String hashStr) {
        if (algorithm == null || algorithm.trim().length() == 0) {
            throw new IllegalArgumentException("Algorithm must be set!");
        }

        if (hashStr == null || hashStr.trim().length() == 0) {
            throw new IllegalArgumentException("Hash must be set!");
        }

        this.algorithm = algorithm.toLowerCase(Locale.ENGLISH);
        this.hash = hashStr.replaceAll("\\s", "").toLowerCase(Locale.ENGLISH);
        this.propertyValue = "{" + this.algorithm + "}" + this.hash;
    }

    /**
     * Constructs an object from the algorithm and hash.
     * 
     * @param algorithm
     *            the algorithm
     * @param hashBytes
     *            the hash value as byte array
     */
    public ContentStreamHashImpl(String algorithm, byte[] hashBytes) {
        if (algorithm == null || algorithm.trim().length() == 0) {
            throw new IllegalArgumentException("Algorithm must be set!");
        }

        if (hashBytes == null || hashBytes.length == 0) {
            throw new IllegalArgumentException("Hash must be set!");
        }

        this.algorithm = algorithm.toLowerCase(Locale.ENGLISH);
        this.hash = byteArrayToHexString(hashBytes);
        this.propertyValue = "{" + this.algorithm + "}" + this.hash;
    }

    @Override
    public String getPropertyValue() {
        return propertyValue;
    }

    @Override
    public String getAlgorithm() {
        return algorithm;
    }

    @Override
    public String getHash() {
        return hash;
    }

    @Override
    public int hashCode() {
        return propertyValue.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }

        ContentStreamHashImpl other = (ContentStreamHashImpl) obj;
        if (propertyValue == null) {
            if (other.propertyValue != null) {
                return false;
            }
        } else if (!propertyValue.equals(other.propertyValue)) {
            return false;
        }

        return true;
    }

    @Override
    public String toString() {
        return propertyValue;
    }

    /**
     * Creates a list of content hashes from a stream
     * <p>
     * This method consumes the stream but doesn't close it.
     * 
     * @param stream
     *            the stream
     * @param algorithm
     *            the algorithms
     * @return the list of content hashes
     */
    public static List<ContentStreamHash> createContentStreamHashes(InputStream stream, String... algorithm)
            throws IOException, NoSuchAlgorithmException {
        if (stream == null) {
            throw new IllegalArgumentException("Stream must be set!");
        }

        if (algorithm == null || algorithm.length == 0) {
            throw new IllegalArgumentException("Algorithm must be set!");
        }

        MessageDigest[] md = new MessageDigest[algorithm.length];
        for (int i = 0; i < algorithm.length; i++) {
            md[i] = MessageDigest.getInstance(algorithm[i]);
        }

        int b;
        byte[] buffer = new byte[64 * 1024];
        while ((b = stream.read(buffer)) > -1) {
            for (int j = 0; j < md.length; j++) {
                md[j].update(buffer, 0, b);
            }
        }

        List<ContentStreamHash> result = new ArrayList<ContentStreamHash>(md.length);

        for (int i = 0; i < md.length; i++) {
            result.add(new ContentStreamHashImpl(algorithm[i], md[i].digest()));
        }

        return result;
    }

    protected static String byteArrayToHexString(byte[] bytes) {
        int n = bytes.length;
        char[] hashHex = new char[n * 2];
        for (int i = 0; i < n; i++) {
            hashHex[i * 2] = HEX_DIGITS[(0xF0 & bytes[i]) >>> 4];
            hashHex[i * 2 + 1] = HEX_DIGITS[0x0F & bytes[i]];
        }

        return new String(hashHex);
    }
}

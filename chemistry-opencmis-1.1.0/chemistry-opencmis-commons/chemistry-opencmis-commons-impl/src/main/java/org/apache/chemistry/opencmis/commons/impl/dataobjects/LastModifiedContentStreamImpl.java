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

import java.io.InputStream;
import java.math.BigInteger;
import java.util.GregorianCalendar;

import org.apache.chemistry.opencmis.commons.data.LastModifiedContentStream;

/**
 * Content stream data with last modified date implementation.
 */
public class LastModifiedContentStreamImpl extends ContentStreamImpl implements LastModifiedContentStream {

    private static final long serialVersionUID = 1L;

    private GregorianCalendar lastModified;

    /**
     * Constructor.
     */
    public LastModifiedContentStreamImpl() {
    }

    /**
     * Constructor.
     */
    public LastModifiedContentStreamImpl(String filename, BigInteger length, String mimetype, InputStream stream,
            GregorianCalendar lastModified) {
        super(filename, length, mimetype, stream);
        this.lastModified = lastModified;
    }

    @Override
    public GregorianCalendar getLastModified() {
        return lastModified;
    }

    public void setLastModified(GregorianCalendar lastModified) {
        this.lastModified = lastModified;
    }
}

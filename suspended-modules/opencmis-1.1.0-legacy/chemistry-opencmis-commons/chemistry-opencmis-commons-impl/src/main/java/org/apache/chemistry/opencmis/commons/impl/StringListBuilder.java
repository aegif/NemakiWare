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
package org.apache.chemistry.opencmis.commons.impl;

public class StringListBuilder {

    private final String seperator;
    private final StringBuilder stringBuilder;
    private boolean first;

    public StringListBuilder() {
        this(",", new StringBuilder(128));
    }

    public StringListBuilder(StringBuilder stringBuilder) {
        this(",", stringBuilder);
    }

    public StringListBuilder(String seperator) {
        this(seperator, new StringBuilder(128));
    }

    public StringListBuilder(String seperator, StringBuilder stringBuilder) {
        this.seperator = seperator;
        this.stringBuilder = stringBuilder;
        first = true;
    }

    public void add(String s) {
        if (!first) {
            stringBuilder.append(seperator);
        } else {
            first = false;
        }

        stringBuilder.append(s);
    }

    public StringBuilder getStringBuilder() {
        return stringBuilder;
    }

    @Override
    public String toString() {
        return stringBuilder.toString();
    }

}

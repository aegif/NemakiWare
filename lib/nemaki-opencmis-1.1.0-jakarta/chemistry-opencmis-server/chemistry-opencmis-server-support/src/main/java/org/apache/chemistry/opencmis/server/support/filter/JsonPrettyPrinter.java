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
package org.apache.chemistry.opencmis.server.support.filter;

/**
 * A super simple JSON pretty printer
 */
public class JsonPrettyPrinter {

    private int indent = 0;
    private String indentStr;
    private StringBuilder sb = new StringBuilder(1024);

    public JsonPrettyPrinter() {
        init(3);
    }

    public JsonPrettyPrinter(int indent) {
        init(indent);
    }

    private void init(final int indent) {
        StringBuilder indentBuilder = new StringBuilder(indent);
        for (int i = 0; i < indent; i++) {
            indentBuilder.append(' ');
        }
        indentStr = indentBuilder.toString();
    }

    public String prettyPrint(final String jsonStr) {
        for (int i = 0; i < jsonStr.length(); i++) {
            char c = jsonStr.charAt(i);
            writeChar(c);
        }
        return sb.toString();
    }

    private void writeChar(char c) {
        if (c == '[' || c == '{') {
            sb.append(c);
            sb.append('\n');
            indent++;
            addIndent();
        } else if (c == ',') {
            sb.append(c);
            sb.append('\n');
            addIndent();
        } else if (c == ']' || c == '}') {
            sb.append('\n');
            indent--;
            addIndent();
            sb.append(c);
        } else {
            sb.append(c);
        }
    }

    private void addIndent() {
        for (int i = 0; i < indent; i++) {
            sb.append(indentStr);
        }
    }

//    public static void main(String[] args) {
//        args = new String[2];
//        args[0] = "[0,{\"1\":{\"2\":{\"3\":{\"4\":[5,{\"6\":7}]}}}}]";
//        args[1] = "{\"abc\":{\"def\":{\"ghi\":{\"jkl\":[5,{\"mno\":7}]}}}}";
//        for (String s : args) {
//            JsonPrettyPrinter pp = new JsonPrettyPrinter();
//            System.out.println("Pretty Printing JSON String: " + s);
//            String result = pp.prettyPrint(s);
//            System.out.println("Pretty Printed JSON: " + result);
//        }
//    }
}

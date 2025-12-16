/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
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
package org.apache.chemistry.opencmis.client.runtime;

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNotEmpty;
import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNullOrEmpty;

import java.net.URI;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.QueryStatement;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.impl.DateTimeHelper;
import org.apache.chemistry.opencmis.commons.impl.StringListBuilder;

/**
 * QueryStatement implementation.
 */
public class QueryStatementImpl implements QueryStatement, Cloneable {

    private final Session session;
    private final String statement;
    private final Map<Integer, String> parametersMap = new HashMap<Integer, String>();

    /**
     * Creates a QueryStatement object with a given statement.
     * 
     * @param session
     *            the Session object, must not be {@code null}
     * @param statement
     *            the query statement with placeholders ('?'), see
     *            {@link QueryStatement} for details
     */
    public QueryStatementImpl(Session session, String statement) {
        if (session == null) {
            throw new IllegalArgumentException("Session must be set!");
        }

        if (statement == null) {
            throw new IllegalArgumentException("Statement must be set!");
        }

        this.session = session;
        this.statement = statement.trim();
    }

    /**
     * Creates a QueryStatement object for a query of one primary type joined by
     * zero or more secondary types.
     * 
     * @param session
     *            the Session object, must not be {@code null}
     * @param selectPropertyIds
     *            the property IDs in the SELECT statement, if {@code null} all
     *            properties are selected
     * @param fromTypes
     *            a Map of type aliases (keys) and type IDs (values), the Map
     *            must contain exactly one primary type and zero or more
     *            secondary types
     * @param whereClause
     *            an optional WHERE clause with placeholders ('?'), see
     *            {@link QueryStatement} for details
     * @param orderByPropertyIds
     *            an optional list of properties IDs for the ORDER BY clause
     */
    public QueryStatementImpl(Session session, Collection<String> selectPropertyIds, Map<String, String> fromTypes,
            String whereClause, List<String> orderByPropertyIds) {
        if (session == null) {
            throw new IllegalArgumentException("Session must be set!");
        }

        if (isNullOrEmpty(fromTypes)) {
            throw new IllegalArgumentException("Types must be set!");
        }

        this.session = session;

        StringBuilder stmt = new StringBuilder(1024);

        // find the primary type and check if all types are queryable
        ObjectType primaryType = null;
        String primaryAlias = null;

        Map<String, ObjectType> types = new HashMap<String, ObjectType>();
        for (Map.Entry<String, String> fte : fromTypes.entrySet()) {
            ObjectType type = session.getTypeDefinition(fte.getValue());

            if (Boolean.FALSE.equals(type.isQueryable())) {
                throw new IllegalArgumentException("Type '" + fte.getValue() + "' is not queryable!");
            }

            String alias = fte.getKey().trim();
            if (alias.length() < 1) {
                throw new IllegalArgumentException("Invalid alias for type '" + fte.getValue() + "'!");
            }

            if (type.getBaseTypeId() != BaseTypeId.CMIS_SECONDARY) {
                if (primaryType == null) {
                    primaryType = type;
                    primaryAlias = alias;
                } else {
                    throw new IllegalArgumentException(
                            "Two primary types found: " + primaryType.getId() + " and " + type.getId());
                }
            }

            // exclude secondary types without properties
            if (isNotEmpty(type.getPropertyDefinitions())) {
                types.put(alias, type);
            }
        }

        if (primaryType == null) {
            throw new IllegalArgumentException("No primary type found!");
        }

        // SELECT
        stmt.append("SELECT ");

        StringListBuilder selectList = new StringListBuilder(",", stmt);

        if (isNullOrEmpty(selectPropertyIds)) {
            // select all properties
            for (String alias : types.keySet()) {
                selectList.add(alias + ".*");
            }
        } else {
            // select provided properties
            for (String propertyId : selectPropertyIds) {

                propertyId = propertyId.trim();

                if (propertyId.equals("*")) {
                    // found property "*" -> select all properties
                    for (String alias : types.keySet()) {
                        selectList.add(alias + ".*");
                    }
                    continue;
                }

                if (propertyId.endsWith(".*")) {
                    // found property "x.*"
                    // -> select all properties of the type with alias "x"
                    String starAlias = propertyId.substring(0, propertyId.length() - 2);
                    if (types.containsKey(starAlias)) {
                        selectList.add(starAlias + ".*");
                        continue;
                    } else {
                        throw new IllegalArgumentException("Alias '" + starAlias + "' is not defined!");
                    }
                }

                PropertyDefinition<?> propertyDef = null;
                String alias = null;

                for (Map.Entry<String, ObjectType> te : types.entrySet()) {
                    propertyDef = te.getValue().getPropertyDefinitions().get(propertyId);
                    if (propertyDef != null) {
                        alias = te.getKey();
                        break;
                    }
                }

                if (propertyDef == null) {
                    throw new IllegalArgumentException(
                            "Property '" + propertyId + "' is not defined in the provided object types!");
                }

                if (propertyDef.getQueryName() == null) {
                    throw new IllegalArgumentException("Property '" + propertyId + "' has no query name!");
                }

                selectList.add(alias + "." + propertyDef.getQueryName());
            }
        }

        // FROM
        stmt.append(" FROM ");

        stmt.append(primaryType.getQueryName());
        stmt.append(" AS ");
        stmt.append(primaryAlias);

        for (Map.Entry<String, ObjectType> te : types.entrySet()) {
            if (te.getKey().equals(primaryAlias)) {
                continue;
            }

            stmt.append(" JOIN ");
            stmt.append(te.getValue().getQueryName());
            stmt.append(" AS ");
            stmt.append(te.getKey());
            stmt.append(" ON ");
            stmt.append(primaryAlias);
            stmt.append(".cmis:objectId=");
            stmt.append(te.getKey());
            stmt.append(".cmis:objectId");
        }

        // WHERE
        if (whereClause != null && whereClause.trim().length() > 0) {
            stmt.append(" WHERE ");
            stmt.append(whereClause.trim());
        }

        // ORDER BY
        if (isNotEmpty(orderByPropertyIds)) {
            stmt.append(" ORDER BY ");

            StringListBuilder orderByList = new StringListBuilder(",", stmt);

            for (String propertyId : orderByPropertyIds) {
                String realPropertyId = propertyId.trim();
                String realPropertyIdLower = realPropertyId.toLowerCase(Locale.ENGLISH);
                boolean desc = false;

                if (realPropertyIdLower.endsWith(" asc")) {
                    // property ends with " asc" -> remove it
                    realPropertyId = realPropertyId.substring(0, realPropertyId.length() - 4);
                }

                if (realPropertyIdLower.endsWith(" desc")) {
                    // property ends with " desc" -> remove it and mark it as
                    // descending
                    realPropertyId = realPropertyId.substring(0, realPropertyId.length() - 5);
                    desc = true;
                }

                PropertyDefinition<?> propertyDef = null;
                String alias = null;

                for (Map.Entry<String, ObjectType> te : types.entrySet()) {
                    propertyDef = te.getValue().getPropertyDefinitions().get(realPropertyId);
                    if (propertyDef != null) {
                        alias = te.getKey();
                        break;
                    }
                }

                if (propertyDef == null) {
                    throw new IllegalArgumentException(
                            "Property '" + realPropertyId + "' is not defined in the provided object types!");
                }

                if (propertyDef.getQueryName() == null) {
                    throw new IllegalArgumentException("Property '" + realPropertyId + "' has no query name!");
                }

                if (Boolean.FALSE.equals(propertyDef.isOrderable())) {
                    throw new IllegalArgumentException("Property '" + realPropertyId + "' is not orderable!");
                }

                orderByList.add(alias + "." + propertyDef.getQueryName() + (desc ? " DESC" : ""));
            }
        }

        this.statement = stmt.toString();
    }

    @Override
    public void setType(int parameterIndex, String typeId) {
        setType(parameterIndex, session.getTypeDefinition(typeId));
    }

    @Override
    public void setType(int parameterIndex, ObjectType type) {
        if (type == null) {
            throw new IllegalArgumentException("Type must be set!");
        }

        String queryName = type.getQueryName();
        if (queryName == null) {
            throw new IllegalArgumentException("Type has no query name!");
        }

        parametersMap.put(parameterIndex, queryName);
    }

    @Override
    public void setProperty(int parameterIndex, String typeId, String propertyId) {
        ObjectType type = session.getTypeDefinition(typeId);

        PropertyDefinition<?> propertyDefinition = type.getPropertyDefinitions().get(propertyId);
        if (propertyDefinition == null) {
            throw new IllegalArgumentException("Property does not exist!");
        }

        setProperty(parameterIndex, propertyDefinition);
    }

    @Override
    public void setProperty(int parameterIndex, PropertyDefinition<?> propertyDefinition) {
        if (propertyDefinition == null) {
            throw new IllegalArgumentException("Property must be set!");
        }

        String queryName = propertyDefinition.getQueryName();
        if (queryName == null) {
            throw new IllegalArgumentException("Property has no query name!");
        }

        parametersMap.put(parameterIndex, queryName);
    }

    @Override
    public void setNumber(int parameterIndex, Number... num) {
        if (num == null || num.length == 0) {
            throw new IllegalArgumentException("Number must be set!");
        }

        StringListBuilder slb = new StringListBuilder(",");
        for (Number n : num) {
            if (n == null) {
                throw new IllegalArgumentException("Number is null!");
            }

            slb.add(n.toString());
        }

        parametersMap.put(parameterIndex, slb.toString());
    }

    @Override
    public void setString(int parameterIndex, String... str) {
        if (str == null || str.length == 0) {
            throw new IllegalArgumentException("String must be set!");
        }

        StringListBuilder slb = new StringListBuilder(",");
        for (String s : str) {
            if (s == null) {
                throw new IllegalArgumentException("String is null!");
            }

            slb.add(escape(s));
        }

        parametersMap.put(parameterIndex, slb.toString());
    }

    @Override
    public void setStringContains(int parameterIndex, String str) {
        if (str == null) {
            throw new IllegalArgumentException("String must be set!");
        }

        parametersMap.put(parameterIndex, escapeContains(str));
    }

    @Override
    public void setStringLike(int parameterIndex, String str) {
        if (str == null) {
            throw new IllegalArgumentException("String must be set!");
        }

        parametersMap.put(parameterIndex, escapeLike(str));
    }

    @Override
    public void setId(int parameterIndex, ObjectId... id) {
        if (id == null || id.length == 0) {
            throw new IllegalArgumentException("Id must be set!");
        }

        StringListBuilder slb = new StringListBuilder(",");
        for (ObjectId oid : id) {
            if (oid == null || oid.getId() == null) {
                throw new IllegalArgumentException("Id is null!");
            }

            slb.add(escape(oid.getId()));
        }

        parametersMap.put(parameterIndex, slb.toString());
    }

    @Override
    public void setUri(int parameterIndex, URI... uri) {
        if (uri == null) {
            throw new IllegalArgumentException("URI must be set!");
        }

        StringListBuilder slb = new StringListBuilder(",");
        for (URI u : uri) {
            if (u == null) {
                throw new IllegalArgumentException("URI is null!");
            }

            slb.add(escape(u.toString()));
        }

        parametersMap.put(parameterIndex, slb.toString());
    }

    @Override
    public void setUrl(int parameterIndex, URL... url) {
        if (url == null) {
            throw new IllegalArgumentException("URL must be set!");
        }

        StringListBuilder slb = new StringListBuilder(",");
        for (URL u : url) {
            if (u == null) {
                throw new IllegalArgumentException("URI is null!");
            }

            slb.add(escape(u.toString()));
        }

        parametersMap.put(parameterIndex, slb.toString());
    }

    @Override
    public void setBoolean(int parameterIndex, boolean... bool) {
        if (bool == null || bool.length == 0) {
            throw new IllegalArgumentException("Boolean must not be set!");
        }

        StringListBuilder slb = new StringListBuilder(",");
        for (boolean b : bool) {
            slb.add(b ? "TRUE" : "FALSE");
        }

        parametersMap.put(parameterIndex, slb.toString());
    }

    @Override
    public void setDateTime(int parameterIndex, Calendar... cal) {
        setDateTime(parameterIndex, false, cal);
    }

    @Override
    public void setDateTimeTimestamp(int parameterIndex, Calendar... cal) {
        setDateTime(parameterIndex, true, cal);
    }

    protected void setDateTime(int parameterIndex, boolean prefix, Calendar... cal) {
        if (cal == null || cal.length == 0) {
            throw new IllegalArgumentException("Calendar must be set!");
        }

        StringBuilder sb = new StringBuilder(64);
        for (Calendar c : cal) {
            if (c == null) {
                throw new IllegalArgumentException("DateTime is null!");
            }

            if (sb.length() > 0) {
                sb.append(',');
            }

            if (prefix) {
                sb.append("TIMESTAMP ");
            }

            sb.append(convert(c.getTime()));
        }

        parametersMap.put(parameterIndex, sb.toString());
    }

    @Override
    public void setDateTime(int parameterIndex, Date... date) {
        setDateTime(parameterIndex, false, date);
    }

    @Override
    public void setDateTimeTimestamp(int parameterIndex, Date... date) {
        setDateTime(parameterIndex, true, date);
    }

    protected void setDateTime(int parameterIndex, boolean prefix, Date... date) {
        if (date == null || date.length == 0) {
            throw new IllegalArgumentException("Date must be set!");
        }

        StringListBuilder slb = new StringListBuilder(",");
        for (Date d : date) {
            if (d == null) {
                throw new IllegalArgumentException("DateTime is null!");
            }

            slb.add((prefix ? "TIMESTAMP " : "") + convert(d));
        }

        parametersMap.put(parameterIndex, slb.toString());
    }

    @Override
    public void setDateTime(int parameterIndex, long... ms) {
        setDateTime(parameterIndex, false, ms);
    }

    @Override
    public void setDateTimeTimestamp(int parameterIndex, long... ms) {
        setDateTime(parameterIndex, true, ms);
    }

    protected void setDateTime(int parameterIndex, boolean prefix, long... ms) {
        if (ms == null || ms.length == 0) {
            throw new IllegalArgumentException("Timestamp must be set!");
        }

        StringListBuilder slb = new StringListBuilder(",");
        for (long l : ms) {
            slb.add((prefix ? "TIMESTAMP " : "") + convert(new Date(l)));
        }

        parametersMap.put(parameterIndex, slb.toString());
    }

    @Override
    public String toQueryString() {
        boolean inStr = false;
        int parameterIndex = 0;

        StringBuilder sb = new StringBuilder(1024);
        for (int i = 0; i < statement.length(); i++) {
            char c = statement.charAt(i);

            if (c == '\'') {
                if (inStr && statement.charAt(i - 1) == '\\') {
                    inStr = true;
                } else {
                    inStr = !inStr;
                }
                sb.append(c);
            } else if (c == '?' && !inStr) {
                parameterIndex++;
                String s = parametersMap.get(parameterIndex);
                if (s == null) {
                    sb.append(c);
                } else {
                    sb.append(s);
                }
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    @Override
    public ItemIterable<QueryResult> query() {
        return session.query(toQueryString(), false);
    }

    @Override
    public ItemIterable<QueryResult> query(boolean searchAllVersions) {
        return session.query(toQueryString(), searchAllVersions);
    }

    @Override
    public ItemIterable<QueryResult> query(boolean searchAllVersions, OperationContext context) {
        return session.query(toQueryString(), searchAllVersions, context);
    }

    @Override
    protected QueryStatementImpl clone() throws CloneNotSupportedException {
        QueryStatementImpl qs = new QueryStatementImpl(session, statement);
        qs.parametersMap.putAll(parametersMap);

        return qs;
    }

    @Override
    public String toString() {
        return toQueryString();
    }

    // --- internal ---

    private static String escape(String str) {
        StringBuilder sb = new StringBuilder(str.length() + 16);

        sb.append('\'');

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);

            if (c == '\'' || c == '\\') {
                sb.append('\\');
            }

            sb.append(c);
        }

        sb.append('\'');

        return sb.toString();
    }

    private static String escapeLike(String str) {
        StringBuilder sb = new StringBuilder(str.length() + 16);

        sb.append('\'');

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);

            if (c == '\'') {
                sb.append('\\');
            } else if (c == '\\') {
                if (i + 1 < str.length() && (str.charAt(i + 1) == '%' || str.charAt(i + 1) == '_')) {
                    // no additional back slash
                } else {
                    sb.append('\\');
                }
            }

            sb.append(c);
        }

        sb.append('\'');

        return sb.toString();
    }

    private static String escapeContains(String str) {
        StringBuilder sb = new StringBuilder(str.length() + 64);

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);

            if (c == '\\') {
                sb.append('\\');
            } else if (c == '\'' || c == '\"') {
                sb.append('\\');
            } else if (c == '-') {
                if (i > 0) {
                    char cb = str.charAt(i - 1);
                    if (cb == '\\') {
                        sb.deleteCharAt(sb.length() - 1);
                    } else if (cb != ' ') {
                        sb.append('\\');
                    }
                }
            }

            sb.append(c);
        }

        return escape(sb.toString());
    }

    private static String convert(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        sdf.setTimeZone(DateTimeHelper.GMT);

        return "'" + sdf.format(date) + "'";
    }
}

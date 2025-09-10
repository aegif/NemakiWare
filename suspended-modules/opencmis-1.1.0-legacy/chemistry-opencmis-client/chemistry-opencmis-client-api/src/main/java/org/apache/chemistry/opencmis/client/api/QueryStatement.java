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
package org.apache.chemistry.opencmis.client.api;

import java.net.URI;
import java.net.URL;
import java.util.Calendar;
import java.util.Date;

import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;

/**
 * Query Statement.
 * <p>
 * Sample code:
 * 
 * <pre>
 * Calendar cal = ...
 * Folder folder = ...
 * 
 * QueryStatement qs = 
 *   session.createQueryStatement("SELECT ?, ? FROM ? WHERE ? &gt; TIMESTAMP ? AND IN_FOLDER(?) OR ? IN (?)");
 * 
 * qs.setProperty(1, "cmis:document", "cmis:name");
 * qs.setProperty(2, "cmis:document", "cmis:objectId");
 * qs.setType(3, "cmis:document");
 * 
 * qs.setProperty(4, "cmis:document", "cmis:creationDate");
 * qs.setDateTime(5, cal);
 * 
 * qs.setId(6, folder);
 * 
 * qs.setProperty(7, "cmis:document", "cmis:createdBy");
 * qs.setString(8, "bob", "tom", "lisa"); 
 * 
 * String statement = qs.toQueryString();
 * </pre>
 */
public interface QueryStatement extends Cloneable {

    /**
     * Sets the designated parameter to the query name of the given type ID.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param typeId
     *            the type ID
     */
    void setType(int parameterIndex, String typeId);

    /**
     * Sets the designated parameter to the query name of the given type.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param type
     *            the object type
     */
    void setType(int parameterIndex, ObjectType type);

    /**
     * Sets the designated parameter to the query name of the given property.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param propertyId
     *            the property ID
     */
    void setProperty(int parameterIndex, String typeId, String propertyId);

    /**
     * Sets the designated parameter to the query name of the given property.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param propertyDefinition
     *            the property definition
     */
    void setProperty(int parameterIndex, PropertyDefinition<?> propertyDefinition);

    /**
     * Sets the designated parameter to the given number.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param num
     *            the number
     */
    void setNumber(int parameterIndex, Number... num);

    /**
     * Sets the designated parameter to the given string.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param str
     *            the string
     */
    void setString(int parameterIndex, String... str);

    /**
     * Sets the designated parameter to the given string. It does not escape
     * backslashes ('\') in front of '%' and '_'.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param str
     *            the LIKE string
     */
    void setStringLike(int parameterIndex, String str);

    /**
     * Sets the designated parameter to the given string in a CMIS contains
     * statement.
     * <p>
     * Note that the CMIS specification requires two levels of escaping. The
     * first level escapes ', ", \ characters to \', \" and \\. The characters
     * *, ? and - are interpreted as text search operators and are not escaped
     * on first level. If *, ?, - shall be used as literals, they must be passed
     * escaped with \*, \? and \- to this method.
     * <p>
     * For all statements in a CONTAINS() clause it is required to isolate those
     * from a query statement. Therefore a second level escaping is performed.
     * On the second level grammar ", ', - and \ are escaped with a \. See the
     * spec for further details.
     * <p>
     * 
     * Summary:
     * <table summary="Escaping Summary">
     * <tr>
     * <th>input</th>
     * <th>first level escaping</th>
     * <th>second level escaping</th>
     * </tr>
     * <tr>
     * <td>*</td>
     * <td>*</td>
     * <td>*</td>
     * </tr>
     * <tr>
     * <td>?</td>
     * <td>?</td>
     * <td>?</td>
     * </tr>
     * <tr>
     * <td>-</td>
     * <td>-</td>
     * <td>-</td>
     * </tr>
     * <tr>
     * <td>\</td>
     * <td>\\</td>
     * <td>\\\\<br>
     * <em>(for any other character following other than * ? -)</em></td>
     * </tr>
     * <tr>
     * <td>\*</td>
     * <td>\*</td>
     * <td>\\*</td>
     * </tr>
     * <tr>
     * <td>\?</td>
     * <td>\?</td>
     * <td>\\?</td>
     * </tr>
     * <tr>
     * <td>\-</td>
     * <td>\-</td>
     * <td>\\-+</td>
     * </tr>
     * <tr>
     * <td>'</td>
     * <td>\'</td>
     * <td>\\\'</td>
     * </tr>
     * <tr>
     * <td>"</td>
     * <td>\"</td>
     * <td>\\\"</td>
     * </tr>
     * </table>
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param str
     *            the CONTAINS string
     */
    void setStringContains(int parameterIndex, String str);

    /**
     * Sets the designated parameter to the given object ID.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param id
     *            the object ID
     */
    void setId(int parameterIndex, ObjectId... id);

    /**
     * Sets the designated parameter to the given URI.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param uri
     *            the URI
     */
    void setUri(int parameterIndex, URI... uri);

    /**
     * Sets the designated parameter to the given URL.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param url
     *            the URL
     */
    void setUrl(int parameterIndex, URL... url);

    /**
     * Sets the designated parameter to the given boolean.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param bool
     *            the boolean
     */
    void setBoolean(int parameterIndex, boolean... bool);

    /**
     * Sets the designated parameter to the given DateTime value.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param cal
     *            the DateTime value as Calendar object
     */
    void setDateTime(int parameterIndex, Calendar... cal);

    /**
     * Sets the designated parameter to the given DateTime value.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param date
     *            the DateTime value as Date object
     */
    void setDateTime(int parameterIndex, Date... date);

    /**
     * Sets the designated parameter to the given DateTime value.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param ms
     *            the DateTime value in milliseconds from midnight, January 1,
     *            1970 UTC.
     */
    void setDateTime(int parameterIndex, long... ms);

    /**
     * Sets the designated parameter to the given DateTime value with the prefix
     * 'TIMESTAMP '.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param cal
     *            the DateTime value as Calendar object
     */
    void setDateTimeTimestamp(int parameterIndex, Calendar... cal);

    /**
     * Sets the designated parameter to the given DateTime value with the prefix
     * 'TIMESTAMP '.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param date
     *            the DateTime value as Date object
     */
    void setDateTimeTimestamp(int parameterIndex, Date... date);

    /**
     * Sets the designated parameter to the given DateTime value with the prefix
     * 'TIMESTAMP '.
     * 
     * @param parameterIndex
     *            the parameter index (one-based)
     * @param ms
     *            the DateTime value in milliseconds from midnight, January 1,
     *            1970 UTC.
     */
    void setDateTimeTimestamp(int parameterIndex, long... ms);

    /**
     * Returns the query statement.
     * 
     * @return the query statement, not {@code null}
     */
    String toQueryString();

    /**
     * Executes the query with {@code searchAllVersions} set to {@code false}.
     * 
     * @see Session#query(String, boolean)
     */
    ItemIterable<QueryResult> query();

    /**
     * Executes the query.
     * 
     * @param searchAllVersions
     *            {@code true} if all document versions should be included in
     *            the search results, {@code false} if only the latest document
     *            versions should be included in the search results
     * 
     * 
     * @see Session#query(String, boolean)
     */
    ItemIterable<QueryResult> query(boolean searchAllVersions);

    /**
     * Executes the query.
     * 
     * @param searchAllVersions
     *            {@code true} if all document versions should be included in
     *            the search results, {@code false} if only the latest document
     *            versions should be included in the search results
     * @param context
     *            the operation context to use
     * 
     * @see Session#query(String, boolean, OperationContext)
     */
    ItemIterable<QueryResult> query(boolean searchAllVersions, OperationContext context);
}

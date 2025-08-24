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
package org.apache.chemistry.opencmis.server.support.query;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestParserExt extends AbstractParserTest {

    @Before
    public void setUp() throws Exception {
        super.setUp(CmisQlExtLexer.class, CmisQlExtParser.class, null, "CmisBaseLexer");
    }

    @Override
    @After
    public void tearDown() {
        super.tearDown();
    }

    @Test
    public void test_predicate1() {
        testParser("value_expression", "LOWER(foo)", "(FUNC LOWER (COL foo))");
    }

    @Test
    public void test_query1() {
        testParser("query", "SELECT DISTINCT a, b, c FROM Document",
                "(SELECT DISTINCT (LIST (COL a) (COL b) (COL c)) (FROM (TABLE Document)))");
    }

    @Test
    public void test_query2() {
        testParserOk("query", "SELECT Y.CLAIM_NUM, X.PROPERTY_ADDRESS, Y.DAMAGE_ESTIMATES "
                + "FROM POLICY AS X JOIN CLAIMS AS Y ON X.POLICY_NUM = Y.POLICY_NUM "
                + "    WHERE ( 100000 <= ANY Y.DAMAGE_ESTIMATES ) AND ( Y.CAUSE NOT LIKE '%Katrina%' )");
    }

    @Test
    public void test_query3() {
        testParserOk("query", "SELECT OBJECT_ID, SCORE() AS X, DESTINATION, DEPARTURE_DATES " + "FROM TRAVEL_BROCHURE "
                + "WHERE ( CONTAINS('CARIBBEAN CENTRAL AMERICA CRUISE TOUR') ) AND( '2010-1-1' < ANY DEPARTURE_DATES )");
    }
}

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
package org.apache.chemistry.opencmis.commons.data;

import java.util.GregorianCalendar;

import org.apache.chemistry.opencmis.commons.enums.ChangeType;

/**
 * Basic change event.
 */
public interface ChangeEventInfo extends ExtensionsData {

    /**
     * Returns the change event type.
     * 
     * @return the change event type, not {@code null}
     */
    ChangeType getChangeType();

    /**
     * Returns when the change took place.
     * 
     * @return the timestamp of the change, not {@code null}
     */
    GregorianCalendar getChangeTime();
}

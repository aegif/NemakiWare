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

import java.util.GregorianCalendar;

import org.apache.chemistry.opencmis.commons.data.ChangeEventInfo;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;

/**
 * Change event.
 */
public class ChangeEventInfoDataImpl extends AbstractExtensionData implements ChangeEventInfo {

    private static final long serialVersionUID = 1L;

    private GregorianCalendar changeTime;
    private ChangeType changeType;

    public ChangeEventInfoDataImpl() {
    }

    public ChangeEventInfoDataImpl(ChangeType changeType, GregorianCalendar changeTime) {
        this.changeType = changeType;
        this.changeTime = changeTime;
    }

    @Override
    public GregorianCalendar getChangeTime() {
        return changeTime;
    }

    public void setChangeTime(GregorianCalendar changeTime) {
        this.changeTime = changeTime;
    }

    @Override
    public ChangeType getChangeType() {
        return changeType;
    }

    public void setChangeType(ChangeType changeType) {
        this.changeType = changeType;
    }
}

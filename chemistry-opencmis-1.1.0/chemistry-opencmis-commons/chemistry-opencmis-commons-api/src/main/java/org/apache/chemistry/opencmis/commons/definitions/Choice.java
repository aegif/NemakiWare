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
package org.apache.chemistry.opencmis.commons.definitions;

import java.io.Serializable;
import java.util.List;

/**
 * Choice value interface.
 * 
 * @cmis 1.0
 */
public interface Choice<T> extends Serializable {

    /**
     * Return the display name of the choice value.
     * 
     * @return the display name
     */
    String getDisplayName();

    /**
     * Return the value of the choice value. Single value properties return a
     * list with exactly one value.
     * 
     * @return the choice value
     */
    List<T> getValue();

    /**
     * Returns sub-choice if there is a hierarchy of choices.
     * 
     * @return the list of sub-choices
     */
    List<Choice<T>> getChoice();
}

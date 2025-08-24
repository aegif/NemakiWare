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
package org.apache.chemistry.opencmis.inmemory;

import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;

/**
 * A simple interface to create the repository info. This needs to be
 * implemented by a client (like a unit test) in class and the name of the class
 * is passed to the session. The in-memory repository creates an instance of
 * this class to generate the repository info for its instance.
 */
public interface RepositoryInfoCreator {
    RepositoryInfo createRepositoryInfo();
}

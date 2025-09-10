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
package org.apache.chemistry.opencmis.workbench;

import org.apache.chemistry.opencmis.tck.runner.AbstractRunner;

/**
 * Abstract TCK runner configurator.
 * 
 * To add a runner configurator, derive a class from this class, create a file
 * {@code META-INF/services/org.apache.chemistry.opencmis.workbench.AbstractTckRunnerConfigurator}
 * , and put the name of the fully qualified name of your class into this file.
 */
public abstract class AbstractTckRunnerConfigurator {

    /**
     * Called at setup.
     * 
     * This method can be used to register custom TCK tests.
     * 
     * @param runner
     *            the TCK runner object
     */
    public void configureRunner(AbstractRunner runner) throws Exception {
        runner.loadDefaultTckGroups();
    }

    /**
     * Called before the TCK test is started.
     * 
     * @param runner
     *            the TCK runner object
     */
    public void beforeRun(AbstractRunner runner) {
    }

    /**
     * Called after the TCK test has finished.
     * 
     * This method can be used to create a custom report.
     * 
     * @param runner
     *            the TCK runner object
     */
    public void afterRun(AbstractRunner runner) {
    }
}

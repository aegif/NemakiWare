/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.commons.server;

/**
 * CmisService classes that implement this interface can control whether the
 * server framework continues or stops processing of the request.
 * 
 * The server framework calls {@link #beforeServiceCall()} before the requested
 * service method is called and calls {@link #afterServiceCall()} after the
 * requested service method is called. Both methods return a {@link Progress}
 * enum value. If the return value is {@link Progress#CONTINUE} , the server
 * framework continues processing. If the return value is {@link Progress#STOP},
 * the server framework stops processing.
 * 
 * If the service method throws an exception, {@link #afterServiceCall()} is not
 * called and the server framework or a service wrapper handles the exception.
 * 
 * The effect of stopping the processing is binding specific. <b>Dealing with
 * that requires good knowledge of CMIS, HTTP, and SOAP.</b>
 * 
 * If an AtomPub or Browser Binding request is stopped, the framework doesn't
 * send any HTTP response. It's the responsibility of the {@link CmisService}
 * class or a service wrapper to generate a CMIS compliant HTTP response. If a
 * Web Service Binding request is stopped, an additional MessageHandler must be
 * installed that handles the situation. Otherwise the service sends an invalid
 * response. If a Local Binding request is stopped, the behavior is undefined.
 * Custom binding implementations might not support this interface at all.
 * 
 * Depending on the binding and the service method, the {@link CmisService}
 * object is called multiple times. Only the method requested by the client is
 * accompanied by {@link #beforeServiceCall()} and {@link #afterServiceCall()}
 * calls. The framework might call other service methods before, after, and in
 * between these two methods.
 */
public interface ProgressControlCmisService extends CmisService {

    public enum Progress {
        CONTINUE, STOP
    };

    /**
     * Called by the server framework before the requested service method is
     * called.
     * 
     * @return {@link Progress#CONTINUE} if the server framework should continue
     *         processing the request, {@link Progress#STOP} id the server
     *         framework should stop
     */
    Progress beforeServiceCall();

    /**
     * Called by the server framework after the requested service method has
     * been called.
     * 
     * @return {@link Progress#CONTINUE} if the server framework should continue
     *         processing the request, {@link Progress#STOP} id the server
     *         framework should stop
     */
    Progress afterServiceCall();
}

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
package org.apache.chemistry.opencmis.server.impl.webservices;

import java.util.Iterator;

import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.binding.soap.interceptor.AbstractSoapInterceptor;
import org.apache.cxf.binding.soap.interceptor.SoapActionInInterceptor;
import org.apache.cxf.interceptor.Interceptor;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.Phase;

/**
 * Removes the CXF SOAP Action handling to be backwards compatible with the
 * OpenCMIS server framework 0.13.0 and earlier.
 */
public class SoapActionRemoveInterceptor extends AbstractSoapInterceptor {

    public SoapActionRemoveInterceptor() {
        super(Phase.READ);
        addBefore(SoapActionInInterceptor.class.getName());
    }

    @Override
    public void handleMessage(SoapMessage message) {
        Interceptor<? extends Message> soapActionInInterceptor = null;
        Iterator<Interceptor<? extends Message>> iterator = message.getInterceptorChain().getIterator();
        while (iterator.hasNext()) {
            Interceptor<? extends Message> interceptor = iterator.next();
            if (interceptor instanceof SoapActionInInterceptor) {
                soapActionInInterceptor = interceptor;
                break;
            }
        }

        if (soapActionInInterceptor != null) {
            message.getInterceptorChain().remove(soapActionInInterceptor);
        }
    }
}

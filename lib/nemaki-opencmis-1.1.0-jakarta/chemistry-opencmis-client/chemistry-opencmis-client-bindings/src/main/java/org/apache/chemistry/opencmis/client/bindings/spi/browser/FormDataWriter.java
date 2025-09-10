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
package org.apache.chemistry.opencmis.client.bindings.spi.browser;

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNotEmpty;
import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNullOrEmpty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.DateTimeFormat;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.DateTimeHelper;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.MimeHelper;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;

public final class FormDataWriter {

    private static final String CONTENT_TYPE_URLENCODED = "application/x-www-form-urlencoded;charset=utf-8";
    private static final String CONTENT_TYPE_FORMDATA = "multipart/form-data; boundary=";
    private static final String CRLF = "\r\n";
    private static final int BUFFER_SIZE = 64 * 1024;

    private final String boundary;
    private final Map<String, String> parameters = new LinkedHashMap<String, String>();
    private ContentStream contentStream;

    public FormDataWriter(String action) {
        this(action, null);
    }

    public FormDataWriter(String action, ContentStream contentStream) {
        addParameter(Constants.CONTROL_CMISACTION, action);
        this.contentStream = contentStream;
        boundary = "aPacHeCheMIStryoPEncmiS" + Long.toHexString(action.hashCode()) + action
                + Long.toHexString(System.currentTimeMillis()) + Long.toHexString(this.hashCode());
    }

    public void addParameter(String name, Object value) {
        if (name == null || value == null) {
            return;
        }

        parameters.put(name, UrlBuilder.normalizeParameter(value));
    }

    public void addPropertiesParameters(Properties properties, DateTimeFormat dateTimeFormat) {
        if (properties == null) {
            return;
        }

        int idx = 0;
        for (PropertyData<?> prop : properties.getPropertyList()) {
            if (prop == null) {
                continue;
            }

            String idxStr = "[" + idx + "]";
            addParameter(Constants.CONTROL_PROP_ID + idxStr, prop.getId());

            if (isNotEmpty(prop.getValues())) {
                if (prop.getValues().size() == 1) {
                    addParameter(Constants.CONTROL_PROP_VALUE + idxStr,
                            convertPropertyValue(prop.getFirstValue(), dateTimeFormat));
                } else {
                    int vidx = 0;
                    for (Object obj : prop.getValues()) {
                        String vidxStr = "[" + vidx + "]";
                        addParameter(Constants.CONTROL_PROP_VALUE + idxStr + vidxStr,
                                convertPropertyValue(obj, dateTimeFormat));
                        vidx++;
                    }
                }
            }

            idx++;
        }
    }

    public void addSuccinctFlag(boolean succinct) {
        if (succinct) {
            addParameter(Constants.CONTROL_SUCCINCT, "true");
        }
    }

    public void addPoliciesParameters(List<String> policies) {
        if (policies == null) {
            return;
        }

        int idx = 0;
        for (String policy : policies) {
            if (policy != null) {
                String idxStr = "[" + idx + "]";
                addParameter(Constants.CONTROL_POLICY + idxStr, policy);
                idx++;
            }
        }
    }

    public void addPolicyIdParameter(String policyId) {
        if (policyId == null) {
            return;
        }

        addParameter(Constants.CONTROL_POLICY_ID, policyId);
    }

    public void addAddAcesParameters(Acl acl) {
        addAcesParameters(acl, Constants.CONTROL_ADD_ACE_PRINCIPAL, Constants.CONTROL_ADD_ACE_PERMISSION);
    }

    public void addRemoveAcesParameters(Acl acl) {
        addAcesParameters(acl, Constants.CONTROL_REMOVE_ACE_PRINCIPAL, Constants.CONTROL_REMOVE_ACE_PERMISSION);
    }

    private void addAcesParameters(Acl acl, String principalControl, String permissionControl) {
        if (acl == null || acl.getAces() == null) {
            return;
        }

        int idx = 0;
        for (Ace ace : acl.getAces()) {
            if (ace.getPrincipalId() != null && isNotEmpty(ace.getPermissions())) {
                String idxStr = "[" + idx + "]";
                addParameter(principalControl + idxStr, ace.getPrincipalId());

                int permIdx = 0;
                for (String perm : ace.getPermissions()) {
                    if (perm != null) {
                        String permIdxStr = "[" + permIdx + "]";
                        addParameter(permissionControl + idxStr + permIdxStr, perm);
                        permIdx++;
                    }
                }
                idx++;
            }
        }
    }

    public void addObjectIdsAndChangeTokens(List<BulkUpdateObjectIdAndChangeToken> objectIdsAndChangeTokens) {
        if (isNullOrEmpty(objectIdsAndChangeTokens)) {
            return;
        }

        int idx = 0;
        for (BulkUpdateObjectIdAndChangeToken oc : objectIdsAndChangeTokens) {
            if (oc == null || oc.getId() == null || oc.getId().length() == 0) {
                continue;
            }

            String idxStr = "[" + idx + "]";
            addParameter(Constants.CONTROL_OBJECT_ID + idxStr, oc.getId());
            addParameter(Constants.CONTROL_CHANGE_TOKEN + idxStr,
                    (oc.getChangeToken() == null ? "" : oc.getChangeToken()));

            idx++;
        }
    }

    public void addSecondaryTypeIds(List<String> secondaryTypeIds) {
        addSecondaryTypeIdParameters(secondaryTypeIds, Constants.CONTROL_ADD_SECONDARY_TYPE);
    }

    public void removeSecondaryTypeIds(List<String> secondaryTypeIds) {
        addSecondaryTypeIdParameters(secondaryTypeIds, Constants.CONTROL_REMOVE_SECONDARY_TYPE);
    }

    private void addSecondaryTypeIdParameters(List<String> secondaryTypeIds, String secondaryTypeIdControl) {
        if (isNullOrEmpty(secondaryTypeIds)) {
            return;
        }

        int idx = 0;
        for (String typeId : secondaryTypeIds) {
            if (typeId == null || typeId.length() == 0) {
                continue;
            }

            String idxStr = "[" + idx + "]";
            addParameter(secondaryTypeIdControl + idxStr, typeId);

            idx++;
        }
    }

    private String convertPropertyValue(Object value, DateTimeFormat dateTimeFormat) {
        if (value == null) {
            return null;
        }

        if (value instanceof GregorianCalendar) {
            if (dateTimeFormat == DateTimeFormat.EXTENDED) {
                return DateTimeHelper.formatXmlDateTime((GregorianCalendar) value);
            } else {
                return String.valueOf(((GregorianCalendar) value).getTimeInMillis());
            }
        }

        return value.toString();
    }

    public String getContentType() {
        return contentStream == null ? CONTENT_TYPE_URLENCODED : CONTENT_TYPE_FORMDATA + boundary;
    }

    public void write(OutputStream out) throws IOException {
        InputStream stream = contentStream == null ? null : contentStream.getStream();

        if (stream == null) {
            boolean first = true;
            byte[] amp = IOUtils.toUTF8Bytes("&");

            for (Map.Entry<String, String> param : parameters.entrySet()) {
                if (first) {
                    first = false;
                } else {
                    out.write(amp);
                }

                out.write(IOUtils.toUTF8Bytes(param.getKey() + "=" + URLEncoder.encode(param.getValue(), IOUtils.UTF8)));
            }
        } else {
            writeLine(out);

            // parameters
            for (Map.Entry<String, String> param : parameters.entrySet()) {
                writeLine(out, "--" + boundary);
                writeLine(out, "Content-Disposition: form-data; name=\"" + param.getKey() + "\"");
                writeLine(out, "Content-Type: text/plain; charset=utf-8");
                writeLine(out);
                writeLine(out, param.getValue());
            }

            // content
            String filename = contentStream.getFileName();
            if (filename == null || filename.length() == 0) {
                filename = "content";
            }

            String mediaType = contentStream.getMimeType();
            if (mediaType == null || mediaType.indexOf('/') < 1 || mediaType.indexOf('\n') > -1
                    || mediaType.indexOf('\r') > -1) {
                mediaType = Constants.MEDIATYPE_OCTETSTREAM;
            }

            writeLine(out, "--" + boundary);
            writeLine(
                    out,
                    "Content-Disposition: "
                            + MimeHelper.encodeContentDisposition(MimeHelper.DISPOSITION_FORM_DATA_CONTENT, filename));
            writeLine(out, "Content-Type: " + mediaType);
            writeLine(out, "Content-Transfer-Encoding: binary");
            writeLine(out);

            IOUtils.copy(stream, out, BUFFER_SIZE);

            writeLine(out);
            writeLine(out, "--" + boundary + "--");
        }
    }

    private void writeLine(OutputStream out) throws IOException {
        writeLine(out, null);
    }

    private void writeLine(OutputStream out, String s) throws IOException {
        out.write(IOUtils.toUTF8Bytes(s == null ? CRLF : s + CRLF));
    }
}

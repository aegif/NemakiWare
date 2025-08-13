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
package org.apache.chemistry.opencmis.server.impl.atompub;

import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_ACL;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_ALLOWABLEACIONS;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_BULK_UPDATE;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_CHANGES;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_CHECKEDOUT;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_CHILDREN;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_CONTENT;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_DESCENDANTS;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_ENTRY;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_FOLDERTREE;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_OBJECTBYID;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_OBJECTBYPATH;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_PARENT;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_PARENTS;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_POLICIES;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_QUERY;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_RELATIONSHIPS;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_TYPE;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_TYPES;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_TYPESDESC;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_UNFILED;
import static org.apache.chemistry.opencmis.server.impl.atompub.AbstractAtomPubServiceCall.RESOURCE_VERSIONS;
import static org.apache.chemistry.opencmis.server.shared.Dispatcher.METHOD_DELETE;
import static org.apache.chemistry.opencmis.server.shared.Dispatcher.METHOD_GET;
import static org.apache.chemistry.opencmis.server.shared.Dispatcher.METHOD_HEAD;
import static org.apache.chemistry.opencmis.server.shared.Dispatcher.METHOD_POST;
import static org.apache.chemistry.opencmis.server.shared.Dispatcher.METHOD_PUT;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisFilterNotValidException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNameConstraintViolationException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisServiceUnavailableException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisStorageException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisStreamNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisTooManyRequestsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUnauthorizedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.server.impl.ServerVersion;
import org.apache.chemistry.opencmis.server.shared.AbstractCmisHttpServlet;
import org.apache.chemistry.opencmis.server.shared.Dispatcher;
import org.apache.chemistry.opencmis.server.shared.ExceptionHelper;
import org.apache.chemistry.opencmis.server.shared.HEADHttpServletRequestWrapper;
import org.apache.chemistry.opencmis.server.shared.HttpUtils;
import org.apache.chemistry.opencmis.server.shared.NoBodyHttpServletResponseWrapper;
import org.apache.chemistry.opencmis.server.shared.QueryStringHttpServletRequestWrapper;
import org.apache.chemistry.opencmis.server.shared.ServiceCall;
import org.apache.chemistry.opencmis.server.shared.TempStoreOutputStreamFactory;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CMIS AtomPub servlet.
 */
public class CmisAtomPubServlet extends AbstractCmisHttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(CmisAtomPubServlet.class);

    private static final String OPENCMIS_CSS_STYLE = "<style>"
            + "<!--H1 {font-size:24px;line-height:normal;font-weight:bold;background-color:#f0f0f0;color:#003366;border-bottom:1px solid #3c78b5;padding:2px;} "
            + "BODY {font-family:Verdana,arial,sans-serif;color:black;font-size:14px;} "
            + "HR {color:#3c78b5;height:1px;}--></style>";

    private static final long serialVersionUID = 1L;

    private final Dispatcher dispatcher = new Dispatcher();

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        // set the binding
        setBinding(CallContext.BINDING_ATOMPUB);

        // get and CMIS version
        String cmisVersionStr = config.getInitParameter(PARAM_CMIS_VERSION);
        if (cmisVersionStr != null) {
            try {
                setCmisVersion(CmisVersion.fromValue(cmisVersionStr));
            } catch (IllegalArgumentException e) {
                LOG.warn("CMIS version is invalid! Setting it to CMIS 1.0.");
                setCmisVersion(CmisVersion.CMIS_1_0);
            }
        } else {
            LOG.warn("CMIS version is not defined! Setting it to CMIS 1.0.");
            setCmisVersion(CmisVersion.CMIS_1_0);
        }

        // initialize resources
        addResource("", METHOD_GET, new RepositoryService.GetRepositories());
        addResource(RESOURCE_TYPES, METHOD_GET, new RepositoryService.GetTypeChildren());
        addResource(RESOURCE_TYPES, METHOD_POST, new RepositoryService.CreateType());
        addResource(RESOURCE_TYPESDESC, METHOD_GET, new RepositoryService.GetTypeDescendants());
        addResource(RESOURCE_TYPE, METHOD_GET, new RepositoryService.GetTypeDefinition());
        addResource(RESOURCE_TYPE, METHOD_PUT, new RepositoryService.UpdateType());
        addResource(RESOURCE_TYPE, METHOD_DELETE, new RepositoryService.DeleteType());
        addResource(RESOURCE_CHILDREN, METHOD_GET, new NavigationService.GetChildren());
        addResource(RESOURCE_DESCENDANTS, METHOD_GET, new NavigationService.GetDescendants());
        addResource(RESOURCE_FOLDERTREE, METHOD_GET, new NavigationService.GetFolderTree());
        addResource(RESOURCE_PARENT, METHOD_GET, new NavigationService.GetFolderParent());
        addResource(RESOURCE_PARENTS, METHOD_GET, new NavigationService.GetObjectParents());
        addResource(RESOURCE_CHECKEDOUT, METHOD_GET, new NavigationService.GetCheckedOutDocs());
        addResource(RESOURCE_ENTRY, METHOD_GET, new ObjectService.GetObject());
        addResource(RESOURCE_OBJECTBYID, METHOD_GET, new ObjectService.GetObject());
        addResource(RESOURCE_OBJECTBYPATH, METHOD_GET, new ObjectService.GetObjectByPath());
        addResource(RESOURCE_ALLOWABLEACIONS, METHOD_GET, new ObjectService.GetAllowableActions());
        addResource(RESOURCE_CONTENT, METHOD_GET, new ObjectService.GetContentStream());
        addResource(RESOURCE_CONTENT, METHOD_PUT, new ObjectService.SetOrAppendContentStream());
        addResource(RESOURCE_CONTENT, METHOD_DELETE, new ObjectService.DeleteContentStream());
        addResource(RESOURCE_CHILDREN, METHOD_POST, new ObjectService.Create());
        addResource(RESOURCE_RELATIONSHIPS, METHOD_POST, new ObjectService.CreateRelationship());
        addResource(RESOURCE_ENTRY, METHOD_PUT, new ObjectService.UpdateProperties());
        addResource(RESOURCE_ENTRY, METHOD_DELETE, new ObjectService.DeleteObject());
        addResource(RESOURCE_CHILDREN, METHOD_DELETE, new ObjectService.DeleteTree()); // 1.1
        addResource(RESOURCE_DESCENDANTS, METHOD_DELETE, new ObjectService.DeleteTree());
        addResource(RESOURCE_FOLDERTREE, METHOD_DELETE, new ObjectService.DeleteTree());
        addResource(RESOURCE_BULK_UPDATE, METHOD_POST, new ObjectService.BulkUpdateProperties());
        addResource(RESOURCE_CHECKEDOUT, METHOD_POST, new VersioningService.CheckOut());
        addResource(RESOURCE_VERSIONS, METHOD_GET, new VersioningService.GetAllVersions());
        addResource(RESOURCE_VERSIONS, METHOD_DELETE, new VersioningService.DeleteAllVersions());
        addResource(RESOURCE_QUERY, METHOD_GET, new DiscoveryService.Query());
        addResource(RESOURCE_QUERY, METHOD_POST, new DiscoveryService.Query());
        addResource(RESOURCE_CHANGES, METHOD_GET, new DiscoveryService.GetContentChanges());
        addResource(RESOURCE_RELATIONSHIPS, METHOD_GET, new RelationshipService.GetObjectRelationships());
        addResource(RESOURCE_UNFILED, METHOD_POST, new MultiFilingService.RemoveObjectFromFolder());
        addResource(RESOURCE_ACL, METHOD_GET, new AclService.GetAcl());
        addResource(RESOURCE_ACL, METHOD_PUT, new AclService.ApplyAcl());
        addResource(RESOURCE_POLICIES, METHOD_GET, new PolicyService.GetAppliedPolicies());
        addResource(RESOURCE_POLICIES, METHOD_POST, new PolicyService.ApplyPolicy());
        addResource(RESOURCE_POLICIES, METHOD_DELETE, new PolicyService.RemovePolicy());
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException,
            IOException {
        CallContext context = null;

        boolean flush = true;
        try {
            // CSRF token check
            if (!METHOD_GET.equals(request.getMethod()) && !METHOD_HEAD.equals(request.getMethod())) {
                checkCsrfToken(request, response, false, false);
            }

            // split path
            String[] pathFragments = HttpUtils.splitPath(request);

            // create stream factory
            TempStoreOutputStreamFactory streamFactoy = TempStoreOutputStreamFactory.newInstance(getServiceFactory(),
                    pathFragments.length > 0 ? pathFragments[0] : null, request);

            // treat HEAD requests
            if (METHOD_HEAD.equals(request.getMethod())) {
                request = new HEADHttpServletRequestWrapper(request);
                response = new NoBodyHttpServletResponseWrapper(response);
            } else {
                request = new QueryStringHttpServletRequestWrapper(request);
            }

            // set default headers
            response.addHeader("Cache-Control", "private, max-age=0");
            response.addHeader("Server", ServerVersion.OPENCMIS_SERVER);

            context = createContext(getServletContext(), request, response, streamFactoy);
            dispatch(context, request, response, pathFragments);
        } catch (Exception e) {
            if (e instanceof CmisUnauthorizedException) {
                response.setHeader("WWW-Authenticate", "Basic realm=\"CMIS\", charset=\"UTF-8\"");
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authorization Required");
            } else if (e instanceof CmisPermissionDeniedException) {
                if (context == null || (context.getUsername() == null)) {
                    response.setHeader("WWW-Authenticate", "Basic realm=\"CMIS\", charset=\"UTF-8\"");
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authorization Required");
                } else {
                    printError(e, request, response);
                }
            } else {
                // an IOException usually indicates that reading the request or
                // sending the response failed
                // flushing will probably fail and raise a new exception ->
                // avoid flushing
                flush = !(e instanceof IOException);

                printError(e, request, response);
            }

        } catch (Error err) {
            LOG.error(createLogMessage(err, request), err);

            try {
                response.resetBuffer();
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("text/html");
                response.setCharacterEncoding(IOUtils.UTF8);

                PrintWriter pw = response.getWriter();
                writeHtmlErrorPage(pw, 500, "runtime", "An error occurred!", err);
                pw.flush();
            } catch (Exception te) {
                // we tried to send an error message but it failed.
                // there is nothing we can do...
                flush = false;
            }

            throw err;
        } finally {
            // we are done.
            if (flush) {
                try {
                    response.flushBuffer();
                } catch (IOException ioe) {
                    LOG.error("Could not flush resposne: {}", ioe.toString(), ioe);
                }
            }
        }
    }

    /**
     * Registers a new resource.
     */
    protected void addResource(String resource, String httpMethod, ServiceCall serviceCall) {
        dispatcher.addResource(resource, httpMethod, serviceCall);
    }

    /**
     * Dispatches to feed, entry or whatever.
     */
    private void dispatch(CallContext context, HttpServletRequest request, HttpServletResponse response,
            String[] pathFragments) throws Exception {

        CmisService service = null;
        try {
            // get the service
            service = getServiceFactory().getService(context);

            // analyze the path
            if (pathFragments.length < 2) {
                // CSRF check
                checkCsrfToken(request, response, true, false);

                // root -> service document
                dispatcher.dispatch("", METHOD_GET, context, service, null, request, response);
                return;
            }

            String method = request.getMethod();
            String repositoryId = pathFragments[0];
            String resource = pathFragments[1];

            // CSRF check
            checkCsrfToken(request, response, false, RESOURCE_CONTENT.equals(resource) && METHOD_GET.equals(method));

            // dispatch
            boolean callServiceFound = dispatcher.dispatch(resource, method, context, service, repositoryId, request,
                    response);

            // if the dispatcher couldn't find a matching service
            // -> return an error message
            if (!callServiceFound) {
                response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Unknown operation");
            }
        } finally {
            if (service != null) {
                service.close();
            }
        }
    }

    /**
     * Translates an exception in an appropriate HTTP error code.
     */
    protected int getErrorCode(CmisBaseException ex) {
        if (ex instanceof CmisConstraintException) {
            return 409;
        } else if (ex instanceof CmisContentAlreadyExistsException) {
            return 409;
        } else if (ex instanceof CmisFilterNotValidException) {
            return 400;
        } else if (ex instanceof CmisInvalidArgumentException) {
            return 400;
        } else if (ex instanceof CmisNameConstraintViolationException) {
            return 409;
        } else if (ex instanceof CmisNotSupportedException) {
            return 405;
        } else if (ex instanceof CmisObjectNotFoundException) {
            return 404;
        } else if (ex instanceof CmisPermissionDeniedException) {
            return 403;
        } else if (ex instanceof CmisStorageException) {
            return 500;
        } else if (ex instanceof CmisStreamNotSupportedException) {
            return 403;
        } else if (ex instanceof CmisUpdateConflictException) {
            return 409;
        } else if (ex instanceof CmisVersioningException) {
            return 409;
        } else if (ex instanceof CmisTooManyRequestsException) {
            return 429;
        } else if (ex instanceof CmisServiceUnavailableException) {
            return 503;
        }

        return 500;
    }

    /**
     * Prints the error HTML page.
     */
    protected void printError(Exception ex, HttpServletRequest request, HttpServletResponse response) {
        int statusCode = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        String exceptionName = "runtime";

        if (ex instanceof CmisRuntimeException) {
            LOG.error(createLogMessage(ex, request), ex);
            statusCode = getErrorCode((CmisRuntimeException) ex);
        } else if (ex instanceof CmisStorageException) {
            LOG.error(createLogMessage(ex, request), ex);
            statusCode = getErrorCode((CmisStorageException) ex);
            exceptionName = ((CmisStorageException) ex).getExceptionName();
        } else if (ex instanceof CmisBaseException) {
            statusCode = getErrorCode((CmisBaseException) ex);
            exceptionName = ((CmisBaseException) ex).getExceptionName();

            if (statusCode == HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
                LOG.error(createLogMessage(ex, request), ex);
            }
        } else if (ex instanceof IOException) {
            LOG.warn(createLogMessage(ex, request), ex);
        } else {
            LOG.error(createLogMessage(ex, request), ex);
        }

        if (response.isCommitted()) {
            LOG.warn("Failed to send error message to client. Response is already committed.", ex);
            return;
        }

        String message = ex.getMessage();
        if (!(ex instanceof CmisBaseException)) {
            message = "An error occurred!";
        }

        try {
            response.resetBuffer();
            response.setStatus(statusCode);
            response.setContentType("text/html");
            response.setCharacterEncoding(IOUtils.UTF8);

            PrintWriter pw = response.getWriter();
            writeHtmlErrorPage(pw, statusCode, exceptionName, message, ex);
            pw.flush();
        } catch (Exception e) {
            LOG.error(createLogMessage(ex, request), e);
            try {
                response.sendError(statusCode, message);
            } catch (Exception en) {
                // there is nothing else we can do
            }
        }
    }

    protected void writeHtmlErrorPage(PrintWriter pw, int statusCode, String exceptionName, String message, Throwable t)
            throws IOException {
        pw.print("<html><head><title>Apache Chemistry OpenCMIS - " + exceptionName + " error</title>"
                + OPENCMIS_CSS_STYLE + "</head><body>");
        pw.print("<h1>HTTP Status " + statusCode + " - <!--exception-->" + exceptionName + "<!--/exception--></h1>");
        pw.print("<p><!--message-->");
        StringEscapeUtils.ESCAPE_HTML4.translate(message, pw);
        pw.print("<!--/message--></p>");

        String st = ExceptionHelper.getStacktraceAsString(t);
        if (st != null) {
            pw.print("<hr noshade='noshade'/><!--stacktrace--><pre>\n<!--key-->stacktrace<!--/key><!--value-->" + st
                    + "<!--/value-->\n</pre><!--/stacktrace--><hr noshade='noshade'/>");
        }

        if (t instanceof CmisBaseException) {
            Map<String, String> additionalData = ((CmisBaseException) t).getAdditionalData();
            if (additionalData != null && !additionalData.isEmpty()) {
                pw.print("<hr noshade='noshade'/>Additional data:<br><br>");
                for (Map.Entry<String, String> e : additionalData.entrySet()) {
                    pw.print("<!--key-->");
                    StringEscapeUtils.ESCAPE_HTML4.translate(e.getKey(), pw);
                    pw.print("<!--/key--> = <!--value-->");
                    StringEscapeUtils.ESCAPE_HTML4.translate(e.getValue(), pw);
                    pw.print("<!--/value--><br>");
                }
            }
        }

        pw.print("</body></html>");
    }
}

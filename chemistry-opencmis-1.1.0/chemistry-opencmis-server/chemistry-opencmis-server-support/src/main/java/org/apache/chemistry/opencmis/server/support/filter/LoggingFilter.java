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
package org.apache.chemistry.opencmis.server.support.filter;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.DateTimeHelper;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingFilter.class);
    private static int requestNo = 0;
    private String logDir;
    private boolean prettyPrint = true;
    private boolean logHeaders = true;
    private int indent = -1;

    @Override
    public void init(FilterConfig cfg) throws ServletException {

        String val;
        logDir = cfg.getInitParameter("LogDir");
        if (null == logDir || logDir.length() == 0) {
            logDir = System.getProperty("java.io.tmpdir");
        }
        if (null == logDir || logDir.length() == 0) {
            logDir = "." + File.separator;
        }

        if (!logDir.endsWith(File.separator)) {
            logDir += File.separator;
        }

        val = cfg.getInitParameter("Indent");
        if (null != val) {
            indent = Integer.parseInt(val);
        }
        if (indent < 0) {
            indent = 4;
        }

        val = cfg.getInitParameter("PrettyPrint");
        if (null != val) {
            prettyPrint = Boolean.parseBoolean(val);
        }

        val = cfg.getInitParameter("LogHeaders");
        if (null != val) {
            logHeaders = Boolean.parseBoolean(val);
        }
    }

    @Override
    public void destroy() {
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException,
            ServletException {
        LOG.debug("Logging filter doFilter");

        if (resp instanceof HttpServletResponse && req instanceof HttpServletRequest) {
            LoggingRequestWrapper logReq = new LoggingRequestWrapper((HttpServletRequest) req);
            LoggingResponseWrapper logResponse = new LoggingResponseWrapper((HttpServletResponse) resp);

            chain.doFilter(logReq, logResponse);

            int reqNo = getNextRequestNumber();
            String requestFileName = getRequestFileName(reqNo);
            String cType = logReq.getContentType();
            String xmlRequest = logReq.getPayload();
            StringBuilder sb = new StringBuilder(1024);

            if (logHeaders) {
                logHeaders(logReq, sb);
            }

            if (xmlRequest == null || xmlRequest.length() == 0) {
                xmlRequest = "";
            }

            if (prettyPrint && cType != null) {
                if (cType.startsWith("multipart")) {
                    xmlRequest = processMultipart(cType, xmlRequest);
                } else if (cType.contains("xml")) {
                    xmlRequest = prettyPrintXml(xmlRequest, indent);
                }
            }

            xmlRequest = sb.toString() + xmlRequest;
            if (LOG.isDebugEnabled()) {
                LOG.debug("Found request: {}: {}", requestFileName, xmlRequest);
            }
            writeTextToFile(requestFileName, xmlRequest);

            sb.setLength(0);
            cType = logResponse.getContentType();
            String xmlResponse = logResponse.getPayload();
            String responseFileName = getResponseFileName(reqNo);

            if (logHeaders) {
                logHeaders(logResponse, req.getProtocol(), sb);
            }

            if (xmlResponse == null || xmlResponse.length() == 0) {
                xmlResponse = "";
            }

            if (prettyPrint && cType != null) {
                if (cType.startsWith("multipart")) {
                    xmlResponse = processMultipart(cType, xmlResponse);
                } else if (cType.contains("xml")) {
                    xmlResponse = prettyPrintXml(xmlResponse, indent);
                } else if (cType.contains("json")) {
                    xmlResponse = prettyPrintJson(xmlResponse, indent);
                }
            }

            xmlResponse = sb.toString() + xmlResponse;

            if (LOG.isDebugEnabled()) {
                LOG.debug("Found response: {}: {}", responseFileName, xmlResponse);
            }

            writeTextToFile(responseFileName, xmlResponse);
        } else {
            chain.doFilter(req, resp);
        }
    }

    private void writeTextToFile(String filename, String content) {
        PrintWriter pw = null;
        OutputStreamWriter fw = null;
        try {
            fw = new OutputStreamWriter(new FileOutputStream(filename), IOUtils.UTF8);
            pw = new PrintWriter(fw);

            Scanner scanner = new Scanner(content);
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                pw.println(line);
            }
            scanner.close();

            pw.flush();
        } catch (IOException e) {
            LOG.error(e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(pw);
            IOUtils.closeQuietly(fw);
        }
    }

    private static String prettyPrintXml(String input, int indent) {
        try {
            Source xmlInput = new StreamSource(new StringReader(input));
            StringWriter stringWriter = new StringWriter();
            StreamResult xmlOutput = new StreamResult(stringWriter);
            Transformer transformer = XMLUtils.newTransformer(indent);
            transformer.transform(xmlInput, xmlOutput);
            return xmlOutput.getWriter().toString();
        } catch (Exception e) {
            throw new RuntimeException(e); // simple exception handling, please
                                           // review it
        }
    }

    private static String prettyPrintJson(String input, int indent) {
        JsonPrettyPrinter pp = new JsonPrettyPrinter(indent);
        return pp.prettyPrint(input);
    }

    private String processMultipart(String cType, String messageBody) throws IOException {
        int beginIndex = cType.indexOf("boundary=\"") + 10;
        int endIndex = cType.indexOf('\"', beginIndex);
        if (endIndex < 0) {
            endIndex = cType.length();
        }
        String boundary = "--" + cType.substring(beginIndex, endIndex);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Boundary = {}", boundary);
        }

        BufferedReader in = new BufferedReader(new StringReader(messageBody));
        StringBuffer out = new StringBuffer();
        String line;
        ByteArrayOutputStream xmlBodyBuffer = new ByteArrayOutputStream(32 * 1024);
        boolean boundaryFound;

        boolean inXmlOrJsonBody = false;
        boolean inXmlOrJsonPart = false;
        boolean isXml;
        while ((line = in.readLine()) != null) {
            if (inXmlOrJsonPart) {
                if (line.startsWith("<?xml") || line.startsWith("{")) {
                    inXmlOrJsonBody = true;
                    isXml = line.startsWith("<?xml");
                    byte[] lienBytes = IOUtils.toUTF8Bytes(line);
                    xmlBodyBuffer.write(lienBytes, 0, lienBytes.length);
                    while (inXmlOrJsonBody) {
                        line = in.readLine();
                        if (line == null) {
                            break;
                        }
                        boundaryFound = line.startsWith(boundary);
                        if (boundaryFound) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Leaving XML body: {}", line);
                            }
                            inXmlOrJsonBody = false;
                            inXmlOrJsonPart = false;
                            if (isXml) {
                                out.append(prettyPrintXml(xmlBodyBuffer.toString(IOUtils.UTF8), indent));
                            } else {
                                out.append(prettyPrintJson(xmlBodyBuffer.toString(IOUtils.UTF8), indent));
                            }
                            out.append(line).append('\n');
                        } else {
                            xmlBodyBuffer.write(lienBytes, 0, lienBytes.length);
                        }
                    }
                } else {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("in XML part is: {}", line);
                    }
                    out.append(line).append('\n');
                }

            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("not in XML part: {}", line);
                }
                out.append(line).append('\n');
                boundaryFound = line.startsWith(boundary);
                if (boundaryFound) {
                    LOG.debug("Boundardy found!");
                    inXmlOrJsonPart = true;
                }
            }
        }
        in.close();
        LOG.debug("End parsing multipart.");

        return out.toString();
    }

    @SuppressWarnings("rawtypes")
    private void logHeaders(LoggingRequestWrapper req, StringBuilder sb) {
        sb.append(req.getMethod());
        sb.append(' ');
        sb.append(req.getRequestURI());
        String queryString = req.getQueryString();
        if (null != queryString && queryString.length() > 0) {
            sb.append('?');
            sb.append(queryString);
        }
        sb.append(' ');
        sb.append(req.getProtocol());
        sb.append('\n');
        Enumeration headerNames = req.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement().toString();
            headerName = headerName.substring(0, 1).toUpperCase() + headerName.substring(1);
            sb.append(headerName);
            sb.append(": ");
            sb.append(req.getHeader(headerName));
            sb.append('\n');
        }
        sb.append('\n');
    }

    private void logHeaders(LoggingResponseWrapper resp, String protocol, StringBuilder sb) {
        sb.append(protocol);
        sb.append(' ');
        sb.append(String.valueOf(resp.getStatus()));
        sb.append('\n');
        Map<String, String> headers = resp.getHeaders();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            sb.append(header.getKey());
            sb.append(": ");
            sb.append(header.getValue());
            sb.append('\n');
        }
        sb.append('\n');
    }

    private String getRequestFileName(int no) {
        return logDir + String.format("%05d-request.log", no);
    }

    private String getResponseFileName(int no) {
        return logDir + String.format("%05d-response.log", no);
    }

    private static synchronized int getNextRequestNumber() {
        return requestNo++;
    }

    private static class LoggingRequestWrapper extends HttpServletRequestWrapper {

        private LoggingInputStream is;

        public LoggingRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            this.is = new LoggingInputStream(super.getInputStream());
            return is;
        }

        public String getPayload() {
            return null == is ? "" : is.getPayload();
        }
    }

    private static class LoggingInputStream extends ServletInputStream {

        private ByteArrayOutputStream baous = new ByteArrayOutputStream();
        private ServletInputStream is;

        public LoggingInputStream(ServletInputStream is) {
            super();
            this.is = is;
        }

        // Since we are not sure which method is used just overwrite all 4 of
        // them:
        @Override
        public int read() throws IOException {
            int ch = is.read();
            if (ch != -1) {
                baous.write(ch);
            }
            return ch;
        }

        @Override
        public int read(byte[] b) throws IOException {
            int ch = is.read(b);
            if (ch != -1) {
                baous.write(b, 0, ch);
            }
            return ch;
        }

        @Override
        public int read(byte[] b, int o, int l) throws IOException {
            int ch = is.read(b, o, l);
            if (ch != -1) {
                baous.write(b, o, ch);
            }
            return ch;
        }

        @Override
        public int readLine(byte[] b, int o, int l) throws IOException {
            int ch = is.readLine(b, o, l);
            if (ch != -1) {
                baous.write(b, o, ch);
            }
            return ch;
        }

        public String getPayload() {
            try {
                return baous.toString(IOUtils.UTF8);
            } catch (UnsupportedEncodingException e) {
                throw new CmisRuntimeException("Unsupported encoding 'UTF-8'!", e);
            }
        }

        @Override
        public boolean isFinished() {
            // Delegate to the wrapped input stream if it supports this method
            return is.isFinished();
        }

        @Override
        public boolean isReady() {
            // Delegate to the wrapped input stream if it supports this method
            return is.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // Delegate to the wrapped input stream
            is.setReadListener(readListener);
        }
    }

    private static class LoggingResponseWrapper extends HttpServletResponseWrapper {

        private LoggingOutputStream os;
        private PrintWriter writer;
        private int statusCode;
        private final Map<String, String> headers = new HashMap<String, String>();
        private String encoding;

        public LoggingResponseWrapper(HttpServletResponse response) throws IOException {
            super(response);
            this.os = new LoggingOutputStream(response.getOutputStream());
        }

        @Override
        public PrintWriter getWriter() {
            try {
                if (null == writer) {
                    writer = new PrintWriter(new OutputStreamWriter(this.getOutputStream(), IOUtils.UTF8));
                }
                return writer;
            } catch (IOException e) {
                LOG.error("Failed to get PrintWriter in LoggingFilter: {}", e.toString(), e);
                return null;
            }
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            return os;
        }

        public String getPayload() {
            return os.getPayload();
        }

        @Override
        public void addCookie(Cookie cookie) {
            super.addCookie(cookie);
            String value;
            if (headers.containsKey("Cookie")) {
                value = headers.get("Cookie") + "; " + cookie.toString();
            } else {
                value = cookie.toString();
            }
            headers.put("Cookie", value);
        }

        @Override
        public void setContentType(String type) {
            super.setContentType(type);
            if (headers.containsKey("Content-Type")) {
                String cType = headers.get("Content-Type");
                int pos = cType.indexOf(";charset=");
                if (pos < 0 && encoding != null) {
                    type = cType + ";charset=" + encoding;
                } else if (pos >= 0) {
                    encoding = null;
                }
            }
            headers.put("Content-Type", type);
        }

        @Override
        public void setCharacterEncoding(java.lang.String charset) {
            super.setCharacterEncoding(charset);
            encoding = charset;
            if (headers.containsKey("Content-Type")) {
                String cType = headers.get("Content-Type");
                int pos = cType.indexOf(";charset=");
                if (pos >= 0) {
                    cType = cType.substring(0, pos) + ";charset=" + encoding;
                } else {
                    cType = cType + ";charset=" + encoding;
                }
                headers.put("Content-Type", cType);
            }
        }

        @Override
        public void setContentLength(int len) {
            super.setContentLength(len);
            headers.put("Content-Length", String.valueOf(len));
        }

        private String getDateString(long date) {
            return DateTimeHelper.formatXmlDateTime(date);
        }

        @Override
        public void setDateHeader(String name, long date) {
            super.setDateHeader(name, date);
            headers.put(name, getDateString(date));
        }

        @Override
        public void addDateHeader(String name, long date) {
            super.addDateHeader(name, date);
            if (headers.containsKey(name)) {
                headers.put(name, headers.get(name) + "; " + getDateString(date));
            } else {
                headers.put(name, getDateString(date));
            }
        }

        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, value);
            headers.put(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            super.addHeader(name, value);
            if (headers.containsKey(name)) {
                headers.put(name, headers.get(name) + "; " + value);
            } else {
                headers.put(name, value);
            }
        }

        @Override
        public void setIntHeader(String name, int value) {
            super.setIntHeader(name, value);
            headers.put(name, String.valueOf(value));
        }

        @Override
        public void addIntHeader(String name, int value) {
            super.addIntHeader(name, value);
            if (headers.containsKey(name)) {
                headers.put(name, headers.get(name) + "; " + value);
            } else {
                headers.put(name, String.valueOf(value));
            }
        }

        @Override
        public void sendError(int sc) throws IOException {
            statusCode = sc;
            super.sendError(sc);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            statusCode = sc;
            super.sendError(sc, msg);
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            statusCode = 302;
            super.sendRedirect(location);
        }

        @Override
        public void setStatus(int sc) {
            statusCode = sc;
            super.setStatus(sc);
        }

        public int getStatus() {
            return statusCode;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }
    }

    private static class LoggingOutputStream extends ServletOutputStream {
        private ByteArrayOutputStream baous = new ByteArrayOutputStream();
        private ServletOutputStream os;

        public LoggingOutputStream(ServletOutputStream os) {
            super();
            this.os = os;
        }

        public String getPayload() {
            return IOUtils.toUTF8String(baous.toByteArray());
        }

        @Override
        public void write(byte[] b, int off, int len) {
            try {
                baous.write(b, off, len);
                os.write(b, off, len);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void write(byte[] b) {
            try {
                baous.write(b);
                os.write(b);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void write(int ch) throws IOException {
            baous.write(ch);
            os.write(ch);
        }

        @Override
        public boolean isReady() {
            // Delegate to the wrapped output stream if it supports this method
            return os.isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            // Delegate to the wrapped output stream
            os.setWriteListener(writeListener);
        }
    }
}

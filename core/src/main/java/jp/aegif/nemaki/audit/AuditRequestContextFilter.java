/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.audit;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * Lightweight filter that sets the audit request context (client IP, trace ID, etc.)
 * for CMIS binding paths (/browser/*, /atom/*) where the AuthenticationFilter is not applied.
 *
 * This filter performs no authentication — it only captures HTTP request metadata
 * into AuditLogger's ThreadLocal so that audit log entries include client IP and
 * other request details.
 */
public class AuditRequestContextFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // no-op
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest) {
            AuditLogger.setRequestContext((HttpServletRequest) request);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            AuditLogger.clearRequestContext();
        }
    }

    @Override
    public void destroy() {
        // no-op
    }
}

package jp.aegif.nemaki.rest;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SAML Assertion Consumer Service (ACS) servlet.
 *
 * Receives the SAML POST binding response from the Identity Provider,
 * stores SAMLResponse and RelayState in sessionStorage via an
 * intermediate HTML page, then redirects to the SPA callback page
 * where the React application processes the SAML response.
 *
 * This bridge is necessary because SAML POST binding delivers the
 * SAMLResponse as HTTP POST form data, which is inaccessible to
 * JavaScript in the target page (browser security limitation).
 */
public class SamlAcsServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(SamlAcsServlet.class);

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        String samlResponse = request.getParameter("SAMLResponse");
        String relayState = request.getParameter("RelayState");

        if (samlResponse == null || samlResponse.isEmpty()) {
            logger.warn("SAML ACS received POST without SAMLResponse parameter");
            response.sendRedirect(request.getContextPath() + "/ui/saml-callback.html");
            return;
        }

        logger.info("SAML ACS received POST binding response (SAMLResponse length={})",
                samlResponse.length());

        // Render a minimal HTML page that stores the SAML data in
        // sessionStorage and redirects to the SPA callback page.
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");

        PrintWriter out = response.getWriter();
        out.println("<!DOCTYPE html>");
        out.println("<html><head><meta charset=\"UTF-8\">");
        out.println("<title>SAML Processing</title></head><body>");
        out.println("<script>");
        out.print("sessionStorage.setItem('nemakiware_saml_response','");
        out.print(escapeForJavaScript(samlResponse));
        out.println("');");
        if (relayState != null && !relayState.isEmpty()) {
            out.print("sessionStorage.setItem('nemakiware_saml_relay_state','");
            out.print(escapeForJavaScript(relayState));
            out.println("');");
        }
        out.print("window.location.replace('");
        out.print(escapeForJavaScript(request.getContextPath() + "/ui/saml-callback.html"));
        out.println("');");
        out.println("</script>");
        out.println("<noscript><p>JavaScript is required for SAML authentication.</p></noscript>");
        out.println("</body></html>");
    }

    /**
     * Escape a string for safe embedding in a JavaScript single-quoted string literal.
     * SAMLResponse is Base64 (alphanumeric + /+=), so this is mostly defensive.
     */
    private static String escapeForJavaScript(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '<':  sb.append("\\x3C"); break;
                case '>':  sb.append("\\x3E"); break;
                default:   sb.append(c); break;
            }
        }
        return sb.toString();
    }
}

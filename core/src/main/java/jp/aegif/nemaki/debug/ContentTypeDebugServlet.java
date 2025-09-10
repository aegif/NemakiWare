package jp.aegif.nemaki.debug;

import java.io.IOException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/debug/content-type")
@MultipartConfig
public class ContentTypeDebugServlet extends HttpServlet {
    
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        
        // Log Content-Type information
        String contentType = request.getContentType();
        
        StringBuilder result = new StringBuilder();
        result.append("=== CONTENT-TYPE DEBUG ===\n");
        result.append("Content-Type: ").append(contentType).append("\n");
        result.append("Content-Type null: ").append(contentType == null).append("\n");
        result.append("Content-Type empty: ").append(contentType != null && contentType.isEmpty()).append("\n");
        
        if (contentType != null) {
            result.append("Content-Type length: ").append(contentType.length()).append("\n");
            result.append("Contains multipart: ").append(contentType.contains("multipart")).append("\n");
            result.append("Contains boundary: ").append(contentType.contains("boundary")).append("\n");
        }
        
        // Test MimeHelper parsing
        try {
            Class<?> mimeHelperClass = Class.forName("org.apache.chemistry.opencmis.commons.impl.MimeHelper");
            java.lang.reflect.Method getBoundaryMethod = mimeHelperClass.getMethod("getBoundaryFromMultiPart", String.class);
            byte[] boundary = (byte[]) getBoundaryMethod.invoke(null, contentType);
            
            result.append("MimeHelper result: ").append(boundary != null ? "SUCCESS" : "NULL").append("\n");
            if (boundary != null) {
                result.append("Boundary length: ").append(boundary.length).append("\n");
                result.append("Boundary string: ").append(new String(boundary, "ISO-8859-1")).append("\n");
            }
        } catch (Exception e) {
            result.append("MimeHelper error: ").append(e.getMessage()).append("\n");
        }
        
        response.getWriter().write(result.toString());
    }
}
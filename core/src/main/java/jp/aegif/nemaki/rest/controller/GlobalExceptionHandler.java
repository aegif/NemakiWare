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
 *
 * Contributors:
 *     Claude Code - Global exception handler for Spring REST API
 ******************************************************************************/
package jp.aegif.nemaki.rest.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.WebRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Global exception handler for Spring REST API controllers
 * Provides consistent error responses across all API endpoints
 */
@ControllerAdvice(basePackages = "jp.aegif.nemaki.rest.controller")
public class GlobalExceptionHandler {

    private static final Log log = LogFactory.getLog(GlobalExceptionHandler.class);

    /**
     * Handle general exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(
            Exception ex, WebRequest request) {
        
        log.error("Unhandled exception in REST API: " + ex.getMessage(), ex);
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", "error");
        response.put("message", "Internal server error");
        response.put("error", ex.getMessage());
        response.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Handle illegal argument exceptions
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest request) {
        
        log.warn("Invalid request parameter: " + ex.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", "error");
        response.put("message", "Invalid request parameter");
        response.put("error", ex.getMessage());
        response.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handle null pointer exceptions
     */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNullPointerException(
            NullPointerException ex, WebRequest request) {
        
        log.error("Null pointer exception in REST API: " + ex.getMessage(), ex);
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", "error");
        response.put("message", "Resource not found or invalid request");
        response.put("error", "Required resource or parameter is missing");
        response.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handle runtime exceptions
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(
            RuntimeException ex, WebRequest request) {
        
        log.error("Runtime exception in REST API: " + ex.getMessage(), ex);
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", "error");
        response.put("message", "Processing error");
        response.put("error", ex.getMessage());
        response.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
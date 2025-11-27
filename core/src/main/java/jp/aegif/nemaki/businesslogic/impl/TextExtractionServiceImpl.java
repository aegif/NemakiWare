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
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic.impl;

import java.io.InputStream;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;

import jp.aegif.nemaki.businesslogic.TextExtractionService;

/**
 * Apache Tika-based implementation of TextExtractionService.
 * Extracts text content from various document formats for full-text search indexing.
 *
 * Supported formats include:
 * - Plain text (txt, csv, xml, html, etc.)
 * - PDF documents
 * - Microsoft Office (doc, docx, xls, xlsx, ppt, pptx)
 * - OpenDocument formats (odt, ods, odp)
 * - Rich Text Format (rtf)
 * - And many more via Apache Tika
 *
 * @author NemakiWare Team
 * @since 3.0.0
 */
public class TextExtractionServiceImpl implements TextExtractionService {

    private static final Log log = LogFactory.getLog(TextExtractionServiceImpl.class);

    /** Default maximum length for extracted text (100KB) */
    private static final int DEFAULT_MAX_LENGTH = 100 * 1024;

    /** Tika instance for simple text extraction */
    private final Tika tika;

    /** Parser for advanced extraction with metadata */
    private final Parser parser;

    /** Set of supported MIME types */
    private final Set<String> supportedMimeTypes;

    /**
     * Constructor initializes Tika components.
     */
    public TextExtractionServiceImpl() {
        this.tika = new Tika();
        this.parser = new AutoDetectParser();
        this.supportedMimeTypes = initSupportedMimeTypes();
        log.info("TextExtractionService initialized with Apache Tika");
    }

    /**
     * Initialize the set of explicitly supported MIME types.
     * Tika supports many more, but these are the primary ones for NemakiWare.
     */
    private Set<String> initSupportedMimeTypes() {
        Set<String> types = new HashSet<>();

        // Plain text formats
        types.add("text/plain");
        types.add("text/csv");
        types.add("text/html");
        types.add("text/xml");
        types.add("application/xml");
        types.add("application/json");

        // PDF
        types.add("application/pdf");

        // Microsoft Office - Legacy formats
        types.add("application/msword");                                                    // .doc
        types.add("application/vnd.ms-excel");                                              // .xls
        types.add("application/vnd.ms-powerpoint");                                         // .ppt

        // Microsoft Office - OpenXML formats
        types.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document");      // .docx
        types.add("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");            // .xlsx
        types.add("application/vnd.openxmlformats-officedocument.presentationml.presentation");    // .pptx

        // OpenDocument formats
        types.add("application/vnd.oasis.opendocument.text");                               // .odt
        types.add("application/vnd.oasis.opendocument.spreadsheet");                        // .ods
        types.add("application/vnd.oasis.opendocument.presentation");                       // .odp

        // Rich Text Format
        types.add("application/rtf");
        types.add("text/rtf");

        // Email formats
        types.add("message/rfc822");                                                        // .eml
        types.add("application/vnd.ms-outlook");                                            // .msg

        // Archive formats (extract content from contained files)
        types.add("application/zip");
        types.add("application/x-tar");
        types.add("application/gzip");

        return types;
    }

    @Override
    public String extractText(InputStream inputStream, String mimeType, String fileName) {
        return extractText(inputStream, mimeType, fileName, DEFAULT_MAX_LENGTH);
    }

    @Override
    public String extractText(InputStream inputStream, String mimeType, String fileName, int maxLength) {
        log.info("[TIKA DEBUG] extractText ENTRY - mimeType: " + mimeType + ", fileName: " + fileName + ", maxLength: " + maxLength);

        if (inputStream == null) {
            log.warn("Cannot extract text: input stream is null");
            return null;
        }

        log.info("[TIKA DEBUG] InputStream class: " + inputStream.getClass().getName() + ", available: " + tryGetAvailable(inputStream));

        try {
            // Set up metadata for better parsing
            log.info("[TIKA DEBUG] Setting up metadata...");
            Metadata metadata = new Metadata();
            if (mimeType != null && !mimeType.isEmpty()) {
                metadata.set(Metadata.CONTENT_TYPE, mimeType);
            }
            if (fileName != null && !fileName.isEmpty()) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            }

            // Use BodyContentHandler with character limit to prevent memory issues
            log.info("[TIKA DEBUG] Creating BodyContentHandler...");
            BodyContentHandler handler = new BodyContentHandler(maxLength);
            ParseContext context = new ParseContext();

            // Parse the document
            log.info("[TIKA DEBUG] Calling parser.parse()...");
            parser.parse(inputStream, handler, metadata, context);
            log.info("[TIKA DEBUG] parser.parse() completed");

            String extractedText = handler.toString();
            log.info("[TIKA DEBUG] Handler returned text length: " + (extractedText != null ? extractedText.length() : 0));

            // Clean up the extracted text
            if (extractedText != null) {
                extractedText = extractedText.trim();
                // Remove excessive whitespace
                extractedText = extractedText.replaceAll("\\s+", " ");
            }

            if (extractedText != null && !extractedText.isEmpty()) {
                log.debug("Successfully extracted " + extractedText.length() + " characters from " +
                        (fileName != null ? fileName : "document") + " (" + mimeType + ")");
                return extractedText;
            } else {
                log.debug("No text content extracted from " +
                        (fileName != null ? fileName : "document") + " (" + mimeType + ")");
                return null;
            }

        } catch (Exception e) {
            log.error("[TIKA DEBUG] EXCEPTION in extractText: " + e.getClass().getName() + " - " + e.getMessage());
            log.error("[TIKA DEBUG] Exception stack trace:", e);
            log.warn("Failed to extract text from " +
                    (fileName != null ? fileName : "document") + " (" + mimeType + "): " + e.getMessage());
            return null;
        } catch (Throwable t) {
            log.error("[TIKA DEBUG] THROWABLE in extractText: " + t.getClass().getName() + " - " + t.getMessage());
            log.error("[TIKA DEBUG] Throwable stack trace:", t);
            return null;
        }
    }

    private int tryGetAvailable(InputStream inputStream) {
        try {
            return inputStream.available();
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public boolean isSupported(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) {
            return false;
        }

        // Check explicit support list
        if (supportedMimeTypes.contains(mimeType.toLowerCase())) {
            return true;
        }

        // Also support any text/* MIME type
        if (mimeType.toLowerCase().startsWith("text/")) {
            return true;
        }

        // Tika may support additional formats not in our explicit list
        // For now, we'll be conservative and only claim support for known types
        return false;
    }

    @Override
    public String getStrategyName() {
        return "tika";
    }

    @Override
    public Map<String, String> extractMetadata(InputStream inputStream, String mimeType, String fileName) {
        Map<String, String> result = new HashMap<>();

        if (inputStream == null) {
            log.warn("Cannot extract metadata: input stream is null");
            return result;
        }

        try {
            Metadata metadata = new Metadata();
            if (mimeType != null && !mimeType.isEmpty()) {
                metadata.set(Metadata.CONTENT_TYPE, mimeType);
            }
            if (fileName != null && !fileName.isEmpty()) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            }

            // Use a handler that discards content (we only want metadata)
            BodyContentHandler handler = new BodyContentHandler(-1);
            ParseContext context = new ParseContext();

            parser.parse(inputStream, handler, metadata, context);

            // Convert Tika metadata to simple map
            for (String name : metadata.names()) {
                String value = metadata.get(name);
                if (value != null && !value.isEmpty()) {
                    result.put(name, value);
                }
            }

            log.debug("Extracted " + result.size() + " metadata properties from " +
                    (fileName != null ? fileName : "document"));

        } catch (Exception e) {
            log.warn("Failed to extract metadata from " +
                    (fileName != null ? fileName : "document") + ": " + e.getMessage());
        }

        return result;
    }

    /**
     * Get the list of supported MIME types.
     * Useful for configuration and documentation.
     *
     * @return Set of supported MIME type strings
     */
    public Set<String> getSupportedMimeTypes() {
        return new HashSet<>(supportedMimeTypes);
    }
}

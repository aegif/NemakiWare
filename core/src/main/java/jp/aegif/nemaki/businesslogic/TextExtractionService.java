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
package jp.aegif.nemaki.businesslogic;

import java.io.InputStream;

/**
 * Service interface for extracting text content from various document formats.
 * This service enables full-text search functionality in NemakiWare 3.0.
 *
 * Supported formats:
 * - Plain text (txt, csv, etc.)
 * - PDF (pdf)
 * - Microsoft Office (doc, docx, xls, xlsx, ppt, pptx)
 * - OpenDocument (odt, ods, odp)
 * - HTML/XML
 * - Rich Text Format (rtf)
 * - And more via Apache Tika
 *
 * @author NemakiWare Team
 * @since 3.0.0
 */
public interface TextExtractionService {

    /**
     * Extract text content from an input stream.
     *
     * @param inputStream The input stream containing the document content
     * @param mimeType The MIME type of the document (e.g., "application/pdf")
     * @param fileName The original file name (used for format detection fallback)
     * @return The extracted text content, or null if extraction fails
     */
    String extractText(InputStream inputStream, String mimeType, String fileName);

    /**
     * Extract text content from an input stream with a maximum length limit.
     *
     * @param inputStream The input stream containing the document content
     * @param mimeType The MIME type of the document
     * @param fileName The original file name
     * @param maxLength Maximum length of extracted text (characters)
     * @return The extracted text content (truncated if necessary), or null if extraction fails
     */
    String extractText(InputStream inputStream, String mimeType, String fileName, int maxLength);

    /**
     * Check if a MIME type is supported for text extraction.
     *
     * @param mimeType The MIME type to check
     * @return true if the MIME type can be processed for text extraction
     */
    boolean isSupported(String mimeType);

    /**
     * Get the extraction strategy name (e.g., "tika", "external", "hybrid").
     *
     * @return The name of the current extraction strategy
     */
    String getStrategyName();

    /**
     * Extract metadata from a document.
     *
     * @param inputStream The input stream containing the document content
     * @param mimeType The MIME type of the document
     * @param fileName The original file name
     * @return A map of metadata key-value pairs, or empty map if extraction fails
     */
    java.util.Map<String, String> extractMetadata(InputStream inputStream, String mimeType, String fileName);
}

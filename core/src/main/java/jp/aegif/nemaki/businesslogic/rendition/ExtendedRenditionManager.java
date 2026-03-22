package jp.aegif.nemaki.businesslogic.rendition;

import org.apache.chemistry.opencmis.commons.data.ContentStream;

/**
 * Extended RenditionManager that supports SVG rendition generation
 * in addition to the standard PDF conversion.
 */
public interface ExtendedRenditionManager extends RenditionManager {

	/**
	 * Check if the given MIME type (and optionally file name) can be converted to SVG.
	 *
	 * @param mimeType the MIME type of the source content
	 * @param fileName optional file name for extension-based fallback
	 * @return true if SVG conversion is supported
	 */
	boolean checkSvgConvertible(String mimeType, String fileName);

	/**
	 * Convert content to SVG format.
	 *
	 * @param contentStream the source content stream
	 * @param documentName  the document name (used for extension detection)
	 * @return ContentStream containing the SVG output, or null on failure
	 */
	ContentStream convertToSvg(ContentStream contentStream, String documentName);

	/**
	 * Extract text content from the given content stream for full-text indexing.
	 *
	 * @param contentStream the source content stream
	 * @param documentName  the document name
	 * @return extracted text, or null if extraction is not supported
	 */
	String extractText(ContentStream contentStream, String documentName);
}

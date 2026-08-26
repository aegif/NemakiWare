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
	 * As {@link #convertToSvg} but naming the delegate that produced the bytes.
	 *
	 * <p>Same reason as {@link RenditionManager#convertToPdfAttributed}: a composite tries the
	 * delegates that CLAIM the type, and one can claim it and return nothing. The name that
	 * goes into the evidence chain has to be the one that did the work, because the digest
	 * recorded beside it commits to that attribution.
	 *
	 * <p>The default is right for a single converter, where claiming and converting are the
	 * same delegate. A composite overrides it.
	 */
	default Converted convertToSvgAttributed(ContentStream contentStream, String documentName) {
		ContentStream result = convertToSvg(contentStream, documentName);
		return result == null ? null : new Converted(result, converterId());
	}

	/**
	 * Extract text content from the given content stream for full-text indexing.
	 *
	 * @param contentStream the source content stream
	 * @param documentName  the document name
	 * @return extracted text, or null if extraction is not supported
	 */
	String extractText(ContentStream contentStream, String documentName);
}

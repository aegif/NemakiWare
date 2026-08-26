package jp.aegif.nemaki.businesslogic.rendition;

import org.apache.chemistry.opencmis.commons.data.ContentStream;

import java.util.List;

public interface RenditionManager {
	public ContentStream convertToPdf(ContentStream contentStream, String documentName);

	public boolean checkConvertible(String mediatype);

	/**
	 * A stable identifier for this converter, matching
	 * {@code FormatDuplicationRecorder.Converter#id()}.
	 *
	 * <p>Exists because the duplication record has to say WHICH tool produced the copy, and the
	 * losses it discloses are that tool's. Hard-coding one at the call site attributed every
	 * rendition to LibreOffice — including the CAD ones, whose real losses (layers, geometry)
	 * were then never disclosed by anything. The digest commits to this value, so a wrong one
	 * is a false attribution recorded in the chain.
	 */
	default String converterId() {
		return "nemaki/unknown";
	}

	/**
	 * The PDF conversion, together with WHO did it.
	 *
	 * <p>One walk, one answer. The first version asked separately — {@code converterIdFor} for
	 * the name and {@code convertToPdf} for the bytes — and the two disagree: the id side takes
	 * the first delegate that says it CAN convert, and the bytes side takes the first that
	 * actually DOES. A delegate that claims the type and returns null (Diagram never converts
	 * to PDF; CAD returns null when it cannot read the extension) makes the composite fall
	 * through to the next one, and the recorded converter is then the one that failed.
	 *
	 * <p>Attribution is written into the evidence chain and the digest commits to it, so a
	 * mismatch here is a false record. Two questions that must agree are better asked once.
	 */
	default Converted convertToPdfAttributed(ContentStream contentStream, String documentName) {
		ContentStream result = convertToPdf(contentStream, documentName);
		return result == null ? null : new Converted(result, converterId());
	}

	/** What came out, and which converter produced it. */
	public record Converted(ContentStream stream, String converterId) {}
	
	public List<String> getSupportedMimeTypes();
}

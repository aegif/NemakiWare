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
	 * Who would convert {@code mediatype}, or null when nothing here would.
	 *
	 * <p>Mirrors {@link #checkConvertible} rather than reporting what the last call happened to
	 * do: a field remembering the last delegate would be wrong under any concurrency, and this
	 * dispatch is deterministic.
	 */
	default String converterIdFor(String mediatype) {
		return checkConvertible(mediatype) ? converterId() : null;
	}
	
	public List<String> getSupportedMimeTypes();
}

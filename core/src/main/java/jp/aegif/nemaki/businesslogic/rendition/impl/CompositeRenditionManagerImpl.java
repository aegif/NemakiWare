package jp.aegif.nemaki.businesslogic.rendition.impl;

import jp.aegif.nemaki.businesslogic.rendition.ExtendedRenditionManager;
import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Composite RenditionManager that chains multiple delegates.
 * Order: CadRM → DiagramRM → JodRM (LibreOffice).
 * First delegate that claims convertibility wins.
 */
public class CompositeRenditionManagerImpl implements ExtendedRenditionManager {

	private static final Log log = LogFactory.getLog(CompositeRenditionManagerImpl.class);

	private List<RenditionManager> delegates;

	public void setDelegates(List<RenditionManager> delegates) {
		this.delegates = delegates;
	}

	@Override
	public boolean checkConvertible(String mediatype) {
		if (delegates == null) return false;
		for (RenditionManager delegate : delegates) {
			if (delegate.checkConvertible(mediatype)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean checkSvgConvertible(String mimeType, String fileName) {
		if (delegates == null) return false;
		for (RenditionManager delegate : delegates) {
			if (delegate instanceof ExtendedRenditionManager) {
				if (((ExtendedRenditionManager) delegate).checkSvgConvertible(mimeType, fileName)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The delegate that actually SUCCEEDED, not the first that claimed the type.
	 *
	 * <p>The distinction is the whole point. A delegate can claim a mime type and still return
	 * null — Diagram never converts to PDF, and CAD gives up when it cannot read the extension
	 * — and this loop then falls through to the next one. Reporting the claimant would name the
	 * delegate that failed, and that name goes into the evidence chain with the digest
	 * committing to it.
	 */
	@Override
	public Converted convertToPdfAttributed(ContentStream contentStream, String documentName) {
		if (delegates == null) return null;
		String mimeType = contentStream.getMimeType();
		for (RenditionManager delegate : delegates) {
			if (delegate.checkConvertible(mimeType)) {
				log.info("[CompositeRendition] Delegating PDF conversion to: " + delegate.getClass().getSimpleName());
				ContentStream result = delegate.convertToPdf(contentStream, documentName);
				if (result != null) return new Converted(result, delegate.converterId());
			}
		}
		log.warn("[CompositeRendition] No delegate could convert to PDF: " + documentName);
		return null;
	}

	@Override
	public ContentStream convertToPdf(ContentStream contentStream, String documentName) {
		Converted converted = convertToPdfAttributed(contentStream, documentName);
		return converted == null ? null : converted.stream();
	}

	/** The delegate that actually SUCCEEDED — see {@link #convertToPdfAttributed}. */
	@Override
	public Converted convertToSvgAttributed(ContentStream contentStream, String documentName) {
		if (delegates == null) return null;
		String mimeType = contentStream.getMimeType();
		for (RenditionManager delegate : delegates) {
			if (delegate instanceof ExtendedRenditionManager) {
				ExtendedRenditionManager ext = (ExtendedRenditionManager) delegate;
				if (ext.checkSvgConvertible(mimeType, documentName)) {
					log.info("[CompositeRendition] Delegating SVG conversion to: " + delegate.getClass().getSimpleName());
					ContentStream result = ext.convertToSvg(contentStream, documentName);
					if (result != null) return new Converted(result, delegate.converterId());
				}
			}
		}
		log.warn("[CompositeRendition] No delegate could convert to SVG: " + documentName);
		return null;
	}

	@Override
	public ContentStream convertToSvg(ContentStream contentStream, String documentName) {
		Converted converted = convertToSvgAttributed(contentStream, documentName);
		return converted == null ? null : converted.stream();
	}

	@Override
	public String extractText(ContentStream contentStream, String documentName) {
		if (delegates == null) return null;
		String mimeType = contentStream.getMimeType();
		for (RenditionManager delegate : delegates) {
			if (delegate instanceof ExtendedRenditionManager) {
				ExtendedRenditionManager ext = (ExtendedRenditionManager) delegate;
				if (ext.checkSvgConvertible(mimeType, documentName)) {
					log.info("[CompositeRendition] Delegating text extraction to: " + delegate.getClass().getSimpleName());
					return ext.extractText(contentStream, documentName);
				}
			}
		}
		return null;
	}

	@Override
	public List<String> getSupportedMimeTypes() {
		Set<String> all = new LinkedHashSet<>();
		if (delegates != null) {
			for (RenditionManager delegate : delegates) {
				List<String> mimes = delegate.getSupportedMimeTypes();
				if (mimes != null) {
					all.addAll(mimes);
				}
			}
		}
		return new ArrayList<>(all);
	}
}

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
	 * The delegate that would convert this type — the same first-match walk {@code convertToPdf}
	 * performs, so the answer is what will actually happen rather than what usually happens.
	 */
	@Override
	public String converterIdFor(String mediatype) {
		if (delegates == null) {
			return null;
		}
		for (RenditionManager delegate : delegates) {
			if (delegate.checkConvertible(mediatype)) {
				return delegate.converterId();
			}
		}
		return null;
	}

	@Override
	public ContentStream convertToPdf(ContentStream contentStream, String documentName) {
		if (delegates == null) return null;
		String mimeType = contentStream.getMimeType();
		for (RenditionManager delegate : delegates) {
			if (delegate.checkConvertible(mimeType)) {
				log.info("[CompositeRendition] Delegating PDF conversion to: " + delegate.getClass().getSimpleName());
				ContentStream result = delegate.convertToPdf(contentStream, documentName);
				if (result != null) return result;
			}
		}
		log.warn("[CompositeRendition] No delegate could convert to PDF: " + documentName);
		return null;
	}

	@Override
	public ContentStream convertToSvg(ContentStream contentStream, String documentName) {
		if (delegates == null) return null;
		String mimeType = contentStream.getMimeType();
		for (RenditionManager delegate : delegates) {
			if (delegate instanceof ExtendedRenditionManager) {
				ExtendedRenditionManager ext = (ExtendedRenditionManager) delegate;
				if (ext.checkSvgConvertible(mimeType, documentName)) {
					log.info("[CompositeRendition] Delegating SVG conversion to: " + delegate.getClass().getSimpleName());
					ContentStream result = ext.convertToSvg(contentStream, documentName);
					if (result != null) return result;
				}
			}
		}
		log.warn("[CompositeRendition] No delegate could convert to SVG: " + documentName);
		return null;
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

package jp.aegif.nemaki.businesslogic.rendition;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import java.util.List;

public interface RenditionManager {
	// Existing methods
	public ContentStream convertToPdf(ContentStream contentStream, String documentName);
	
	public boolean checkConvertible(String mediatype);
	
	// NEW: Get target mimetype for a source mimetype based on mapping
	public String getTargetMimeType(String sourceMimeType);
	
	// NEW: Get rendition kind for a source mimetype based on mapping
	public String getRenditionKind(String sourceMimeType);
	
	// NEW: Check if rendition generation is enabled
	public boolean isRenditionEnabled();
	
	// NEW: Get list of supported source mimetypes
	public List<String> getSupportedSourceMimeTypes();
}

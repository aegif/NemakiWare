package jp.aegif.nemaki.businesslogic.rendition.impl;

import jp.aegif.nemaki.businesslogic.rendition.ExtendedRenditionManager;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.*;
import java.math.BigInteger;
import java.util.*;

/**
 * RenditionManager for CAD formats (DXF, DWG, JWW, SFC, P21).
 * Uses mathacad library for conversion to SVG.
 */
public class CadRenditionManagerImpl implements ExtendedRenditionManager {

	private static final Log log = LogFactory.getLog(CadRenditionManagerImpl.class);

	// Cap the in-memory buffering of CAD source content so a very large stored
	// document cannot exhaust the heap during rendition (see BoundedIO).
	private static final int MAX_CAD_SOURCE_BYTES = 50 * 1024 * 1024; // 50 MB

	/** Map of extension → MIME type for CAD formats */
	private static final Map<String, String> EXTENSION_TO_MIME = new LinkedHashMap<>();
	static {
		EXTENSION_TO_MIME.put("dxf", "image/vnd.dxf");
		EXTENSION_TO_MIME.put("dwg", "application/x-dwg");
		EXTENSION_TO_MIME.put("jww", "application/x-jww");
		EXTENSION_TO_MIME.put("sfc", "application/x-sfc");
		EXTENSION_TO_MIME.put("p21", "application/x-p21");
	}

	/** All CAD MIME types we support */
	private static final Set<String> SUPPORTED_MIME_TYPES = new LinkedHashSet<>(EXTENSION_TO_MIME.values());

	/** Cached reflection: mathacad class (null if library not available) */
	private static volatile Class<?> mathacadClass;
	private static volatile boolean mathacadResolved;

	private static Class<?> getMathacadClass() {
		if (!mathacadResolved) {
			synchronized (CadRenditionManagerImpl.class) {
				if (!mathacadResolved) {
					try {
						mathacadClass = Class.forName("io.github.yumioka.mathacad.Mathacad");
					} catch (ClassNotFoundException e) {
						log.error("[CadRendition] mathacad library not found. Place mathacad-core-0.1.0.jar in lib/built-jars/", e);
						mathacadClass = null;
					}
					mathacadResolved = true;
				}
			}
		}
		return mathacadClass;
	}

	@Override
	public boolean checkSvgConvertible(String mimeType, String fileName) {
		if (mimeType != null && SUPPORTED_MIME_TYPES.contains(mimeType.toLowerCase())) {
			return true;
		}
		// Fallback: check file extension when MIME is octet-stream
		if ("application/octet-stream".equals(mimeType) && fileName != null) {
			String ext = FilenameUtils.getExtension(fileName).toLowerCase();
			return !ext.isEmpty() && EXTENSION_TO_MIME.containsKey(ext);
		}
		return false;
	}

	@Override
	public boolean checkConvertible(String mediatype) {
		// CAD files are converted to SVG, not PDF, so we delegate to checkSvgConvertible
		return checkSvgConvertible(mediatype, null);
	}

	@Override
	public ContentStream convertToSvg(ContentStream contentStream, String documentName) {
		log.info("[CadRendition] Starting SVG conversion for: " + documentName);
		try {
			String ext = FilenameUtils.getExtension(documentName).toLowerCase();
			if (ext.isEmpty()) {
				log.warn("[CadRendition] Cannot determine extension for: " + documentName);
				return null;
			}

			byte[] inputBytes;
			try (InputStream is = contentStream.getStream()) {
				inputBytes = jp.aegif.nemaki.util.io.BoundedIO.readBounded(is, MAX_CAD_SOURCE_BYTES, "CAD source");
			}

			// Use mathacad to convert
			Object cadDoc = readCadDocument(ext, inputBytes);
			if (cadDoc == null) return null;

			String svgString = invokeSvgConversion(cadDoc);
			if (svgString == null) {
				log.error("[CadRendition] mathacad conversion returned null for: " + documentName);
				return null;
			}

			byte[] svgBytes = svgString.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			ContentStreamImpl result = new ContentStreamImpl();
			result.setStream(new ByteArrayInputStream(svgBytes));
			result.setFileName(documentName + ".svg");
			result.setMimeType("image/svg+xml");
			result.setLength(BigInteger.valueOf(svgBytes.length));

			log.info("[CadRendition] SVG conversion successful for: " + documentName + " (" + svgBytes.length + " bytes)");
			return result;
		} catch (Exception e) {
			log.error("[CadRendition] SVG conversion failed for: " + documentName, e);
			return null;
		}
	}

	@Override
	public String converterId() {
		return "nemaki/cad";
	}

	@Override
	public ContentStream convertToPdf(ContentStream contentStream, String documentName) {
		log.info("[CadRendition] PDF conversion for CAD files via mathacad for: " + documentName);
		try {
			String ext = FilenameUtils.getExtension(documentName).toLowerCase();
			if (ext.isEmpty()) {
				return null;
			}

			byte[] inputBytes;
			try (InputStream is = contentStream.getStream()) {
				inputBytes = jp.aegif.nemaki.util.io.BoundedIO.readBounded(is, MAX_CAD_SOURCE_BYTES, "CAD source");
			}

			Object cadDoc = readCadDocument(ext, inputBytes);
			if (cadDoc == null) return null;

			byte[] pdfBytes = convertCadToPdf(cadDoc);
			if (pdfBytes == null) {
				return null;
			}

			ContentStreamImpl result = new ContentStreamImpl();
			result.setStream(new ByteArrayInputStream(pdfBytes));
			result.setFileName(documentName + ".pdf");
			result.setMimeType("application/pdf");
			result.setLength(BigInteger.valueOf(pdfBytes.length));
			return result;
		} catch (Exception e) {
			log.error("[CadRendition] PDF conversion failed for: " + documentName, e);
			return null;
		}
	}

	@Override
	public String extractText(ContentStream contentStream, String documentName) {
		log.info("[CadRendition] Extracting text from: " + documentName);
		try {
			String ext = FilenameUtils.getExtension(documentName).toLowerCase();
			if (ext.isEmpty()) {
				return null;
			}

			byte[] inputBytes;
			try (InputStream is = contentStream.getStream()) {
				inputBytes = jp.aegif.nemaki.util.io.BoundedIO.readBounded(is, MAX_CAD_SOURCE_BYTES, "CAD source");
			}

			Object cadDoc = readCadDocument(ext, inputBytes);
			if (cadDoc == null) return null;

			return extractCadText(cadDoc);
		} catch (Exception e) {
			log.error("[CadRendition] Text extraction failed for: " + documentName, e);
			return null;
		}
	}

	@Override
	public List<String> getSupportedMimeTypes() {
		return new ArrayList<>(SUPPORTED_MIME_TYPES);
	}

	// --- mathacad integration methods (reflection-based to handle missing JAR gracefully) ---

	private String invokeSvgConversion(Object cadDoc) {
		Class<?> clazz = getMathacadClass();
		if (clazz == null) return null;
		try {
			// Try all methods named toSvgString with 1 parameter
			for (java.lang.reflect.Method m : clazz.getMethods()) {
				if ("toSvgString".equals(m.getName()) && m.getParameterCount() == 1) {
					try {
						return (String) m.invoke(null, cadDoc);
					} catch (Exception ignored) {
						// try next overload
					}
				}
			}
			return null;
		} catch (Exception e) {
			log.error("[CadRendition] SVG conversion via mathacad failed", e);
			return null;
		}
	}

	private byte[] convertCadToPdf(Object cadDoc) {
		Class<?> clazz = getMathacadClass();
		if (clazz == null) return null;
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			// Try toPdf(cadDoc, OutputStream)
			for (java.lang.reflect.Method m : clazz.getMethods()) {
				if ("toPdf".equals(m.getName()) && m.getParameterCount() == 2) {
					m.invoke(null, cadDoc, baos);
					return baos.toByteArray();
				}
			}
			log.warn("[CadRendition] No toPdf method found in mathacad");
			return null;
		} catch (Exception e) {
			log.error("[CadRendition] PDF conversion via mathacad failed", e);
			return null;
		}
	}

	private String extractCadText(Object cadDoc) {
		Class<?> clazz = getMathacadClass();
		if (clazz == null) return null;
		try {
			// Try extractText(cadDoc) or getTextEntities(cadDoc)
			for (java.lang.reflect.Method m : clazz.getMethods()) {
				if (("extractText".equals(m.getName()) || "getTextEntities".equals(m.getName()))
						&& m.getParameterCount() == 1) {
					Object result = m.invoke(null, cadDoc);
					if (result instanceof String) return (String) result;
					if (result instanceof List) {
						StringBuilder sb = new StringBuilder();
						for (Object item : (List<?>) result) {
							if (sb.length() > 0) sb.append("\n");
							sb.append(item.toString());
						}
						return sb.toString();
					}
				}
			}
			log.warn("[CadRendition] No text extraction method found in mathacad");
			return null;
		} catch (Exception e) {
			log.error("[CadRendition] Text extraction via mathacad failed", e);
			return null;
		}
	}

	private Object readCadDocument(String extension, byte[] inputBytes) throws Exception {
		Class<?> clazz = getMathacadClass();
		if (clazz == null) return null;
		String methodName = "read" + extension.substring(0, 1).toUpperCase() + extension.substring(1).toLowerCase();
		// Try readDxf(InputStream), readDwg(InputStream), etc.
		for (java.lang.reflect.Method m : clazz.getMethods()) {
			if (m.getName().equalsIgnoreCase(methodName) && m.getParameterCount() == 1) {
				if (m.getParameterTypes()[0].isAssignableFrom(InputStream.class)) {
					return m.invoke(null, new ByteArrayInputStream(inputBytes));
				}
			}
		}
		log.warn("[CadRendition] No reader method found for extension: " + extension + " (looked for " + methodName + ")");
		return null;
	}
}

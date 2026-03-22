package jp.aegif.nemaki.businesslogic.rendition.impl;

import jp.aegif.nemaki.businesslogic.rendition.ExtendedRenditionManager;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RenditionManager for diagram formats (PlantUML, Graphviz DOT).
 * Uses PlantUML MIT with Smetana pure-Java engine (no native Graphviz required).
 */
public class DiagramRenditionManagerImpl implements ExtendedRenditionManager {

	private static final Log log = LogFactory.getLog(DiagramRenditionManagerImpl.class);

	private static final Set<String> PLANTUML_MIME_TYPES = Set.of(
			"text/x-plantuml"
	);

	private static final Set<String> DOT_MIME_TYPES = Set.of(
			"text/vnd.graphviz"
	);

	private static final Map<String, String> EXTENSION_TO_MIME = new LinkedHashMap<>();
	static {
		EXTENSION_TO_MIME.put("puml", "text/x-plantuml");
		EXTENSION_TO_MIME.put("plantuml", "text/x-plantuml");
		EXTENSION_TO_MIME.put("dot", "text/vnd.graphviz");
		EXTENSION_TO_MIME.put("gv", "text/vnd.graphviz");
	}

	private static final Set<String> ALL_SUPPORTED_MIMES = new LinkedHashSet<>();
	static {
		ALL_SUPPORTED_MIMES.addAll(PLANTUML_MIME_TYPES);
		ALL_SUPPORTED_MIMES.addAll(DOT_MIME_TYPES);
	}

	/** Pre-compiled patterns for text extraction */
	private static final Pattern QUOTE_PATTERN = Pattern.compile("\"([^\"]+)\"");
	private static final Pattern LABEL_PATTERN = Pattern.compile("label\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
	private static final Pattern NODE_PATTERN = Pattern.compile("^\\s*(\\w+)\\s*[\\[;]", Pattern.MULTILINE);

	@Override
	public boolean checkSvgConvertible(String mimeType, String fileName) {
		if (mimeType != null && ALL_SUPPORTED_MIMES.contains(mimeType.toLowerCase())) {
			return true;
		}
		if ("application/octet-stream".equals(mimeType) && fileName != null) {
			String ext = FilenameUtils.getExtension(fileName).toLowerCase();
			return !ext.isEmpty() && EXTENSION_TO_MIME.containsKey(ext);
		}
		return false;
	}

	@Override
	public boolean checkConvertible(String mediatype) {
		return checkSvgConvertible(mediatype, null);
	}

	@Override
	public ContentStream convertToSvg(ContentStream contentStream, String documentName) {
		log.info("[DiagramRendition] Starting SVG conversion for: " + documentName);
		try {
			String source = new String(contentStream.getStream().readAllBytes(), StandardCharsets.UTF_8);
			String mimeType = contentStream.getMimeType();

			// Determine if it's DOT or PlantUML
			boolean isDot = isDotFormat(mimeType, documentName);

			String plantUmlSource;
			if (isDot) {
				// Wrap DOT source in PlantUML @startdot/@enddot
				plantUmlSource = "@startdot\n" + source + "\n@enddot";
			} else {
				plantUmlSource = source;
			}

			// Use PlantUML SourceStringReader to generate SVG
			SourceStringReader reader = new SourceStringReader(plantUmlSource);
			ByteArrayOutputStream svgOut = new ByteArrayOutputStream();
			reader.outputImage(svgOut, new FileFormatOption(FileFormat.SVG));

			byte[] svgBytes = svgOut.toByteArray();
			if (svgBytes.length == 0) {
				log.error("[DiagramRendition] PlantUML produced empty SVG for: " + documentName);
				return null;
			}

			ContentStreamImpl result = new ContentStreamImpl();
			result.setStream(new ByteArrayInputStream(svgBytes));
			result.setFileName(documentName + ".svg");
			result.setMimeType("image/svg+xml");
			result.setLength(BigInteger.valueOf(svgBytes.length));

			log.info("[DiagramRendition] SVG conversion successful for: " + documentName + " (" + svgBytes.length + " bytes)");
			return result;
		} catch (Exception e) {
			log.error("[DiagramRendition] SVG conversion failed for: " + documentName, e);
			return null;
		}
	}

	@Override
	public ContentStream convertToPdf(ContentStream contentStream, String documentName) {
		// Diagram formats are rendered as SVG; PDF not directly supported
		log.info("[DiagramRendition] PDF conversion not directly supported for diagrams, returning null");
		return null;
	}

	@Override
	public String extractText(ContentStream contentStream, String documentName) {
		log.info("[DiagramRendition] Extracting text from: " + documentName);
		try {
			String source = new String(contentStream.getStream().readAllBytes(), StandardCharsets.UTF_8);
			String mimeType = contentStream.getMimeType();
			boolean isDot = isDotFormat(mimeType, documentName);

			if (isDot) {
				return extractDotText(source);
			} else {
				return extractPlantUmlText(source);
			}
		} catch (Exception e) {
			log.error("[DiagramRendition] Text extraction failed for: " + documentName, e);
			return null;
		}
	}

	@Override
	public List<String> getSupportedMimeTypes() {
		return new ArrayList<>(ALL_SUPPORTED_MIMES);
	}

	// --- Private helpers ---

	private boolean isDotFormat(String mimeType, String fileName) {
		if (mimeType != null && DOT_MIME_TYPES.contains(mimeType.toLowerCase())) {
			return true;
		}
		if (fileName != null) {
			String ext = FilenameUtils.getExtension(fileName).toLowerCase();
			return "dot".equals(ext) || "gv".equals(ext);
		}
		return false;
	}

	/**
	 * Extract readable text from PlantUML source:
	 * - Remove @startuml/@enduml directives
	 * - Remove markup symbols (->  --> : | etc.)
	 * - Keep labels, notes, and meaningful text
	 */
	private String extractPlantUmlText(String source) {
		StringBuilder sb = new StringBuilder();
		for (String line : source.split("\n")) {
			String trimmed = line.trim();
			// Skip directives
			if (trimmed.startsWith("@start") || trimmed.startsWith("@end") || trimmed.isEmpty()) {
				continue;
			}
			// Skip pure comments (but extract text from them)
			if (trimmed.startsWith("'")) {
				sb.append(trimmed.substring(1).trim()).append("\n");
				continue;
			}
			// Extract text after colons (labels in sequence/activity diagrams)
			int colonIdx = trimmed.indexOf(':');
			if (colonIdx >= 0 && colonIdx < trimmed.length() - 1) {
				sb.append(trimmed.substring(colonIdx + 1).trim()).append("\n");
			}
			// Extract quoted strings
			Matcher m = QUOTE_PATTERN.matcher(trimmed);
			while (m.find()) {
				sb.append(m.group(1)).append("\n");
			}
			// Extract note content
			if (trimmed.startsWith("note ") || trimmed.equals("end note")) {
				continue; // skip note markers but keep content between them
			}
			// If no special markers, include the line as-is (for notes content etc.)
			if (colonIdx < 0 && !trimmed.contains("->") && !trimmed.contains("-->") &&
					!trimmed.contains("--") && !trimmed.startsWith("participant") &&
					!trimmed.startsWith("actor") && !trimmed.startsWith("class") &&
					!trimmed.startsWith("interface") && !trimmed.startsWith("skinparam") &&
					!trimmed.startsWith("!")) {
				sb.append(trimmed).append("\n");
			}
		}
		return sb.toString().trim();
	}

	/**
	 * Extract readable text from DOT/Graphviz source:
	 * - Extract label attributes
	 * - Extract node names
	 */
	private String extractDotText(String source) {
		StringBuilder sb = new StringBuilder();
		// Extract label="..." attributes
		Matcher m = LABEL_PATTERN.matcher(source);
		while (m.find()) {
			sb.append(m.group(1)).append("\n");
		}
		// Extract node names (simple identifiers before [ or ;)
		Matcher nm = NODE_PATTERN.matcher(source);
		while (nm.find()) {
			String name = nm.group(1);
			// Skip DOT keywords
			if (!"digraph".equalsIgnoreCase(name) && !"graph".equalsIgnoreCase(name) &&
					!"subgraph".equalsIgnoreCase(name) && !"node".equalsIgnoreCase(name) &&
					!"edge".equalsIgnoreCase(name)) {
				sb.append(name).append("\n");
			}
		}
		return sb.toString().trim();
	}
}

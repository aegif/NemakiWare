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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RenditionManager for diagram formats (PlantUML, Graphviz DOT).
 * Uses PlantUML MIT with Smetana pure-Java engine (no native Graphviz required).
 */
public class DiagramRenditionManagerImpl implements ExtendedRenditionManager {

	private static final Log log = LogFactory.getLog(DiagramRenditionManagerImpl.class);

	// Security: diagram source is untrusted user document content. PlantUML's
	// default profile is LEGACY, which permits preprocessor includes that read
	// local files (!include) and fetch URLs (!includeurl) — a local-file-read /
	// SSRF sink. Force the SANDBOX profile (no local file access, no network)
	// before any PlantUML class caches the profile, unless an operator has
	// explicitly chosen one via -DPLANTUML_SECURITY_PROFILE / env.
	static {
		if (isBlank(System.getProperty("PLANTUML_SECURITY_PROFILE"))
				&& isBlank(System.getenv("PLANTUML_SECURITY_PROFILE"))) {
			System.setProperty("PLANTUML_SECURITY_PROFILE", "SANDBOX");
		}
	}

	/** Reject diagram source larger than this (text diagrams are small; guards DoS). */
	private static final int MAX_SOURCE_BYTES = 512 * 1024;
	/** Cap the rendered SVG so a pathological diagram cannot exhaust memory. */
	private static final int MAX_SVG_BYTES = 20 * 1024 * 1024;
	/** Bound render wall-clock so a complex diagram cannot hang a request thread. */
	private static final long RENDER_TIMEOUT_SECONDS = 15;

	private static final ExecutorService RENDER_EXECUTOR = Executors.newThreadPerTaskExecutor(
			Thread.ofVirtual().name("diagram-rendition-", 0).factory());

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

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
			byte[] sourceBytes;
			try {
				// Fail fast at the cap so nothing beyond MAX_SOURCE_BYTES is buffered.
				sourceBytes = jp.aegif.nemaki.util.io.BoundedIO.readBounded(
						contentStream.getStream(), MAX_SOURCE_BYTES, "Diagram source");
			} catch (java.io.IOException tooLarge) {
				log.warn("[DiagramRendition] Diagram source unreadable or too large (limit "
						+ MAX_SOURCE_BYTES + " bytes) for: " + documentName + " — " + tooLarge.getMessage());
				return null;
			}
			String source = new String(sourceBytes, StandardCharsets.UTF_8);
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

			byte[] svgBytes = renderWithLimits(plantUmlSource, documentName);
			if (svgBytes == null || svgBytes.length == 0) {
				log.error("[DiagramRendition] PlantUML produced no SVG for: " + documentName);
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

	/**
	 * Renders PlantUML source to SVG under a wall-clock timeout and a bounded
	 * output buffer. The SANDBOX profile (set in the static initializer) already
	 * blocks file/URL includes; these limits additionally cap CPU/memory for a
	 * pathological but syntactically valid diagram. Returns null on timeout,
	 * output-cap breach, or render error.
	 */
	private byte[] renderWithLimits(String plantUmlSource, String documentName) {
		Future<byte[]> future = RENDER_EXECUTOR.submit(() -> {
			SourceStringReader reader = new SourceStringReader(plantUmlSource);
			BoundedOutputStream svgOut = new BoundedOutputStream(MAX_SVG_BYTES);
			reader.outputImage(svgOut, new FileFormatOption(FileFormat.SVG));
			return svgOut.toByteArray();
		});
		try {
			return future.get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			log.warn("[DiagramRendition] Render exceeded " + RENDER_TIMEOUT_SECONDS
					+ "s time limit for: " + documentName);
			return null;
		} catch (Exception e) {
			future.cancel(true);
			log.error("[DiagramRendition] Render failed for: " + documentName + " - " + e.getMessage());
			return null;
		}
	}

	/** OutputStream that fails fast once more than {@code max} bytes are written. */
	private static final class BoundedOutputStream extends OutputStream {
		private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
		private final int max;

		BoundedOutputStream(int max) {
			this.max = max;
		}

		private void ensureCapacity(int add) throws IOException {
			if ((long) delegate.size() + add > max) {
				throw new IOException("Rendered diagram exceeds output cap of " + max + " bytes");
			}
		}

		@Override
		public void write(int b) throws IOException {
			ensureCapacity(1);
			delegate.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			ensureCapacity(len);
			delegate.write(b, off, len);
		}

		byte[] toByteArray() {
			return delegate.toByteArray();
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
			String source = new String(
					jp.aegif.nemaki.util.io.BoundedIO.readBounded(
							contentStream.getStream(), MAX_SOURCE_BYTES, "Diagram source"),
					StandardCharsets.UTF_8);
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

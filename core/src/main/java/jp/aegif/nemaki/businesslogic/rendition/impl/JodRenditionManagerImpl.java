package jp.aegif.nemaki.businesslogic.rendition.impl;

import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.YamlManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jodconverter.OfficeDocumentConverter;
import org.jodconverter.office.DefaultOfficeManagerBuilder;
import org.jodconverter.document.DefaultDocumentFormatRegistry;
import org.jodconverter.document.DocumentFormat;
import org.jodconverter.office.OfficeException;
import org.jodconverter.office.OfficeManager;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class JodRenditionManagerImpl implements RenditionManager {

	private PropertyManager propertyManager;
	private DefaultDocumentFormatRegistry registry;
	private static final Log log = LogFactory
			.getLog(JodRenditionManagerImpl.class);

	// Singleton OfficeManager to handle concurrent requests
	private OfficeManager officeManager;
	private final Object officeManagerLock = new Object();
	private volatile boolean officeManagerStarted = false;

	@PostConstruct
	public void init() {
		registry = DefaultDocumentFormatRegistry.getInstance();

		String definitionFile = "";
		try {
			definitionFile = propertyManager.readValue(
					PropertyKey.JODCONVERTER_REGISTRY_DATAFORMATS);
		} catch (Exception e) {
			log.error("Cannot read a permission definition file", e);
		}

		// Parse definition file
		YamlManager manager = new YamlManager(definitionFile);
		Object yamlResult = manager.loadYml();
		List<Map<String, Object>> yml = null;
		
		if (yamlResult instanceof List) {
			yml = (List<Map<String, Object>>) yamlResult;
		} else {
			log.warn("YAML result is not a List, skipping rendition format initialization. Result type: " + 
			        (yamlResult != null ? yamlResult.getClass().getName() : "null"));
			return;
		}

		if (CollectionUtils.isNotEmpty(yml)) {
			for (Map<String, Object> format : yml) {
				String name = (String) (format.get("name"));
				String extension = (String) (format.get("extension"));
				String mediaType = (String) (format.get("mediaType"));

				DocumentFormat df = new DocumentFormat(name, extension,
						mediaType);
				registry.addFormat(df);
			}

		}
	}

	/**
	 * Ensure the singleton OfficeManager is started.
	 * Uses double-checked locking for thread safety.
	 */
	private void ensureOfficeManagerStarted() throws OfficeException {
		if (!officeManagerStarted) {
			synchronized (officeManagerLock) {
				if (!officeManagerStarted) {
					String officehome = propertyManager.readValue(PropertyKey.JODCONVERTER_OFFICEHOME);
					log.info("[JodRendition] Initializing singleton OfficeManager with office home: " + officehome);

					// Check if office home exists
					File officeHomeDir = new File(officehome);
					if (!officeHomeDir.exists()) {
						throw new OfficeException("Office home directory does not exist: " + officehome);
					}

					officeManager = new DefaultOfficeManagerBuilder()
							.setOfficeHome(officehome)
							.setPortNumber(8100)
							.build();
					officeManager.start();
					officeManagerStarted = true;
					log.info("[JodRendition] Singleton OfficeManager started successfully on port 8100");
				}
			}
		}
	}

	public ContentStream convertToPdf(ContentStream contentStream,
			String documentName) {
		log.info("[JodRendition] Starting PDF conversion for: " + documentName + ", mimeType: " + contentStream.getMimeType());

		//Skip pdf file (Avoid converting pdf to pdf)
		if(contentStream.getMimeType().equals("application/pdf")){
			log.info("[JodRendition] Skipping PDF file - already PDF");
			return contentStream;
		}

		OutputStream outputStream = null;
		File inputFile = null;
		File outputFile = null;
		try {
			String prefix = getPrefix(documentName);
			String suffix = getSuffix(documentName);
			log.info("[JodRendition] Creating temp file with prefix=" + prefix + ", suffix=." + suffix);

			inputFile = convertInputStreamToFile(prefix, "." + suffix,
					contentStream.getStream());
			inputFile.deleteOnExit();
			log.info("[JodRendition] Input file created: " + inputFile.getAbsolutePath() + ", size=" + inputFile.length());

			outputFile = File.createTempFile("output", ".pdf");
			outputFile.deleteOnExit();
			log.info("[JodRendition] Output file created: " + outputFile.getAbsolutePath());

			// Ensure singleton OfficeManager is started
			ensureOfficeManagerStarted();

			// Synchronize the conversion to prevent concurrent LibreOffice issues
			synchronized (officeManagerLock) {
				log.info("[JodRendition] Starting conversion (synchronized)...");
				OfficeDocumentConverter converter = new OfficeDocumentConverter(officeManager);
				converter.convert(inputFile, outputFile);
				log.info("[JodRendition] Conversion completed, output size=" + outputFile.length());
			}

			// convert back
			FileInputStream fis = new FileInputStream(outputFile);
			ContentStreamImpl result = new ContentStreamImpl();
			result.setStream(fis);
			result.setFileName(contentStream.getFileName());
			result.setMimeType("application/pdf");
			result.setLength(BigInteger.valueOf(outputFile.length()));

			log.info("[JodRendition] PDF conversion successful for: " + documentName);
			return result;

		} catch (IOException e) {
			log.error("[JodRendition] IOException during PDF conversion: " + e.getMessage(), e);
		} catch (OfficeException e) {
			log.error("[JodRendition] OfficeException during PDF conversion: " + e.getMessage(), e);
			// Reset the office manager state so it can be restarted on next request
			synchronized (officeManagerLock) {
				if (officeManager != null) {
					try {
						officeManager.stop();
					} catch (OfficeException ex) {
						log.warn("[JodRendition] Error stopping failed OfficeManager: " + ex.getMessage());
					}
					officeManager = null;
					officeManagerStarted = false;
				}
			}
		} catch (Exception e) {
			log.error("[JodRendition] Unexpected exception during PDF conversion: " + e.getMessage(), e);
		} finally {
			if (outputStream != null) {
				try {
					outputStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			// Note: Don't stop the singleton OfficeManager here - it stays running for reuse
		}

		log.error("[JodRendition] PDF conversion failed for: " + documentName);
		return null;
	}

	/**
	 * @param inputStream
	 * @return
	 * @throws IOException
	 */
	private File convertInputStreamToFile(String prefix, String suffix,
			InputStream inputStream) throws IOException {

		File file = File.createTempFile(prefix, suffix);
		try {
			OutputStream out = new FileOutputStream(file);

			int read = 0;
			byte[] bytes = new byte[1024];

			while ((read = inputStream.read(bytes)) != -1) {
				out.write(bytes, 0, read);
			}
			inputStream.close();
			out.flush();
			out.close();
		} catch (IOException e) {
			if (log.isDebugEnabled()) {
				log.debug("IOException in convertInputStreamToFile: " + e.getMessage());
			}
		}

		return file;
	}

	private String getSuffix(String fileName) {
		if (fileName == null)
			return null;
		int point = fileName.lastIndexOf(".");
		if (point != -1) {
			return fileName.substring(point + 1);
		}
		return fileName;
	}

	private String getPrefix(String fileName) {
		if (fileName == null)
			return null;
		int point = fileName.lastIndexOf(".");
		if (point != -1) {
			return fileName.substring(0, point);
		}
		return fileName;
	}

	public boolean checkConvertible(String mediatype) {
		DocumentFormat df = registry.getFormatByMediaType(mediatype);
		return df != null;
	}
	
	public List<String> getSupportedMimeTypes() {
		List<String> mimeTypes = new ArrayList<>();
		if (registry != null) {
			for (org.jodconverter.document.DocumentFamily family : org.jodconverter.document.DocumentFamily.values()) {
				try {
					java.util.Set<DocumentFormat> formats = registry.getOutputFormats(family);
					if (formats != null) {
						for (DocumentFormat format : formats) {
							if (format.getMediaType() != null && !mimeTypes.contains(format.getMediaType())) {
								mimeTypes.add(format.getMediaType());
							}
						}
					}
				} catch (Exception e) {
					log.debug("Could not get output formats for family: " + family);
				}
			}
		}
		return mimeTypes;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
}

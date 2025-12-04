package jp.aegif.nemaki.businesslogic.rendition.impl;

import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;
import jp.aegif.nemaki.businesslogic.rendition.RenditionMapping;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;


public class JodRenditionManagerImpl implements RenditionManager {

	private PropertyManager propertyManager;
	private DefaultDocumentFormatRegistry registry;
	private static final Log log = LogFactory
			.getLog(JodRenditionManagerImpl.class);

	// Rendition mapping configuration
	private List<RenditionMapping> renditionMappings;
	private boolean renditionEnabled;
	private String defaultKind;

	// Default values for centralized management
	private static final boolean DEFAULT_RENDITION_ENABLED = true;
	private static final String DEFAULT_RENDITION_KIND = "cmis:preview";
	private static final String DEFAULT_MAPPING_FILE = "rendition-mapping.yml";
	private static final boolean DEFAULT_LAZY_CREATE = false;

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

		// NEW: Load rendition enabled flag with default
		String enabledStr = propertyManager.readValue(PropertyKey.RENDITION_ENABLED);
		renditionEnabled = (enabledStr != null) ? Boolean.parseBoolean(enabledStr) : DEFAULT_RENDITION_ENABLED;
		log.info("Rendition feature enabled: " + renditionEnabled);

		// NEW: Load default kind with normalization
		String configuredKind = propertyManager.readValue(PropertyKey.RENDITION_DEFAULT_KIND);
		defaultKind = normalizeRenditionKind(configuredKind);
		log.info("Default rendition kind: " + defaultKind);

		// NEW: Load rendition mapping
		loadRenditionMapping();
	}

	/**
	 * Normalize rendition kind to CMIS-compliant format.
	 * Accepts both 'preview' and 'cmis:preview', normalizes to 'cmis:preview'.
	 * This addresses the requirement for 'preview' as default while maintaining CMIS compliance.
	 */
	private String normalizeRenditionKind(String kind) {
		if (kind == null || kind.isEmpty()) {
			return DEFAULT_RENDITION_KIND;
		}

		// If already has cmis: prefix, return as-is
		if (kind.startsWith("cmis:")) {
			return kind;
		}

		// Normalize short form to CMIS form
		String cmisKind = "cmis:" + kind;
		log.debug("Normalized rendition kind from '" + kind + "' to '" + cmisKind + "'");
		return cmisKind;
	}

	@SuppressWarnings("unchecked")
	private void loadRenditionMapping() {
		renditionMappings = new ArrayList<>();
		String mappingFile = propertyManager.readValue(PropertyKey.RENDITION_MAPPING_DEFINITION);
		if (mappingFile == null || mappingFile.isEmpty()) {
			mappingFile = DEFAULT_MAPPING_FILE;
		}

		try {
			YamlManager yamlManager = new YamlManager(mappingFile);
			Object yamlResult = yamlManager.loadYml();
			
			if (yamlResult instanceof Map) {
				Map<String, Object> ymlMap = (Map<String, Object>) yamlResult;
				List<Map<String, Object>> mappings = (List<Map<String, Object>>) ymlMap.get("mappings");

				if (mappings != null) {
					for (Map<String, Object> mapping : mappings) {
						RenditionMapping rm = new RenditionMapping();
						rm.setSourceMediaTypes((List<String>) mapping.get("sourceMediaTypes"));
						rm.setConverter((String) mapping.get("converter"));
						rm.setTargetMediaType((String) mapping.get("targetMediaType"));
						String kind = (String) mapping.get("kind");
						rm.setKind(normalizeRenditionKind(kind));
						renditionMappings.add(rm);
					}
					log.info("Loaded " + renditionMappings.size() + " rendition mappings from " + mappingFile);
				}
			} else {
				log.warn("Rendition mapping YAML is not a Map, using defaults");
				addDefaultMappings();
			}
		} catch (Exception e) {
			log.warn("Failed to load rendition mapping from " + mappingFile + ", using defaults", e);
			addDefaultMappings();
		}

		if (renditionMappings.isEmpty()) {
			log.info("No rendition mappings loaded, adding defaults");
			addDefaultMappings();
		}
	}

	private void addDefaultMappings() {
		// Word
		RenditionMapping word = new RenditionMapping();
		word.setSourceMediaTypes(Arrays.asList(
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
			"application/msword"
		));
		word.setConverter("jod");
		word.setTargetMediaType("application/pdf");
		word.setKind(defaultKind);
		renditionMappings.add(word);

		// Excel
		RenditionMapping excel = new RenditionMapping();
		excel.setSourceMediaTypes(Arrays.asList(
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
			"application/vnd.ms-excel"
		));
		excel.setConverter("jod");
		excel.setTargetMediaType("application/pdf");
		excel.setKind(defaultKind);
		renditionMappings.add(excel);

		// PowerPoint
		RenditionMapping ppt = new RenditionMapping();
		ppt.setSourceMediaTypes(Arrays.asList(
			"application/vnd.openxmlformats-officedocument.presentationml.presentation",
			"application/vnd.ms-powerpoint"
		));
		ppt.setConverter("jod");
		ppt.setTargetMediaType("application/pdf");
		ppt.setKind(defaultKind);
		renditionMappings.add(ppt);

		// OpenDocument formats
		RenditionMapping odf = new RenditionMapping();
		odf.setSourceMediaTypes(Arrays.asList(
			"application/vnd.oasis.opendocument.text",
			"application/vnd.oasis.opendocument.spreadsheet",
			"application/vnd.oasis.opendocument.presentation"
		));
		odf.setConverter("jod");
		odf.setTargetMediaType("application/pdf");
		odf.setKind(defaultKind);
		renditionMappings.add(odf);

		log.info("Added " + renditionMappings.size() + " default rendition mappings");
	}

	public ContentStream convertToPdf(ContentStream contentStream,
			String documentName) {
		//Skip pdf file (Avoid converting pdf to pdf)
		if(contentStream.getMimeType().equals("application/pdf")){
			return contentStream;
		}
		
		OutputStream outputStream = null;
		try {
			String prefix = getPrefix(documentName);
			String suffix = getSuffix(documentName);
			File inputFile = convertInputStreamToFile(prefix, "." + suffix,
					contentStream.getStream());
			inputFile.deleteOnExit();
			File outputFile = File.createTempFile("output", ".pdf");
			outputFile.deleteOnExit();

			String officehome = propertyManager
					.readValue(PropertyKey.JODCONVERTER_OFFICEHOME);
/*
			OfficeManager officeManager = new DefaultOfficeManagerConfiguration()
					.setPortNumber(8100).setOfficeHome(officehome)
					.buildOfficeManager();
*/
			//TODO: retrieve port number from conf
			OfficeManager officeManager = new DefaultOfficeManagerBuilder().setOfficeHome(officehome).setPortNumber(8100).build();
			officeManager.start();

			OfficeDocumentConverter converter = new OfficeDocumentConverter(
					officeManager);
			converter.convert(inputFile, outputFile);

			officeManager.stop();

			// convert back
			FileInputStream fis = new FileInputStream(outputFile);
			ContentStreamImpl result = new ContentStreamImpl();
			result.setStream(fis);
			result.setFileName(contentStream.getFileName());
			result.setMimeType("application/pdf");
			result.setLength(BigInteger.valueOf(outputFile.length()));

			return result;

		} catch (IOException e) {
			// TODO Auto-generated catch block
			// log.debug(e);
		} catch (OfficeException e) {
			log.warn(e);
		} finally {

			if (outputStream != null) {
				try {
					// outputStream.flush();
					outputStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}

			}
		}

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

	@Override
	public String getTargetMimeType(String sourceMimeType) {
		for (RenditionMapping mapping : renditionMappings) {
			if (mapping.getSourceMediaTypes() != null && 
				mapping.getSourceMediaTypes().contains(sourceMimeType)) {
				return mapping.getTargetMediaType();
			}
		}
		return null;
	}

	@Override
	public String getRenditionKind(String sourceMimeType) {
		for (RenditionMapping mapping : renditionMappings) {
			if (mapping.getSourceMediaTypes() != null && 
				mapping.getSourceMediaTypes().contains(sourceMimeType)) {
				return mapping.getKind();
			}
		}
		return defaultKind;
	}

	@Override
	public boolean isRenditionEnabled() {
		return renditionEnabled;
	}

	@Override
	public List<String> getSupportedSourceMimeTypes() {
		List<String> result = new ArrayList<>();
		for (RenditionMapping mapping : renditionMappings) {
			if (mapping.getSourceMediaTypes() != null) {
				result.addAll(mapping.getSourceMediaTypes());
			}
		}
		return result;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
}

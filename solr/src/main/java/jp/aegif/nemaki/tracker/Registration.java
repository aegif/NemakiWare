package jp.aegif.nemaki.tracker;

import jp.aegif.nemaki.util.Constant;
import jp.aegif.nemaki.util.NemakiCacheManager;
import org.apache.chemistry.opencmis.client.api.ChangeEvent;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.SecondaryType;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.core.SolrCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import static org.apache.solr.handler.extraction.ExtractingParams.LITERALS_PREFIX;
import static org.apache.solr.handler.extraction.ExtractingParams.UNKNOWN_FIELD_PREFIX;

public class Registration implements Runnable {

	Session cmisSession;
	SolrCore core;
	SolrServer repositoryServer;
	List<ChangeEvent> list;
	boolean mimeTypeFilterEnabled;
	List<String> allowedMimeTypeFilter;
	boolean fulltextEnabled;
	NemakiCacheManager cache;

	private static final Logger logger = LoggerFactory.getLogger(Registration.class);

	public Registration(Session cmisSession, SolrCore core, SolrServer repositoryServer, List<ChangeEvent> list,
			boolean fulltextEnabled, boolean mimeTypeFilterEnabled, List<String> allowedMimeTypeFilter, NemakiCacheManager cache) {
		this.cmisSession = cmisSession;
		this.core = core;
		this.repositoryServer = repositoryServer;
		this.list = list;
		this.fulltextEnabled = fulltextEnabled;
		this.mimeTypeFilterEnabled = mimeTypeFilterEnabled;
		this.allowedMimeTypeFilter = allowedMimeTypeFilter;
		this.cache = cache;
	}

	@Override
	public void run() {
		logger.info("Start registration {} change event(s)", list.size());

		// Read MIME-Type filtering
		for (ChangeEvent ce : list) {
			logger.info("Run Registration : Type={}, Id={}" ,ce.getChangeType(), ce.getObjectId());

			// cache clean
			cache.delete(ce.getObjectId(), ce.getChangeTime());

			switch (ce.getChangeType()) {
			case CREATED:
				registerSolrDocument(ce, fulltextEnabled, mimeTypeFilterEnabled, allowedMimeTypeFilter);
				break;
			case UPDATED:
				registerSolrDocument(ce, fulltextEnabled, mimeTypeFilterEnabled, allowedMimeTypeFilter);
				break;
			case DELETED:
				deleteSolrDocument(ce);
				continue;
			default:
				//SECURITY is iqnore
				break;
			}
		}
	}

	/**
	 * Create/Update Solr document
	 *
	 * @param ce
	 * @param fulltextEnabled
	 *            TODO
	 */
	private void registerSolrDocument(ChangeEvent ce, boolean fulltextEnabled, boolean mimeTypeFilter,
			List<String> allowedMimeTypes) {
		CmisObject obj = null;
		try {
			obj = cmisSession.getObject(ce.getObjectId());
		} catch (Exception e) {
			logger.info("[ObjectId={}]CmisObject is deleted. Skip reading a change event.", ce.getObjectId());
			return;
		}
		AbstractUpdateRequest req = null;
		Map<String, Object> map = buildParamMap(obj);
		switch (obj.getBaseTypeId()) {
		case CMIS_RELATIONSHIP:
			return;
		case CMIS_ITEM:
			return;
		case CMIS_DOCUMENT:
			if (fulltextEnabled) {
				String mimeType = (String) map.get(Constant.FIELD_CONTENT_MIMETYPE);
				if (!mimeTypeFilter
						|| CollectionUtils.isNotEmpty(allowedMimeTypes) && allowedMimeTypes.contains(mimeType)) {
					ContentStream cs = cmisSession.getContentStream(new ObjectIdImpl(obj.getId()));
					req = buildUpdateRequestWithFile(map, cs);
				} else {
					req = buildUpdateRequest(map);
				}
			} else {
				req = buildUpdateRequest(map);
			}

			break;
		case CMIS_FOLDER:
//		case CMIS_ITEM:
//		case CMIS_RELATIONSHIP:
			req = buildUpdateRequest(map);
			break;
		default:
			// All other document types are not indexed.
			return;
		}

		if (ce.getChangeType() == ChangeType.CREATED) {
			try {
				// Clear tree cache 
				clearTreeCache(obj);
			} catch (Exception e) {
				logger.error("[ObjectId={}]{}", ce.getObjectId(), "Failed to delete tree cache.");
			}
		}
		
		String successMsg = "";
		String errMsg = "";
		switch (ce.getChangeType()) {
		case CREATED:
			successMsg = "Successfully created";
			errMsg = "Failed to create";
			break;
		case UPDATED:
			successMsg = "Successfully updated";
			errMsg = "Failed to update";
			break;
		default:
			break;
		}

		// Send a request to Solr
		try {
			repositoryServer.request(req);
			logger.info("[ObjectId={}]{}", ce.getObjectId(), successMsg);
		} catch (Exception e) {
			logger.error("[ObjectId={}]{}", ce.getObjectId(), errMsg);
		} finally {
			// Delete temp files
			try {
				deleteTempFile(req);
			} catch (Exception e) {
				logger.error("[ObjectId={}]Error occurred during deleting temp files.", ce.getObjectId(), e);
			}
		}
	}

	private void deleteTempFile(AbstractUpdateRequest req) throws IOException, URISyntaxException {
		logger.info("Start deleteTempFile");
		Collection<org.apache.solr.common.util.ContentStream> streams = req.getContentStreams();
		Iterator<org.apache.solr.common.util.ContentStream> itr = streams.iterator();
		if (itr.hasNext()) {
			org.apache.solr.common.util.ContentStream stream = itr.next();
			String sourceInfo = stream.getSourceInfo();

			if (sourceInfo.startsWith("file:")) {
				File f = new File(new URI(sourceInfo));
				if (f != null && f.isFile()) {
					f.delete();
				}
			}
		}
	}

	/**
	 * Delete Solr document
	 *
	 * @param ce
	 */
	private void deleteSolrDocument(ChangeEvent ce) {
		logger.info("Start deleteSolrDocument");
		try {
			// Check if the SolrDocument exists
			SolrQuery solrQuery = new SolrQuery();
			solrQuery.setQuery(Constant.FIELD_OBJECT_ID + ":" + ce.getObjectId());
			QueryResponse resp = repositoryServer.query(solrQuery);
			if (resp != null && resp.getResults() != null) {
				if (resp.getResults().getNumFound() == 0) {
					logger.info("[ObjectId={}]DELETED type change event is skipped because there is no SolrDocument",
							ce.getObjectId());
					return;
				}
			} else {
				logger.error("{}:Something wrong in the connection to Solr server", core.getName());
			}
			
			try {
				// Clear tree cache
				String parentId = (String)resp.getResults().get(0).getFieldValue(Constant.FIELD_PARENT_ID);
				clearTreeCache(parentId);
			} catch (Exception ex) {
				logger.error("[ObjectId={}]{}", ce.getObjectId(), "Failed to delete tree cache.");
			}

			// Delete			
			String repositoryId = cmisSession.getRepositoryInfo().getId();
			String objectId = ce.getObjectId();
			repositoryServer.deleteById(buildUniqueId(repositoryId, objectId));
			repositoryServer.commit();
			logger.info("[ObjectId={}]Successfully deleted.", ce.getObjectId());
		} catch (Exception e) {
			logger.error("[ObjectId={}]Failed.", ce.getObjectId(), e);
		}
	}

	/**
	 * Build update request with file to Solr
	 *
	 * @param map
	 * @param inputStream
	 * @return
	 */
	// NOTION: SolrCell seems not to accept a capital property name.
	// For example, "parentId" doesn't work.
	private AbstractUpdateRequest buildUpdateRequestWithFile(Map<String, Object> map, ContentStream inputStream) {
		ContentStreamUpdateRequest up = new ContentStreamUpdateRequest("/update/extract");

		// Set File Stream
		try {
			File file = convertInputStreamToFile(inputStream.getStream());
			up.addFile(file, inputStream.getMimeType());
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Set field values
		// NOTION:
		// Cast to String works on the assumption they are already String
		// so that ModifiableSolrParams can have an argument Map<String,
		// String[]>.
		// Any other better way?
		Map<String, String[]> m = new HashMap<String, String[]>();
		// for a field with capital letters
		m.put("lowernames", new String[] { "false" });
		// Ignored(for schema.xml, ignoring some SolrCell meta fields)
		m.put(UNKNOWN_FIELD_PREFIX, new String[] { "ignored_" });

		Set<String> keys = map.keySet();
		Iterator<String> iterator = keys.iterator();
		while (iterator.hasNext()) {
			String key = iterator.next();
			// Multi value
			Object val = map.get(key);
			if (val instanceof List<?>) {
				m.put(LITERALS_PREFIX + key, ((List<String>) val).toArray(new String[0]));
				// Single value
			} else if (val instanceof String) {
				String[] _val = { (String) val };
				m.put(LITERALS_PREFIX + key, _val);
			}
		}

		up.setParams(new ModifiableSolrParams(m));
		up.setAction(AbstractUpdateRequest.ACTION.COMMIT, true, true);

		return up;
	}

	/**
	 * Build an update request to Solr without file
	 *
	 * @param map
	 * @return
	 */
	public AbstractUpdateRequest buildUpdateRequest(Map<String, Object> map) {
		UpdateRequest up = new UpdateRequest();
		SolrInputDocument sid = new SolrInputDocument();

		// Set SolrDocument parameters
		Iterator<String> iterator = map.keySet().iterator();
		while (iterator.hasNext()) {
			String key = iterator.next();
			sid.addField(key, map.get(key));
		}

		// Set UpdateRequest
		up.add(sid);
		// Ignored(for schema.xml, ignoring some SolrCell meta fields)
		up.setParam(UNKNOWN_FIELD_PREFIX, "ignored_");

		// Set Solr action parameter
		up.setAction(AbstractUpdateRequest.ACTION.COMMIT, true, true);
		return up;
	}

	/**
	 * @param inputStream
	 * @return
	 * @throws IOException
	 */
	private File convertInputStreamToFile(InputStream inputStream) throws IOException {

		File file = File.createTempFile(String.valueOf(System.currentTimeMillis()), null);
		file.deleteOnExit();

		try {
			// write the inputStream to a FileOutputStream
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
			logger.error("Error occurred during output file stream.", e);
		}
		return file;
	}

	/**
	 *
	 * @param object
	 * @return
	 */
	private Map<String, Object> buildParamMap(CmisObject object) {
		Map<String, Object> map = new HashMap<String, Object>();
		buildBaseParamMap(map, object);
		// BaseType specific property
		switch (object.getBaseTypeId()) {
		case CMIS_DOCUMENT:
			map.put(Constant.FIELD_CONTENT_ID, object.getPropertyValue(PropertyIds.CONTENT_STREAM_ID));
			map.put(Constant.FIELD_CONTENT_NAME, object.getPropertyValue(PropertyIds.CONTENT_STREAM_FILE_NAME));
			map.put(Constant.FIELD_CONTENT_MIMETYPE, object.getPropertyValue(PropertyIds.CONTENT_STREAM_MIME_TYPE));
			map.put(Constant.FIELD_CONTENT_LENGTH,
					object.getPropertyValue(PropertyIds.CONTENT_STREAM_LENGTH).toString());
			map.put(Constant.FIELD_VERSION_LABEL, object.getPropertyValue(PropertyIds.VERSION_LABEL));
			String isMajorVersion = (object.getPropertyValue(PropertyIds.IS_MAJOR_VERSION) == null) ? null
					: object.getPropertyValue(PropertyIds.IS_MAJOR_VERSION).toString();
			map.put(Constant.FIELD_IS_MAJOR_VEERSION, isMajorVersion);
			String isLatestVersion = (object.getPropertyValue(PropertyIds.IS_LATEST_VERSION) == null) ? null
					: object.getPropertyValue(PropertyIds.IS_LATEST_VERSION).toString();
			map.put(Constant.FIELD_IS_LATEST_VERSION, isLatestVersion);
			map.put(Constant.FIELD_VERSION_SERIES_ID, object.getPropertyValue(PropertyIds.VERSION_SERIES_ID));
			String isCheckedOut = (object.getPropertyValue(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT) == null) ? null
					: object.getPropertyValue(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT).toString();
			map.put(Constant.FIELD_IS_CHECKEDOUT, isCheckedOut);
			map.put(Constant.FIELD_CHECKEDOUT_ID, object.getPropertyValue(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID));
			map.put(Constant.FIELD_CHECKEDOUT_BY, object.getPropertyValue(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY));
			map.put(Constant.FIELD_CHECKIN_COMMENT, object.getPropertyValue(PropertyIds.CHECKIN_COMMENT));
			String isPrivateWorkingCopy = (object.getPropertyValue(PropertyIds.IS_PRIVATE_WORKING_COPY) == null) ? null
					: object.getPropertyValue(PropertyIds.IS_PRIVATE_WORKING_COPY).toString();
			map.put(Constant.FIELD_IS_PRIVATE_WORKING_COPY, isPrivateWorkingCopy);

			ObjectParentData parent = getParent(object);
			map.put(Constant.FIELD_PARENT_ID, parent.getObject().getId());
			break;
		case CMIS_FOLDER:
			map.put(Constant.FIELD_PARENT_ID, object.getPropertyValue(PropertyIds.PARENT_ID));
			map.put(Constant.FIELD_PATH, object.getPropertyValue(PropertyIds.PATH));
		default:
			return map;
		}

		// SubType & Secondary property
		buildDynamicParamMap(map, object);

		return map;
	}

	private void buildBaseParamMap(Map<String, Object> map, CmisObject object) {
		logger.info("Build BaseParam: " + object.toString());
		String repositoryId = cmisSession.getRepositoryInfo().getId();

		String objectId = object.getId();
		map.put(Constant.FIELD_ID, buildUniqueId(repositoryId, objectId));
		map.put(Constant.FIELD_REPOSITORY_ID, repositoryId);
		map.put(Constant.FIELD_OBJECT_ID, objectId);
		map.put(Constant.FIELD_NAME, object.getName());
		map.put(Constant.FIELD_DESCRIPTION, object.getDescription());
		map.put(Constant.FIELD_BASE_TYPE, object.getBaseTypeId().value());
		map.put(Constant.FIELD_OBJECT_TYPE, object.getType().getQueryName());
		map.put(Constant.FIELD_SECONDARY_OBJECT_TYPE_IDS, getSecondaryIds(object));
		map.put(Constant.FIELD_CREATED, getUTC(object.getCreationDate()));
		map.put(Constant.FIELD_CREATOR, object.getCreatedBy());
		map.put(Constant.FIELD_MODIFIED, getUTC(object.getLastModificationDate()));
		map.put(Constant.FIELD_MODIFIER, object.getLastModifiedBy());
	}

	private String buildUniqueId(String repositoryId, String objectId) {
		return repositoryId + "_" + objectId;
	}

	private List<String> getSecondaryIds(CmisObject object) {
		List<SecondaryType> secondaryTypes = object.getSecondaryTypes();
		if (CollectionUtils.isEmpty(secondaryTypes)) {
			return new ArrayList<String>();
		} else {
			List<String> list = new ArrayList<String>();
			Iterator<SecondaryType> iterator = secondaryTypes.iterator();
			while (iterator.hasNext()) {
				list.add(iterator.next().getId());
			}
			return list;
		}
	}

	/**
	 * For properties other than those of baseType. They are indexed regardless
	 * of its "queryable" flag in case the flag is changed later.
	 *
	 * @param map
	 * @param object
	 */
	private void buildDynamicParamMap(Map<String, Object> map, CmisObject object) {
		Map<String, PropertyDefinition<?>> propDefs = object.getType().getPropertyDefinitions();
		Map<String, PropertyDefinition<?>> basePropDefs = object.getBaseType().getPropertyDefinitions();
		logger.info("Build Dynamic Param");
		for (String propId : propDefs.keySet()) {
			if (!basePropDefs.containsKey(propId)) {
				boolean isSecondary = false;
				PropertyDefinition<?> pd = propDefs.get(propId);
				Object propValue;
				String propPrefix;
				if (pd.getPropertyType() == PropertyType.DATETIME && object.getPropertyValue(propId) != null) {
					propValue = getUTC(object.getPropertyValue(propId));
					propPrefix = "dynamicDate.property.";
				} else {
					propValue = object.getPropertyValue(propId);
					propPrefix = "dynamic.property.";
				}

				// Secondary type
				List<SecondaryType> secs = object.getSecondaryTypes();
				if (CollectionUtils.isNotEmpty(secs)) {
					for (SecondaryType sec : secs) {
						Map<String, PropertyDefinition<?>> secondaryPropDefs = sec.getPropertyDefinitions();
						// Secondary specific property
						if (secondaryPropDefs.containsKey(propId)) {
							String type = propPrefix + sec.getQueryName() + Constant.SEPARATOR + propId;
							map.put(type, propValue);
							isSecondary = true;
							break;
						}
					}
				}

				// Non-Secondary type
				if (!isSecondary) {
					String type = propPrefix + propId;
					map.put(type, propValue);
				}
			}
		}
	}

	/**
	 * Clear target parent's tree cache
	 * @param object
	 */
	private void clearTreeCache(CmisObject object) {
		ObjectParentData parent = getParent(object);
		if (parent != null) {
			String parentId = parent.getObject().getId();
			clearTreeCache(parentId);
		}
	}
	
	/**
	 * Clear target tree cache
	 * @param objectId
	 */
	private void clearTreeCache(String objectId) {
		cache.deleteTree(objectId);
	}

	/**
	 * Get target parent
	 * @param object
	 * @return
	 */
	private ObjectParentData getParent(CmisObject object) {
		List<ObjectParentData> parents = cmisSession.getBinding().getNavigationService().getObjectParents(
				cmisSession.getRepositoryInfo().getId(), object.getId(), null, false, null, null, true, null);
		return (parents.size() != 0)? parents.get(0): null;
	}
	
	/**
	 *
	 * @param cal
	 * @return
	 */
	private String getUTC(GregorianCalendar cal) {
		DateFormat df = new SimpleDateFormat(Constant.DATETIME_FORMAT);
		df.setTimeZone(TimeZone.getTimeZone("UTC"));
		String timestamp = df.format(cal.getTime());
		return timestamp;
	}
}

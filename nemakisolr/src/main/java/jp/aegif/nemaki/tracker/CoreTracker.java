/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.tracker;

import static org.apache.solr.handler.extraction.ExtractingParams.LITERALS_PREFIX;
import static org.apache.solr.handler.extraction.ExtractingParams.UNKNOWN_FIELD_PREFIX;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import jp.aegif.nemaki.NemakiCoreAdminHandler;
import jp.aegif.nemaki.util.PropertyKey;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.StringPool;
import jp.aegif.nemaki.util.impl.PropertyManagerImpl;

import org.apache.chemistry.opencmis.client.api.ChangeEvent;
import org.apache.chemistry.opencmis.client.api.ChangeEvents;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.SecondaryType;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.SolrCore;

/**
 * Index tracking class
 *
 * @author linzhixing
 *
 */
public class CoreTracker extends CloseHook {

	Logger logger = Logger.getLogger(CoreTracker.class);

	NemakiCoreAdminHandler adminHandler;
	SolrCore core;
	SolrServer repositoryServer;
	SolrServer tokenServer;
	Session cmisSession;

	private final String MODE_FULL = "FULL";

	private final String PROP_URL = "url";

	private final String FIELD_ID = "id";
	private final String FIELD_NAME = "name";
	private final String FIELD_DESCRIPTION = "cmis_description";
	private final String FIELD_BASE_TYPE = "basetype";
	private final String FIELD_OBJECT_TYPE = "objecttype";
	private final String FIELD_SECONDARY_OBJECT_TYPE_IDS = "secondary_object_type_ids";
	private final String FIELD_CREATED = "created";
	private final String FIELD_CREATOR = "creator";
	private final String FIELD_MODIFIED = "modified";
	private final String FIELD_MODIFIER = "modifier";

	private final String FIELD_CONTENT_ID = "content_id";
	private final String FIELD_CONTENT_NAME = "content_name";
	private final String FIELD_CONTENT_MIMETYPE = "content_mimetype";
	private final String FIELD_CONTENT_LENGTH = "content_length";
	private final String FIELD_IS_MAJOR_VEERSION = "is_major_version";
	private final String FIELD_IS_PRIVATE_WORKING_COPY = "is_pwc";
	private final String FIELD_IS_CHECKEDOUT = "is_checkedout";
	private final String FIELD_CHECKEDOUT_BY = "checkedout_by";
	private final String FIELD_CHECKEDOUT_ID = "checkedout_id";
	private final String FIELD_CHECKIN_COMMENT = "checkein_comment";
	private final String FIELD_VERSION_LABEL = "version_label";
	private final String FIELD_VERSION_SERIES_ID = "version_series_id";

	private final String FIELD_PARENT_ID = "parent_id";
	private final String FIELD_PATH = "path";

	private final String FIELD_TOKEN = "change_token";

	private final String separator = ".";

	public CoreTracker(NemakiCoreAdminHandler adminHandler, SolrCore core,
			SolrServer repositoryServer, SolrServer tokenServer) {
		super();

		this.adminHandler = adminHandler;
		this.core = core;
		this.repositoryServer = repositoryServer;
		this.tokenServer = tokenServer;
	}

	public void setupCmisSession() {
		PropertyManager pm = new PropertyManagerImpl(StringPool.PROPERTIES_PATH);

		String protocol = pm.readValue(PropertyKey.CMIS_SERVER_PROTOCOL);
		String host = pm.readValue(PropertyKey.CMIS_SERVER_HOST);
		String port = pm.readValue(PropertyKey.CMIS_SERVER_PORT);
		String context = pm.readValue(PropertyKey.CMIS_SERVER_CONTEXT);
		String wsEndpoint = pm.readValue(PropertyKey.CMIS_SERVER_WS_ENDPOINT);
		String url = getCmisUrl(protocol, host, port, context, wsEndpoint);

		String repository = pm.readValue(PropertyKey.CMIS_REPOSITORY_MAIN);
		String country = pm.readValue(PropertyKey.CMIS_LOCALE_COUNTRY);
		String language = pm.readValue(PropertyKey.CMIS_LOCALE_LANGUAGE);
		String user = pm.readValue(PropertyKey.CMIS_PRINCIPAL_ADMIN_ID);
		String password = pm.readValue(PropertyKey.CMIS_PRINCIPAL_ADMIN_PASSWORD);

		SessionFactory f = SessionFactoryImpl.newInstance();
		Map<String, String> parameter = new HashMap<String, String>();

		// user credentials
		parameter.put(SessionParameter.USER, user);
		parameter.put(SessionParameter.PASSWORD, password);

		// session locale
		parameter.put(SessionParameter.LOCALE_ISO3166_COUNTRY, country);
		parameter.put(SessionParameter.LOCALE_ISO639_LANGUAGE, language);

		// repository
		parameter.put(SessionParameter.REPOSITORY_ID, repository);

		// WebServices ports
		parameter.put(SessionParameter.BINDING_TYPE,
				BindingType.WEBSERVICES.value());

		parameter.put(SessionParameter.WEBSERVICES_ACL_SERVICE, url
				+ "ACLService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_DISCOVERY_SERVICE, url
				+ "DiscoveryService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_MULTIFILING_SERVICE, url
				+ "MultiFilingService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_NAVIGATION_SERVICE, url
				+ "NavigationService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_OBJECT_SERVICE, url
				+ "ObjectService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_POLICY_SERVICE, url
				+ "PolicyService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_RELATIONSHIP_SERVICE, url
				+ "RelationshipService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_REPOSITORY_SERVICE, url
				+ "RepositoryService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_VERSIONING_SERVICE, url
				+ "VersioningService?wsdl");

		// create session
		try {
			cmisSession = f.createSession(parameter);
			OperationContext operationContext = cmisSession.createOperationContext(null,
					false, false, false, null, null, false, null, true, 100);
			cmisSession.setDefaultContext(operationContext);
		} catch (Exception e) {
			logger.error("Failed to create a session to CMIS server", e);
		}
	}

	private String getCmisUrl(String protocol, String host, String port, String context, String wsEndpoint){
		try {
			URL url = new URL(protocol, host, Integer.parseInt(port), "");
			return url.toString() + "/" + context + "/" + wsEndpoint + "/";
		} catch (NumberFormatException e) {
			logger.error("", e);
			e.printStackTrace();
		} catch (MalformedURLException e) {
			logger.error("", e);
		}
		return null;
	}

	public boolean isConnectionSetup() {
		return (this.cmisSession != null);
	}

	@Override
	public void preClose(SolrCore core) {
	}

	@Override
	public void postClose(SolrCore core) {
	}

	/**
	 * Initialize a specified Solr core
	 */
	public void initCore() {
		try {
			// Initialize all documents
			repositoryServer.deleteByQuery("*:*");
			repositoryServer.commit();
			logger.info(core.getName() + ":Successfully initialized!");

			storeLatestChangeToken("");

		} catch (SolrServerException e) {
			logger.error(core.getName() + ":Initialization failed!", e);
		} catch (IOException e) {
			logger.error(core.getName() + ":Initialization failed!", e);
		}
	}

	/**
	 * Read CMIS change logs and Index them
	 *
	 * @param trackingType
	 */
	public void index(String trackingType) {
		ChangeEvents changeEvents = getCmisChangeLog(trackingType);
		List<ChangeEvent> events = changeEvents.getChangeEvents();

		// After 2nd crawling, discard the first item
		// Because the specs say that it's included in the results
		String token = readLatestChangeToken();

		if (!StringUtils.isEmpty(token)) {
			if (!org.apache.commons.collections.CollectionUtils.isEmpty(events)) {
				events.remove(0);
			}
		}

		if (events.isEmpty())
			return;

		// Extract only the last events of each objectId
		List<ChangeEvent> list = extractChangeEvent(events);
		for (ChangeEvent ce : list) {
			switch (ce.getChangeType()) {
			case CREATED:
				registerSolrDocument(ce);
				break;
			case UPDATED:
				registerSolrDocument(ce);
				break;
			case DELETED:
				deleteSolrDocument(ce);
				continue;
			default:
				break;
			}
		}

		// Save the latest token
		storeLatestChangeToken(changeEvents.getLatestChangeLogToken());
	}

	/**
	 * Get the last change token stored in Solr
	 * @return
	 */
	private String readLatestChangeToken(){
		SolrQuery solrQuery = new SolrQuery();
		solrQuery.setQuery(FIELD_ID + ":" + FIELD_TOKEN);

		QueryResponse resp = null;
		try {
			resp = tokenServer.query(solrQuery);
		} catch (SolrServerException e) {
			e.printStackTrace();
		}

		String latestChangeToken = "";
		if (resp != null && resp.getResults() != null
				&& resp.getResults().getNumFound() != 0) {
			SolrDocument doc = resp.getResults().get(0);
			latestChangeToken = (String)doc.get(FIELD_TOKEN);

		}else{
			logger.error("Failed to read the latest change token in Solr!");
		}

		return latestChangeToken;
	}

	/**
	 * Store the last change token in Solr
	 * @return
	 */
	private void storeLatestChangeToken(String token){

		Map<String, Object> map = new HashMap<String, Object>();
		map.put(FIELD_ID, FIELD_TOKEN);
		map.put(FIELD_TOKEN, token);

		AbstractUpdateRequest req = buildUpdateRequest(map);

		try {
			tokenServer.request(req);
		} catch (SolrServerException e) {
			logger.error("Failed to store latest change token in Solr!", e);
		} catch (IOException e) {
			logger.error("Failed to store latest change token in Solr!", e);
		}
	}

	/**
	 * Get CMIS change logs
	 *
	 * @param trackingType
	 * @return
	 */
	private ChangeEvents getCmisChangeLog(String trackingType) {
		PropertyManager cmisMgr = new PropertyManagerImpl(StringPool.PROPERTIES_PATH);

		String _latestToken = readLatestChangeToken();
		String latestToken = (trackingType.equals(MODE_FULL) || StringUtils
				.isEmpty(_latestToken)) ? null : _latestToken;

		String _numItems = cmisMgr.readValue(PropertyKey.CMIS_CHANGELOG_ITEMS);
		long numItems = (StringUtils.isEmpty(_numItems)) ? 100 : Long.valueOf(_numItems);

		ChangeEvents changeEvents = cmisSession.getContentChanges(latestToken,
				false, numItems);

		// No need for Sorting
		// (Specification requires they are returned by ASCENDING)

		return changeEvents;
	}

	/**
	 *
	 * @param events
	 * @return
	 */
	private List<ChangeEvent> extractChangeEvent(List<ChangeEvent> events) {
		List<ChangeEvent> list = new ArrayList<ChangeEvent>();
		Set<String> objectIds = new HashSet<String>();

		int size = events.size();
		ListIterator<ChangeEvent> iterator = events.listIterator(size);
		while (iterator.hasPrevious()) {
			ChangeEvent event = iterator.previous();
			if (objectIds.contains(event.getObjectId())) {
				continue;
			} else {
				objectIds.add(event.getObjectId());
				list.add(event);
			}
		}

		Collections.reverse(list);
		return list;
	}

	/**
	 * Create/Update Solr document
	 *
	 * @param ce
	 */
	private void registerSolrDocument(ChangeEvent ce) {
		CmisObject obj = null;
		try {
			obj = cmisSession.getObject(ce.getObjectId());
		} catch (Exception e) {
			logger.warn("[objectId=" + ce.getObjectId()
					+ "]object is deleted. Skip reading a change event.");
			return;
		}

		AbstractUpdateRequest req = null;
		Map<String, Object> map = buildParamMap(obj);
		switch (obj.getBaseTypeId()) {
		case CMIS_DOCUMENT:
			ContentStream cs = cmisSession.getContentStream(new ObjectIdImpl(
					obj.getId()));
			req = buildUpdateRequestWithFile(map, cs);
			break;
		case CMIS_FOLDER:
			req = buildUpdateRequest(map);
			break;
		default:
			break;
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
			logger.info(logPrefix(ce) + successMsg);
		} catch (Exception e) {
			logger.error(logPrefix(ce) + errMsg, e);
		}
	}

	/**
	 * Delete Solr document
	 *
	 * @param ce
	 */
	private void deleteSolrDocument(ChangeEvent ce) {
		try {
			// Check if the SolrDocument exists
			SolrQuery solrQuery = new SolrQuery();
			solrQuery.setQuery(FIELD_ID + ":" + ce.getObjectId());
			QueryResponse resp = repositoryServer.query(solrQuery);
			if (resp != null && resp.getResults() != null) {
				if (resp.getResults().getNumFound() == 0) {
					logger.info(logPrefix(ce)
							+ "DELETED type change event is skipped because there is no SolrDocument");
					return;
				}
			} else {
				logger.error(core.getName()
						+ ":Something wrong in the connection to Solr server");
			}

			// Delete
			repositoryServer.deleteById(ce.getObjectId());
			repositoryServer.commit();
			logger.info(logPrefix(ce) + "Successfully deleted");
		} catch (Exception e) {
			logger.error(logPrefix(ce) + "Failed to ", e);
		}
	}

	private String logPrefix(ChangeEvent ce) {
		return "[objectId=" + ce.getObjectId() + "]";
	}

	/**
	 * Build update request with file to Solr
	 *
	 * @param content
	 * @param inputStream
	 * @return
	 */
	// NOTION: SolrCell seems not to accept a capital property name.
	// For example, "parentId" doesn't work.
	private AbstractUpdateRequest buildUpdateRequestWithFile(
			Map<String, Object> map, ContentStream inputStream) {
		ContentStreamUpdateRequest up = new ContentStreamUpdateRequest(
				"/update/extract");

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
				m.put(LITERALS_PREFIX + key,
						((List<String>) val).toArray(new String[0]));
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
	 * @param content
	 * @return
	 */
	private AbstractUpdateRequest buildUpdateRequest(Map<String, Object> map) {
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
	private File convertInputStreamToFile(InputStream inputStream)
			throws IOException {

		File file = File.createTempFile(
				String.valueOf(System.currentTimeMillis()), null);
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
			System.out.println(e.getMessage());
		}
		return file;
	}

	/**
	 *
	 * @param content
	 * @return
	 */
	private Map<String, Object> buildParamMap(CmisObject object) {
		Map<String, Object> map = new HashMap<String, Object>();

		buildBaseParamMap(map, object);

		// BaseType specific property
		switch (object.getBaseTypeId()) {
		case CMIS_DOCUMENT:
			map.put(FIELD_CONTENT_ID,
					object.getPropertyValue(PropertyIds.CONTENT_STREAM_ID));
			map.put(FIELD_CONTENT_NAME, object
					.getPropertyValue(PropertyIds.CONTENT_STREAM_FILE_NAME));
			map.put(FIELD_CONTENT_MIMETYPE, object
					.getPropertyValue(PropertyIds.CONTENT_STREAM_MIME_TYPE));
			map.put(FIELD_CONTENT_LENGTH,
					object.getPropertyValue(PropertyIds.CONTENT_STREAM_LENGTH)
							.toString());
			map.put(FIELD_VERSION_LABEL,
					object.getPropertyValue(PropertyIds.VERSION_LABEL));
			String isMajorVersion = (object
					.getPropertyValue(PropertyIds.IS_MAJOR_VERSION) == null) ? null
					: object.getPropertyValue(PropertyIds.IS_MAJOR_VERSION)
							.toString();
			map.put(FIELD_IS_MAJOR_VEERSION, isMajorVersion);
			map.put(FIELD_VERSION_SERIES_ID,
					object.getPropertyValue(PropertyIds.VERSION_SERIES_ID));
			String isCheckedOut = (object
					.getPropertyValue(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT) == null) ? null
					: object.getPropertyValue(
							PropertyIds.IS_VERSION_SERIES_CHECKED_OUT)
							.toString();
			map.put(FIELD_IS_CHECKEDOUT, isCheckedOut);
			map.put(FIELD_CHECKEDOUT_ID,
					object.getPropertyValue(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID));
			map.put(FIELD_CHECKEDOUT_BY,
					object.getPropertyValue(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY));
			map.put(FIELD_CHECKIN_COMMENT,
					object.getPropertyValue(PropertyIds.CHECKIN_COMMENT));
			String isPrivateWorkingCopy = (object
					.getPropertyValue(PropertyIds.IS_PRIVATE_WORKING_COPY) == null) ? null
					: object.getPropertyValue(
							PropertyIds.IS_PRIVATE_WORKING_COPY).toString();
			map.put(FIELD_IS_PRIVATE_WORKING_COPY, isPrivateWorkingCopy);

			ObjectParentData parent= getParent(object);
			map.put(FIELD_PARENT_ID,
					parent.getObject().getId());
			break;
		case CMIS_FOLDER:
			map.put(FIELD_PARENT_ID,
					object.getPropertyValue(PropertyIds.PARENT_ID));
			map.put(FIELD_PATH, object.getPropertyValue(PropertyIds.PATH));
		default:
			break;
		}

		// SubType & Secondary property
		buildDynamicParamMap(map, object);

		return map;
	}

	private void buildBaseParamMap(Map<String, Object> map, CmisObject object) {
		map.put(FIELD_NAME, object.getName());
		map.put(FIELD_DESCRIPTION, object.getDescription());
		map.put(FIELD_ID, object.getId());
		map.put(FIELD_BASE_TYPE, object.getBaseTypeId().value());
		map.put(FIELD_OBJECT_TYPE, object.getType().getQueryName());
		map.put(FIELD_SECONDARY_OBJECT_TYPE_IDS, getSecondaryIds(object));
		map.put(FIELD_CREATED, getUTC(object.getCreationDate()));
		map.put(FIELD_CREATOR, object.getCreatedBy());
		map.put(FIELD_MODIFIED, getUTC(object.getLastModificationDate()));
		map.put(FIELD_MODIFIER, object.getLastModifiedBy());
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
		Map<String, PropertyDefinition<?>> propDefs = object.getType()
				.getPropertyDefinitions();
		Map<String, PropertyDefinition<?>> basePropDefs = object.getBaseType()
				.getPropertyDefinitions();

		for (String propId : propDefs.keySet()) {
			if (!basePropDefs.containsKey(propId)) {
				boolean isSecondary = false;

				// Secondary type
				List<SecondaryType> secs = object.getSecondaryTypes();
				if (CollectionUtils.isNotEmpty(secs)) {
					for (SecondaryType sec : secs) {
						Map<String, PropertyDefinition<?>> secondaryPropDefs = sec
								.getPropertyDefinitions();
						// Secondary specific property
						if (secondaryPropDefs.containsKey(propId)) {
							String type = "dynamic.property."
									+ sec.getQueryName() + separator + propId;
							map.put(type, object.getPropertyValue(propId));
							isSecondary = true;
							break;
						}
					}
				}

				// Non-Secondary type
				if (!isSecondary) {
					String type = "dynamic.property." + propId;
					map.put(type, object.getPropertyValue(propId));
				}
			}
		}
	}

	private ObjectParentData getParent(CmisObject object){
		List<ObjectParentData> parents =
		cmisSession
		.getBinding()
		.getNavigationService()
		.getObjectParents(cmisSession.getRepositoryInfo().getId(),
				object.getId(), null, false, null, null, true, null);
		return parents.get(0);
	}

	/**
	 *
	 * @param cal
	 * @return
	 */
	private String getUTC(GregorianCalendar cal) {
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		df.setTimeZone(TimeZone.getTimeZone("UTC"));
		String timestamp = df.format(cal.getTime());
		return timestamp;
	}

	private String sanitizeUrl(String url) {
		String end = url.substring(url.length() - 1, url.length() - 1);
		if (end.equals("/")) {
			return url;
		} else {
			return url + "/";
		}
	}


}

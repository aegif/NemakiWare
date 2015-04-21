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

import static org.apache.solr.handler.extraction.ExtractingParams.UNKNOWN_FIELD_PREFIX;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import jp.aegif.nemaki.NemakiCoreAdminHandler;
import jp.aegif.nemaki.util.Constant;
import jp.aegif.nemaki.util.PropertyKey;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.StringPool;
import jp.aegif.nemaki.util.impl.PropertyManagerImpl;

import org.apache.chemistry.opencmis.client.api.ChangeEvent;
import org.apache.chemistry.opencmis.client.api.ChangeEvents;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.SolrCore;

/**
 * Index tracking class
 *
 * @author linzhixing
 *
 */
public class CoreTracker extends CloseHook {

	private static final Object LOCK = new Object();

	Logger logger = Logger.getLogger(CoreTracker.class);

	NemakiCoreAdminHandler adminHandler;
	SolrCore core;
	SolrServer repositoryServer;
	SolrServer tokenServer;
	Session cmisSession;

	public CoreTracker(NemakiCoreAdminHandler adminHandler, SolrCore core,
			SolrServer repositoryServer, SolrServer tokenServer) {
		super();

		this.adminHandler = adminHandler;
		this.core = core;
		this.repositoryServer = repositoryServer;
		this.tokenServer = tokenServer;
	}

	public void setupCmisSession() {
		PropertyManager pm = new PropertyManagerImpl(StringPool.PROPERTIES_NAME);

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
		String password = pm
				.readValue(PropertyKey.CMIS_PRINCIPAL_ADMIN_PASSWORD);

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
			OperationContext operationContext = cmisSession
					.createOperationContext(null, false, false, false, null,
							null, false, null, true, 100);
			cmisSession.setDefaultContext(operationContext);
		} catch (Exception e) {
			logger.error("Failed to create a session to CMIS server", e);
		}
	}

	private String getCmisUrl(String protocol, String host, String port,
			String context, String wsEndpoint) {
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
		synchronized (LOCK) {
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
	}

	/**
	 * Read CMIS change logs and Index them
	 *
	 * @param trackingType
	 */
	public void index(String trackingType) {
		synchronized (LOCK) {
			ChangeEvents changeEvents = getCmisChangeLog(trackingType);
			List<ChangeEvent> events = changeEvents.getChangeEvents();

			// After 2nd crawling, discard the first item
			// Because the specs say that it's included in the results
			String token = readLatestChangeToken();

			if (!StringUtils.isEmpty(token)) {
				if (!org.apache.commons.collections.CollectionUtils
						.isEmpty(events)) {
					events.remove(0);
				}
			}

			if (events.isEmpty())
				return;

			// Read MIME-Type filtering
			PropertyManager pm = new PropertyManagerImpl(
					StringPool.PROPERTIES_NAME);
			boolean mimeTypeFilter = false;
			new ArrayList<String>();
			boolean fulltextEnabled = Boolean.TRUE.toString().equalsIgnoreCase(
					pm.readValue(PropertyKey.SOLR_TRACKING_FULLTEXT_ENABLED));

			if (fulltextEnabled) {
				String _filter = pm
						.readValue(PropertyKey.SOLR_TRACKING_MIMETYPE_FILTER_ENABLED);
				mimeTypeFilter = Boolean.TRUE.toString().equalsIgnoreCase(
						_filter);
				if (mimeTypeFilter) {
					pm
							.readValues(PropertyKey.SOLR_TRACKING_MIMETYPE);
				}
			}

			// Extract only the last events of each objectId
			List<ChangeEvent> list = extractChangeEvent(events);

			PropertyManager propMgr = new PropertyManagerImpl(StringPool.PROPERTIES_NAME);
			int numberOfThread = Integer.valueOf(propMgr.readValue(PropertyKey.SOLR_TRACKING_NUMBER_OF_THREAD));
			int numberPerThread = list.size()/ numberOfThread;
			if(list.size() < numberOfThread){
				numberOfThread = list.size();
				numberPerThread = 1;
			}
			
			for (int i = 0; i < numberOfThread; i++) {
				int toIndex = (numberPerThread * (i + 1) > list.size()) ? list
						.size() : numberPerThread * (i + 1);

				List<ChangeEvent> listPerThread = list.subList(numberPerThread
						* i, toIndex);
				Registration registration = new Registration(cmisSession, core,
						repositoryServer, listPerThread);
				Thread t = new Thread(registration);
				t.start();
				try {
					t.join();
				} catch (InterruptedException e) {
					logger.error(e);
				}
			}

			// Save the latest token
			storeLatestChangeToken(changeEvents.getLatestChangeLogToken());
			
			//In case of FUll mode, repeat until indexing all change logs
			if(Constant.MODE_FULL.equals(trackingType)){
				index(Constant.MODE_FULL);
			}
		}
	}

	/**
	 * Get the last change token stored in Solr
	 * 
	 * @return
	 */
	private String readLatestChangeToken() {
		SolrQuery solrQuery = new SolrQuery();
		solrQuery.setQuery(Constant.FIELD_ID + ":" + Constant.FIELD_TOKEN);

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
			latestChangeToken = (String) doc.get(Constant.FIELD_TOKEN);

		} else {
			logger.error("Failed to read the latest change token in Solr!");
		}

		return latestChangeToken;
	}

	/**
	 * Store the last change token in Solr
	 * 
	 * @return
	 */
	private void storeLatestChangeToken(String token) {

		Map<String, Object> map = new HashMap<String, Object>();
		map.put(Constant.FIELD_ID, Constant.FIELD_TOKEN);
		map.put(Constant.FIELD_TOKEN, token);

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
		PropertyManager propMgr = new PropertyManagerImpl(
				StringPool.PROPERTIES_NAME);

		String _latestToken = readLatestChangeToken();
		String latestToken = (StringUtils.isEmpty(_latestToken)) ? null : _latestToken;

		long _numItems = 0;
		if(Constant.MODE_DELTA.equals(trackingType)){
			_numItems = Long.valueOf(propMgr
					.readValue(PropertyKey.CMIS_CHANGELOG_ITEMS_DELTA)); 
		}else if(Constant.MODE_FULL.equals(trackingType)){
			_numItems = Long.valueOf(propMgr
					.readValue(PropertyKey.CMIS_CHANGELOG_ITEMS_FULL)); 
		}
		
		long numItems = (-1 == _numItems) ? Long.MAX_VALUE : Long
				.valueOf(_numItems);

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
	 * Build an update request to Solr without file
	 *
	 * @param content
	 * @return
	 */
	//TODO Unify that of Registration class
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
}
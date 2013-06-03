package jp.aegif.nemaki.tracker;

import static org.apache.solr.handler.extraction.ExtractingParams.LITERALS_PREFIX;
import static org.apache.solr.handler.extraction.ExtractingParams.UNKNOWN_FIELD_PREFIX;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import jp.aegif.nemaki.NemakiCoreAdminHandler;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.service.DaoService;
import jp.aegif.nemaki.service.impl.CouchDaoServiceImpl;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.impl.PropertyManagerImpl;

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.SolrCore;
import org.ektorp.AttachmentInputStream;
import org.ektorp.changes.DocumentChange;

/**
 * Index tracking class
 * @author linzhixing
 *
 */
public class CoreTracker extends CloseHook {

	private final String PATH_TRACKING = "tracking.properties";
	Logger logger = Logger.getLogger(CoreTracker.class);
	
	NemakiCoreAdminHandler adminHandler;
	SolrCore core;
	SolrServer server;
	DaoService dao;

	private final String PROP_TYPE = "type";
	private final String PROP_ID = "id";
	private final String PROP_NAME = "name";
	private final String PROP_PARENT_ID = "parentid";
	private final String PROP_PATH = "path";
	private final String PROP_CREATED = "created";
	private final String PROP_CREATOR = "creator";
	private final String PROP_MODIFIED = "modified";
	private final String PROP_MODIFIER = "modifier";
	private final String PROP_IS_LATEST_VERSION = "is_latest_version";
	
	public CoreTracker(NemakiCoreAdminHandler adminHandler, SolrCore core,
			SolrServer server) {
		super();
		this.adminHandler = adminHandler;
		this.core = core;
		this.server = server;
		this.dao = (DaoService) new CouchDaoServiceImpl();
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
			server.deleteByQuery("*:*");
			server.commit();

			logger.info(core.getName() + ":Successfully initialized!");

			// Initialize the last sequence number
			PropertyManager propertyManager = new PropertyManagerImpl(
					PATH_TRACKING);
			propertyManager.modifyValue("seq", "0");
		} catch (SolrServerException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Execute index tracking
	 * @param tracking
	 */
	public void indexNodes(String tracking) {
		List<String> mimetypes = getTrackingMimeType();
		PropertyManager propertyManager = new PropertyManagerImpl(PATH_TRACKING);
		
		// Get DocumentChanges, consisting of latest changes of each document
		List<DocumentChange> dcs = getFilteredChanges(tracking);
		// Sort by ascending sequence
		Collections.sort(dcs, new DocumentChangeComparator());

		//Execute indexing by iterating DocumentChange 
		Iterator<DocumentChange> iterator = dcs.iterator();
		while (iterator.hasNext()) {
			DocumentChange dc = iterator.next();
			String docId = dc.getId();
			Content content = dao.getContent(docId); // content

			// Check whether to DELETE or UPDATE against Solr
			// When "DELETED" DocumentChange: DELETE
			if (dc.isDeleted()) {
				try {
					server.deleteById(docId);
					server.commit();
					logger.info("Successfully deleted from Solr");
				} catch (SolrServerException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
				continue;
			}

			// When "CREATED" or "UPDATED" DocumentChange: INDEX
			// First, Check the document has ContetStream or not.
			boolean updateFile = false;
			AttachmentInputStream inputStream = null;
			if (content.getType().equals("cmis:document")) { // Only for document type
				String latestId = dao.getLatestAttachment(docId).getId();
				if (!latestId.equals(getSolrAttachmentId(docId))) {
					//Retrieve inputStream when it exists
					inputStream = dao.getInlineAttachment(latestId);
					//Check inputStream is included in the user-customized MIME-types
					if (mimetypes.contains(inputStream.getContentType())) {
						updateFile = true;
					}
				}
			}

			// Build an update request depending on the existence of ContentStream
			// If you want to change tracked properties, 
			// dont't forget to modify both methods!!
			Map<String, String>map = buildParamMap(content);
			
			AbstractUpdateRequest update;
			if (updateFile) {
				// update request by SolrCell
				update = buildUpdateRequestWithFile(map, inputStream);
			} else {
				//update request without SolrCell
				update = buildUpdateRequest(map);
			}

			// Send a request to Solr
			try {
				server.request(update);
			} catch (SolrServerException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}

			// Save the sequence number to the property file
			propertyManager
					.modifyValue("seq", String.valueOf(dc.getSequence()));
			logger.info("[Registerd]" + "seq:" + dc.getSequence() +  ", id:" + docId);
		}
	}

	/**
	 * 
	 * @return
	 */
	private List<String> getTrackingMimeType() {
		PropertyManager pm = new PropertyManagerImpl("nemakisolr.properties");
		return pm.readValues("tracking.mimetype");
	}
	
	/**
	 * Filter CouchDB changes log to be indexed
	 * @param tracking
	 * @return
	 */
	private List<DocumentChange> getFilteredChanges(String tracking) {
		PropertyManager propertyManager = new PropertyManagerImpl(PATH_TRACKING);
		String seq = propertyManager.readValue("seq");
		int lastIndexed = Integer.parseInt(seq);

		// When tracking mode is FULL: init seq from which index will be tracked.
		if (tracking.equals("FULL")) {
			lastIndexed = 0;
			propertyManager.modifyValue("seq", String.valueOf(lastIndexed));
		}

		Set<String> documents = new HashSet<String>();
		List<DocumentChange> dcs = new ArrayList<DocumentChange>();

		// Retrieve change logs from CouchDB
		List<DocumentChange> changes = dao.getChangeLog(lastIndexed);
		Iterator<DocumentChange> iterator = changes.iterator();

		// Make a set of all changed document's id without duplication
		while (iterator.hasNext()) {
			DocumentChange dc = iterator.next();
			documents.add(dc.getId());
		}

		// Extract the indexing DocumentChange of each changed document
		Iterator<String> docIterator = documents.iterator();
		while (docIterator.hasNext()) {
			String id = docIterator.next();

			// Make a collection of same id DocumentChange
			List<DocumentChange> sameIdDcs = new ArrayList<DocumentChange>();
			for (DocumentChange dc : changes) {
				if (id.equals(dc.getId())) {
					sameIdDcs.add(dc);
				}
			}

			// Sort by ascending order of document id
			Collections.sort(sameIdDcs, new DocumentChangeComparator());

			// Get the latest DocumentChange
			DocumentChange lastDc = sameIdDcs.get(sameIdDcs.size() - 1);

			// Filtering
			// For "DELETED" type DocumentChange
			if (lastDc.isDeleted()) {
				// When DELETED from Solr: add it to the list to be deleted from
				// the index
				if (existInSolr(lastDc.getId())) {
					dcs.add(lastDc);
				} else {
					continue;
				}
				// For "CREATED" or "UPDATED" type DocumentChange
			} else {
				Content content = dao.getContent(lastDc.getId());
				// Filer it to the Document or Folder type
				if (content != null
						&& content.getType() != null
						&& (content.getType().equals("cmis:document") || content
								.getType().equals("cmis:folder"))) {
					dcs.add(lastDc);
				} else {
					continue;
				}
			}
		}
		return dcs;
	}

	/**
	 * Check existence of the given id in Solr
	 * @param docId
	 * @return
	 */
	private boolean existInSolr(String docId) {
		SolrParams params = new SolrQuery("id:" + docId);

		SolrDocumentList list = new SolrDocumentList();
		try {
			QueryResponse qrsp = server.query(params);
			list = qrsp.getResults();
		} catch (SolrServerException e) {
			e.printStackTrace();
		}

		if (list.getNumFound() > 0) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Get attachement id from a Solr document of given id
	 * @param docId
	 * @return
	 */
	private String getSolrAttachmentId(String docId) {
		SolrParams params = new SolrQuery("id:" + docId);

		SolrDocumentList list = new SolrDocumentList();
		try {
			QueryResponse qrsp = server.query(params);
			list = qrsp.getResults();
		} catch (SolrServerException e1) {
			e1.printStackTrace();
		}

		String sa = null;
		if (list.getNumFound() != 0) {
			SolrDocument sd = list.get(0);
			if (sd.get("attachment") != null) {
				sa = (String) sd.get("attachment");
			}
		}
		return sa;
	}

	/**
	 * Build update request with file to Solr
	 * @param content
	 * @param inputStream
	 * @return
	 */
	// NOTION: SolrCell seems not to accept a capital property name.
	// For example, "parentId" doesn't work.
	private AbstractUpdateRequest buildUpdateRequestWithFile(Map<String, String> map,
			AttachmentInputStream inputStream) {
		ContentStreamUpdateRequest up = new ContentStreamUpdateRequest(
				"/update/extract");
		
		up.setParam("lowernames", "false");		//for a field with capital letters 
		
		// Set File Stream
		try {
			File file = convertInputStreamToFile(inputStream);
			up.addFile(file, inputStream.getContentType());
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Set SolrDocument parameters
		Set<String> keys = map.keySet();
		Iterator<String> iterator = keys.iterator();
		while(iterator.hasNext()){
			String key  = iterator.next();
			up.setParam(LITERALS_PREFIX + key, map.get(key));
		}
		
		// Ignored(for schema.xml, ignoring some SolrCell meta fields)
		up.setParam(UNKNOWN_FIELD_PREFIX, "ignored_");

		// Set Solr action parameter
		up.setAction(AbstractUpdateRequest.ACTION.COMMIT, true, true);
		return (AbstractUpdateRequest) up;
	}
	
	/**
	 * Build an update request to Solr without file
	 * 
	 * @param content
	 * @return
	 */
	private AbstractUpdateRequest buildUpdateRequest(Map<String,String>map) {
		UpdateRequest up = new UpdateRequest();
		SolrInputDocument sid = new SolrInputDocument();
		
		//Set SolrDocument parameters
		Iterator<String> iterator = map.keySet().iterator();
		while(iterator.hasNext()){
			String key = iterator.next();
			sid.addField(key, map.get(key));
		}
		
		//Set UpdateRequest
		up.add(sid);
		// Ignored(for schema.xml, ignoring some SolrCell meta fields)
		//up.setParam(UNKNOWN_FIELD_PREFIX, "ignored_");
		up.setAction(AbstractUpdateRequest.ACTION.COMMIT, true, true);

		return (AbstractUpdateRequest) up;
	}
	
	/**
	 * @param inputStream
	 * @return
	 * @throws IOException
	 */
	private File convertInputStreamToFile(AttachmentInputStream inputStream)
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
			System.out.println("New file created!");
		} catch (IOException e) {
			System.out.println(e.getMessage());
		}
		return file;
	}

	/**
	 * Inner Class for comparing DocumentChange by seq
	 */
	private class DocumentChangeComparator implements Comparator<DocumentChange> {
		public int compare(DocumentChange dc1, DocumentChange dc2) {
			return dc1.getSequence() - dc2.getSequence();
		}
	}
	
	/**
	 * 
	 * @param content
	 * @return
	 */
	private Map<String,String>buildParamMap(Content content){
		Map <String, String> map = new HashMap<String, String>();
		//ID
		map.put(PROP_ID, content.getId());
		//NAME
		map.put(PROP_NAME, content.getName());
		//PARENT ID
		map.put(PROP_PARENT_ID, content.getParentId());
		//PATH
		map.put(PROP_PATH, content.getPath());
		//CREATION DATE
		GregorianCalendar gcCreated = content.getCreated();
		map.put(PROP_CREATED, getUTC(gcCreated));
		//CREATOR
		map.put(PROP_CREATOR, content.getCreator());
		//MODIFICATION DATE
		GregorianCalendar gcModified = (content.getModified() == null) ? content.getCreated() : content.getModified();
		map.put(PROP_MODIFIED, getUTC(gcModified));
		//MODIFIER
		map.put(PROP_MODIFIER, content.getModifier());
		//TYPE
		map.put(PROP_TYPE, content.getType());
		//ASPECT
		map = setAspects(map, content);
		//LATEST VERSION
		if(content.isDocument()){
			map.put(PROP_IS_LATEST_VERSION, content.isLatestVersion().toString());
		}
		
		return map;
	}

	/**
	 * 
	 * @param cal
	 * @return
	 */
	private String getUTC(GregorianCalendar cal){
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		df.setTimeZone(TimeZone.getTimeZone("UTC"));
		String timestamp = df.format(cal.getTime());
		return timestamp;
	}
	
	/**
	 * Return a map with a key like "aspect:<aspectName>:<keyName>" and its value 
	 */
	private Map<String,String>setAspects(Map<String,String>map, Content content){
		final String separator = ":";

		List<Aspect> aspects = content.getAspects();
		Iterator<Aspect> iterator = aspects.iterator();
		while (iterator.hasNext()) {
			Aspect aspect = iterator.next();
			String field = "aspect" + separator + aspect.getName() + separator;
			List<Property> properties = aspect.getProperties();
			Iterator<Property> propIterator = properties.iterator();
			while(propIterator.hasNext()){
				Property property = propIterator.next();
				field += property.getKey();		//QueryName?
				map.put(field, property.getValue().toString());
			}
		}
		return map;
	}

}

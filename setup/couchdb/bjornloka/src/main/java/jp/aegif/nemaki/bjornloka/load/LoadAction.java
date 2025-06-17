package jp.aegif.nemaki.bjornloka.load;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.collections4.CollectionUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jp.aegif.nemaki.bjornloka.proxy.CouchProxy;
import jp.aegif.nemaki.bjornloka.util.Indicator;
import jp.aegif.nemaki.bjornloka.util.Util;

public abstract class LoadAction {
	protected String url;
	protected String repositoryId;
	protected File file;
	protected boolean force;
	
	protected LoadAction(String url, String repositoryId, File file, boolean force) {
		super();
		this.url = Util.sanitizeUrl(url);
		this.repositoryId = repositoryId;
		this.file = file;
		this.force = force;
	}
	
	public static LoadAction getInstance (String url, String repositoryId, File file, boolean force){
		switch(Util.checkProxyType(url)){
		case EKTORP: 
			return new LoadEktorp(url, repositoryId, file, force);
		case CLOUDANT:
			return new LoadCloudant(url, repositoryId, file, force);
		}
		return null;
	}

	public abstract boolean load();
	public abstract boolean initRepository();
	
	protected boolean action(CouchProxy proxy, File file, boolean initResult){
		if(!initResult){
			System.out.println("Database already exists");
			return false;
		}
		
		//CouchDB documents(without attachments)
				List<ObjectNode> documents = new ArrayList<ObjectNode>();
				//All attachments
				Map<String,ObjectNode> payloads = new HashMap<String, ObjectNode>();
		
		try {
			//Read all dump data from file
			JsonNode _dump = new ObjectMapper().readTree(file);
			ArrayNode dump = (ArrayNode) _dump;

			//Parse each entry in dump
			Iterator<JsonNode> iterator = dump.iterator();
			while(iterator.hasNext()){
				JsonNode _entry = iterator.next();
				//Document
				ObjectNode document = (ObjectNode) _entry.get("document");
				processDocument(document); //remove some fields
				documents.add(document);

				//Attachments
				ObjectNode attachments = (ObjectNode) _entry.get("attachments");
				String docId = document.get("_id").textValue();
				payloads.put(docId, attachments);
			}
		} catch (JsonProcessingException e) {
			e.printStackTrace();
			return false;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}

		//Load documents to DB
		System.out.println("Loading metadata: START");
		
		//TEST
		List<String> documentsResult = new ArrayList<String>();
		int unit = 5000;
		int turn = documents.size() / unit;
		Indicator metadataIndicator = new Indicator(documents.size());
		for(int i=0; i <= turn ; i++){
			int toIndex = (unit*(i+1) > documents.size()) ? documents.size() : unit*(i+1);
			List<ObjectNode> subList = documents.subList(i*unit, toIndex);
			documentsResult.addAll(proxy.bulkInsert(subList));
			
			metadataIndicator.indicate(unit);
		}
		
		if(CollectionUtils.isNotEmpty(documentsResult)){
			System.err.println("Documents are not imported because of some error.");
			System.err.println(documentsResult);
			return false;
		}
		System.out.println("Loading metadata: END");

		//Load all attachments to DB
		System.out.println("Loading attachments: START");
		Indicator attachmentIndicator = new Indicator(payloads.size());
		for(Entry<String, ObjectNode> entry : payloads.entrySet()){
			ObjectNode attachments = entry.getValue();
			Iterator<String> it = attachments.fieldNames();
			while(it.hasNext()){
				String attachmentId = it.next();
				ObjectNode attachment = (ObjectNode) attachments.get(attachmentId);

				try {
					proxy.createAttachment(entry.getKey(), attachmentId, attachment);
				} catch (Exception e) {
					e.printStackTrace();
				}
				
			}
			
			attachmentIndicator.indicate();
		}

		System.out.println("Loading attachments: END");
		return true;
	}

	private void processDocument(ObjectNode document) {
		document.remove("_rev");
		document.remove("_attachments");
		
		if (document.has("_id")) {
			String originalId = document.get("_id").textValue();
			String repositorySpecificId = repositoryId + "_" + originalId;
			document.put("_id", repositorySpecificId);
			System.out.println("Transformed object ID: " + originalId + " -> " + repositorySpecificId);
		}
		
		transformInternalReferences(document);
	}
	
	private void transformInternalReferences(ObjectNode document) {
		if (document.has("parentId") && !document.get("parentId").isNull()) {
			String originalParentId = document.get("parentId").textValue();
			String repositorySpecificParentId = repositoryId + "_" + originalParentId;
			document.put("parentId", repositorySpecificParentId);
		}
		
		Iterator<Entry<String, JsonNode>> fields = document.fields();
		while (fields.hasNext()) {
			Entry<String, JsonNode> field = fields.next();
			String fieldName = field.getKey();
			JsonNode fieldValue = field.getValue();
			
			if (fieldValue.isTextual() && isObjectIdReference(fieldName, fieldValue.textValue())) {
				String originalRef = fieldValue.textValue();
				String repositorySpecificRef = repositoryId + "_" + originalRef;
				document.put(fieldName, repositorySpecificRef);
			}
		}
	}
	
	private boolean isObjectIdReference(String fieldName, String value) {
		return (fieldName.toLowerCase().contains("id") || 
				fieldName.toLowerCase().contains("ref")) && 
				value.length() == 32 && 
				value.matches("[a-f0-9]+");
	}
}

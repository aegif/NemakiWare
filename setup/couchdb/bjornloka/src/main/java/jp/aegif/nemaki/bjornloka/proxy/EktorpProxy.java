package jp.aegif.nemaki.bjornloka.proxy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.ektorp.AttachmentInputStream;
import org.ektorp.CouchDbConnector;
import org.ektorp.CouchDbInstance;
import org.ektorp.DocumentOperationResult;
import org.ektorp.ViewQuery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;

import jp.aegif.nemaki.bjornloka.StringPool;
import jp.aegif.nemaki.bjornloka.util.Util;

public class EktorpProxy implements CouchProxy{
	CouchDbInstance dbInstance;
	CouchDbConnector connector;
	
	public EktorpProxy(CouchDbInstance dbInstance, CouchDbConnector connector) {
		super();
		this.connector = connector;
		this.dbInstance = dbInstance;
	}

	@Override
	public List<String> getAllDocIds() {
		return connector.getAllDocIds();
	}

	@Override
	public List<ObjectNode> getDocs(List<String> keys) {
		ViewQuery query = new ViewQuery().allDocs().includeDocs(true).keys(keys);
		List<ObjectNode> results = connector.queryView(query, ObjectNode.class);
		return results;
	}

	@Override
	public Map<String, Map<String, Object>> getAttachments(JsonNode o) {
		String docId = o.get(StringPool.FIELD_ID).textValue();
		JsonNode _attachments = o.get(StringPool.FIELD_ATTACHMENTS);

		// Example:
		// "attachments":{
		// "foo.txt":{
		// "data":<binary data>,
		// "content_type":"text/plain"
		// }
		// }
		Map<String, Map<String, Object>> attachments = new HashMap<String, Map<String, Object>>();

		if (_attachments != null) {
			// Parse each attachment
			Iterator<String> iterator = _attachments.fieldNames();
			while (iterator.hasNext()) {
				String attachmentId = iterator.next();
				JsonNode _attachment = _attachments.get(attachmentId);

				Map<String, Object> attachment = new HashMap<String, Object>();

				// Content type
				String contentType = _attachment.get(
						StringPool.FIELD_CONTENT_TYPE).textValue();
				attachment.put(StringPool.FIELD_CONTENT_TYPE, contentType);

				// Data
				AttachmentInputStream ais = connector.getAttachment(docId, attachmentId);
				byte[] bytes;
				try {
					bytes = Util.readAll(ais);
					attachment.put(StringPool.FIELD_DATA, bytes);
				} catch (IOException e) {
					System.err.println("Fail to read binary data: docId=" + docId + ", attachment=" + attachmentId);
					e.printStackTrace();
				}
				
				attachments.put(attachmentId, attachment);
			}
		}

		return attachments;
	}
	
	//Return error list
	public List<String> bulkInsert(List<ObjectNode> subList){
		//List<DocumentOperationResult> result = connector.executeAllOrNothing(subList);
		List<DocumentOperationResult> result = new ArrayList<DocumentOperationResult>();
		for(Object o : subList){
			System.out.println(((ObjectNode)o).toString());
			connector.create((ObjectNode)o);
		}
		
		List<String> list = new ArrayList<String>();
		if(result == null || (result != null && result.size() > 0)){ //TODO
			for(DocumentOperationResult r : result){
				list.add(r.getId());
			}
		}
		
		return list;
	}
	
	//docId = entry.getKey()
	public void createAttachment(String docId, String attachmentId, ObjectNode attachment) throws Exception{
		//Binary data
		byte[] data = null;
		data = attachment.get(StringPool.FIELD_DATA).binaryValue();
		InputStream _data = new ByteArrayInputStream(data);

		//Content type
		String contentType = attachment.get(StringPool.FIELD_CONTENT_TYPE).asText();

		//Build attachment input stream
		AttachmentInputStream ais = new AttachmentInputStream(attachmentId, _data, contentType);

		//Get revision to avoid a conflict
		String revision = connector.getCurrentRevision(docId);

		//Create attachement
		connector.createAttachment(docId, revision, ais);
	}
}

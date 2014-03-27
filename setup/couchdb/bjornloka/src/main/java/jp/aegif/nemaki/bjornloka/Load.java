package jp.aegif.nemaki.bjornloka;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.ektorp.AttachmentInputStream;
import org.ektorp.CouchDbConnector;
import org.ektorp.CouchDbInstance;
import org.ektorp.DocumentOperationResult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Load {

	public static void main(String[]args){
		if(args.length < 3){
			System.err.println("Wrong number of arguments: host, port, repositoryId, filePath, force");
			return;
		}

		//host
		String host = args[0];

		//port
		String _port = args[1];
		int port = 0;
		try{
			port = Integer.parseInt(_port);
		}catch(Exception e){
			System.err.println("Port must be integer: " + _port);
			return;
		}

		//repositoryId
		String repositoryId = args[2];

		//filePath
		String filePath = args[3];
		File file = new File(filePath);

		//force(optional)
		boolean force = false;
		try{
			String _force = args[4];
			force = StringPool.BOOLEAN_TRUE.equals(_force);
		}catch(Exception e){

		}

		//Execute loading
		boolean success = load(host, port, repositoryId, file, force);
		if(success){
			System.out.println(repositoryId + ":Data imported successfully");
		}else{
			System.err.println(repositoryId + ":Data import failed");
		}

	}

	public static boolean load(String host, int port, String repositoryId, File file, boolean force){
		CouchDbInstance dbInstance = CouchFactory.createCouchDbInstance(host, port);
		//Initialize database
		if(dbInstance.checkIfDbExists(repositoryId)){
			if(!force){
				System.err.println("Repository already exist. Do nothing.");
				return false;
			}
			dbInstance.deleteDatabase(repositoryId);
		}
		dbInstance.createDatabase(repositoryId);

		//Get connector
		CouchDbConnector connector = CouchFactory.createCouchDbConnector(host, port, repositoryId);

		//CouchDB documents(without attachments)
		List<JsonNode> documents = new ArrayList<JsonNode>();
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
		List<DocumentOperationResult> documentsResult = connector.executeAllOrNothing(documents);
		if(documentsResult != null && documentsResult.size() > 0){
			System.err.println("Documents are not imported because of some error.");
			return false;
		}

		//Load all attachments to DB
		for(Entry<String, ObjectNode> entry : payloads.entrySet()){
			ObjectNode attachments = entry.getValue();
			Iterator<String> it = attachments.fieldNames();
			while(it.hasNext()){
				String attachmentId = it.next();
				JsonNode attachment = attachments.get(attachmentId);

				//Binary data
				byte[] data = null;
				try {
					data = attachment.get(StringPool.FIELD_DATA).binaryValue();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					continue;
				}
				InputStream _data = new ByteArrayInputStream(data);

				//Content type
				String contentType = attachment.get(StringPool.FIELD_CONTENT_TYPE).textValue();

				//Build attachment input stream
				AttachmentInputStream ais = new AttachmentInputStream(attachmentId, _data, contentType);

				//Get revision to avoid a conflict
				String revision = connector.getCurrentRevision(entry.getKey());

				//Create attachement
				try{
					connector.createAttachment(entry.getKey(), revision, ais);
				}catch(Exception e){
					e.printStackTrace();
					return false;
				}

			}
		}

		return true;
	}

	private static void processDocument(ObjectNode document){
		document.remove("_rev");
		document.remove("_attachments");
	}
}

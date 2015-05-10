package jp.aegif.nemaki.bjornloka;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.bjornloka.model.Entry;
import jp.aegif.nemaki.bjornloka.util.Indicator;

import org.ektorp.AttachmentInputStream;
import org.ektorp.CouchDbConnector;
import org.ektorp.ViewQuery;
import org.ektorp.ViewResult;
import org.ektorp.ViewResult.Row;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Dump {
	public static void main(String[] args) {
		if (args.length < 4) {
			System.err
					.println("Wrong number of arguments: host, port, repositoryId, filePath, omitTimestamp");
			return;
		}

		// host
		String host = args[0];

		// port
		String _port = args[1];
		int port = 0;
		try {
			port = Integer.parseInt(_port);
		} catch (Exception e) {
			System.err.println("Port must be integer");
			return;
		}

		// repositoryId
		String repositoryId = args[2];

		// filePath
		String filePath = args[3];
		File file = new File(filePath);

		// omitTimestamp(optional)
		boolean omitTimestamp = false;
		try {
			String _omitTimestamp = args[4];
			omitTimestamp = StringPool.BOOLEAN_TRUE.equals(_omitTimestamp);
		} catch (Exception e) {
			// do nothing
		}
		
		if (!omitTimestamp) {
			String timestamp = getCurrentDateString();
			String newFilePath = file.getAbsolutePath() + "_" + timestamp;
			file = null;
			file = new File(newFilePath);
		}

		// Execute dumping
		try {
			String createdFilePath = dump(host, port, repositoryId, file, omitTimestamp);
			System.out.println("Dump successfully: " + createdFilePath);
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("Dump failed");
		}
	}

	public static String dump(String host, int port, String repositoryId,
			File file, boolean omitTimestamp) throws JsonParseException,
			JsonMappingException, IOException {
		CouchDbConnector connector = CouchFactory.createCouchDbConnector(host,
				port, repositoryId);
		
		List<String> docIds = connector.getAllDocIds();

		//TEST
		try {
			file.createNewFile();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		Indicator indicator = new Indicator(docIds.size());

		int unit = 500;
		int turn = docIds.size() / unit;
		System.out.println("Writing to " + file.getAbsolutePath() + " ...");
		for(int i=0; i <= turn ; i++){
			int toIndex = (unit*(i+1) > docIds.size()) ? docIds.size() : unit*(i+1);

			List<String> keys = docIds.subList(i*unit, toIndex);
			ViewQuery query = new ViewQuery().allDocs().includeDocs(true).keys(keys);
			List<ObjectNode> results = connector.queryView(query, ObjectNode.class);
			
			List<Entry> entries = new ArrayList<Entry>();
			for(ObjectNode document : results){
				Entry entry = new Entry();
				entry.setDocument(document);
				entry.setAttachments(getAttachments(connector, document));
				entries.add(entry);
				indicator.indicate();
			}
			new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new FileOutputStream(file, true), entries);
		}
		
		return file.getAbsolutePath();
	}

	private static Map<String, Map<String, Object>> getAttachments(
			CouchDbConnector connector, JsonNode o) throws IOException {
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
				AttachmentInputStream ais = connector.getAttachment(docId,
						attachmentId);
				Object bytes = readAll(ais);
				attachment.put(StringPool.FIELD_DATA, bytes);
				attachments.put(attachmentId, attachment);
			}
		}

		return attachments;
	}

	private static byte[] readAll(InputStream inputStream) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		byte[] buffer = new byte[1024];
		while (true) {
			int len = inputStream.read(buffer);
			if (len < 0) {
				break;
			}
			bout.write(buffer, 0, len);
		}

		byte[] result = bout.toByteArray();
		bout.close();
		return result;
	}

	private static String getCurrentDateString() {
		Date date = new Date();
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
		return sdf.format(date);
	}
}
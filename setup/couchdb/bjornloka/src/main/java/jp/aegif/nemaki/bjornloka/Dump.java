package jp.aegif.nemaki.bjornloka;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jp.aegif.nemaki.bjornloka.dump.DumpAction;
import jp.aegif.nemaki.bjornloka.model.Entry;
import jp.aegif.nemaki.bjornloka.proxy.CloudantFactory;
import jp.aegif.nemaki.bjornloka.proxy.CloudantProxy;
import jp.aegif.nemaki.bjornloka.proxy.CouchProxy;
import jp.aegif.nemaki.bjornloka.proxy.EktorpFactory;
import jp.aegif.nemaki.bjornloka.proxy.EktorpProxy;
import jp.aegif.nemaki.bjornloka.util.Indicator;
import jp.aegif.nemaki.bjornloka.util.Util;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Dump {
	public static void main(String[] args) {
		if (args.length < 3) {
			System.err
					.println("Wrong number of arguments: url, repositoryId, filePath, omitTimestamp");
			return;
		}

		// url
		String url = args[0];

		// repositoryId
		String repositoryId = args[1];

		// filePath
		String filePath = args[2];
		File file = new File(filePath);

		// omitTimestamp(optional)
		boolean omitTimestamp = false;
		try {
			String _omitTimestamp = args[3];
			omitTimestamp = StringPool.BOOLEAN_TRUE.equals(_omitTimestamp);
		} catch (Exception e) {
			// do nothing
		}
		
		if (!omitTimestamp) {
			String timestamp = Util.getCurrentDateString();
			String newFilePath = file.getAbsolutePath() + "_" + timestamp;
			file = null;
			file = new File(newFilePath);
		}

		// Execute dumping
		try {
			DumpAction dumpAction = DumpAction.getInstance(url, repositoryId, file, omitTimestamp);
			String createdFilePath = dumpAction.dump();;
			System.out.println("Dump successfully: " + createdFilePath);
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("Dump failed");
		}
	}
	
	public static String dump(CouchProxy client,
			File file, boolean omitTimestamp){
		List<String> docIds = client.getAllDocIds();
		System.out.println("alldoc keys:" + docIds.toString());

		try {
			file.createNewFile();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		Indicator indicator = new Indicator(docIds.size());

		int unit = 500;
		int turn = docIds.size() / unit;
		System.out.println("Writing to " + file.getAbsolutePath() + " ...");
		for(int i=0; i <= turn ; i++){
			int toIndex = (unit*(i+1) > docIds.size()) ? docIds.size() : unit*(i+1);

			List<String> keys = docIds.subList(i*unit, toIndex);
			System.out.println("subsystem keys:" + keys.toString());
			List<ObjectNode> results = client.getDocs(keys);
			
			List<Entry> entries = new ArrayList<Entry>();
			for(ObjectNode document : results){
				Entry entry = new Entry();
				entry.setDocument(document);
				entry.setAttachments(client.getAttachments(document));
				entries.add(entry);
				indicator.indicate();
			}
			try {
				new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new FileOutputStream(file, true), entries);
			} catch (JsonGenerationException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (JsonMappingException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		return file.getAbsolutePath();
		
	}
	
	public static String dumpEktorp(String url, String repositoryId,
			File file, boolean omitTimestamp) throws JsonParseException,
			JsonMappingException, IOException {
		EktorpProxy proxy = EktorpFactory.getInstance().createProxy(url, repositoryId);
		return dump(proxy, file, omitTimestamp);
	}

	//TODO implement
	public static String dumpCloudant(String url, String repositoryId,
			File file, boolean omitTimestamp){
		CloudantProxy proxy = CloudantFactory.getInstance().createProxy(url, repositoryId);
		return dump(proxy, file, omitTimestamp);
	}
	
}
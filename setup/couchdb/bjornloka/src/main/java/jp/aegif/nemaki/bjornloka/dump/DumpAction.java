package jp.aegif.nemaki.bjornloka.dump;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.aegif.nemaki.bjornloka.model.Entry;
import jp.aegif.nemaki.bjornloka.proxy.CouchProxy;
import jp.aegif.nemaki.bjornloka.util.Indicator;
import jp.aegif.nemaki.bjornloka.util.Util;

public abstract class DumpAction {
	
	protected String url;
	String repositoryId;
	protected File file;
	protected boolean omitTimestamp;
	
	public DumpAction(String url, String repositoryId, File file, boolean omitTimestamp) {
		super();
		this.url = Util.sanitizeUrl(url);
		this.repositoryId = repositoryId;
		this.file = file;
		this.omitTimestamp = omitTimestamp;
	}
	
	public static DumpAction getInstance (String url, String repositoryId, File file, boolean omitTimestamp){
		switch(Util.checkProxyType(url)){
		case EKTORP: 
			return new DumpEktorp(url, repositoryId, file, omitTimestamp);
		case CLOUDANT:
			return new DumpCloudant(url, repositoryId, file, omitTimestamp);
		}
		return null;
	}

	public abstract String dump();
	
	public static String action(CouchProxy client,
			File file, boolean omitTimestamp){
		List<String> docIds = client.getAllDocIds();
		System.out.println("alldoc keys:" + docIds.toString());

		try {
			file.createNewFile();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		Indicator indicator = new Indicator(docIds.size());

		int unit = 5000;
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
}

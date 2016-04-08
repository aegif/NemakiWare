package util;

import java.util.HashMap;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.Session;

public class CmisObjectTree {

	private	HashMap<String, CmisObject> map = new HashMap<String, CmisObject>();
	 private Session _session;
	 private long size = 0;

	public CmisObjectTree(Session session){
		_session = session;
	}

	private void buildTree(String parentPath, String id){
		CmisObject obj = _session.getObject(id);
		if( Util.isDocument(obj)){
			Document doc = (Document)obj;
			size = size + doc.getContentStreamLength();
			String path = parentPath  + doc.getName();
			map.put(path, doc);
		}else if(Util.isFolder(obj)){
			Folder folder = (Folder)obj;
			String path = parentPath  + folder.getName() + "/";
			map.put(path, folder);
			for(CmisObject child :  folder.getChildren()){
				buildTree(path,  child.getId());
			}
		}
	}

	public void buildTree(String[] ids){
		size = 0;
		for(String id : ids){
			buildTree("/", id);
		}
	}

	public long getContentsSize(){
		return size;
	}

	public HashMap<String, CmisObject> getHashMap(){
		return map;
	}

}

package jp.aegif.nemaki.service.impl;

import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.db.CouchConnector;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.NemakiAttachment;
import jp.aegif.nemaki.service.DaoService;

import org.ektorp.Attachment;
import org.ektorp.AttachmentInputStream;
import org.ektorp.CouchDbConnector;
import org.ektorp.changes.ChangesCommand;
import org.ektorp.changes.DocumentChange;

public class CouchDaoServiceImpl implements DaoService{

	private CouchDbConnector connector;
	
	
	public CouchDaoServiceImpl(){
		CouchConnector couchConnector = new CouchConnector();
		this.connector = couchConnector.getConnection();
	}
	
	public Content getContent(String id){
		try{
			Content content = connector.get(Content.class, id);
			return content;
		}catch(Exception e){
			return null;
		}
	}
	
	public List<String> getAllDocIds(){
		return connector.getAllDocIds();
	}
	
	public NemakiAttachment getAttachment(String id){
		try{
			NemakiAttachment attachment = connector.get(NemakiAttachment.class, id);
			return attachment;
		}catch(Exception e){
			e.printStackTrace();
			return null;
		}
	}
	
	//FIXME Remove this method which is supposed to be for multiple attachments
	public NemakiAttachment getLatestAttachment(String id){
		Content content = getContent(id);
		String attachmentNodeId = content.getAttachmentNodeId();
		if(attachmentNodeId == null) return null;
		String latest = attachmentNodeId;
		return getAttachment(latest);
	}
	
	public List<DocumentChange> getChangeLog(long since){
		ChangesCommand cmd = new ChangesCommand.Builder().since(since).build();
		return connector.changes(cmd);
	}
	
	public  AttachmentInputStream getInlineAttachment(String id){
		
		NemakiAttachment attachment = getAttachment(id);
		
		//inline attachment のname取得(1つしかない想定)
		Map<String,Attachment> map = attachment.getAttachments();
		java.util.Iterator<String> iterator =  map.keySet().iterator();
		String key = null;
		while(iterator.hasNext()){	//TODO 見つからなかった場合の処理
			key = iterator.next();
		}
		
		AttachmentInputStream stream = connector.getAttachment(id, key);
		return stream;
	}
	
	public boolean contentExist(String id){
		Content content = getContent(id);
		if (content == null){
			return false;
		}
		return true;
	}
	
}

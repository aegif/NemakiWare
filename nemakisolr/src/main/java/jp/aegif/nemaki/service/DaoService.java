package jp.aegif.nemaki.service;

import java.util.List;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.NemakiAttachment;

import org.ektorp.AttachmentInputStream;
import org.ektorp.changes.DocumentChange;

public interface DaoService {

	public Content getContent(String id);
	
	public NemakiAttachment getAttachment(String id);
	
	public NemakiAttachment getLatestAttachment(String id);
	
	public List<DocumentChange> getChangeLog(long since);
	
	public AttachmentInputStream getInlineAttachment(String id);
	
	public List<String> getAllDocIds();
	
	public boolean contentExist(String id);
	
}

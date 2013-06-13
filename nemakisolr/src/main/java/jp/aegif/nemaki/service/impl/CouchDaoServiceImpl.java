/*******************************************************************************
 * Copyright (c) 2013 aegif.
 * 
 * This file is part of NemakiWare.
 * 
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
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

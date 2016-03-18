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
package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.common.ErrorCode;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.util.constant.NodeType;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

@Path("/repo/{repositoryId}/archive")
public class ArchiveResource extends ResourceBase {
	
	private static final Log log = LogFactory
            .getLog(ArchiveResource.class);

	private ContentService contentService;

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	//FIXME Attachment should always be got out of the output on this layer
	@SuppressWarnings("unchecked")
	@GET
	@Path("/index")
	@Produces(MediaType.APPLICATION_JSON)
	public String index(@PathParam("repositoryId") String repositoryId){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray list = new JSONArray();
		JSONArray errMsg = new JSONArray();

		try{
			List<Archive> archives = contentService.getAllArchives(repositoryId);
			for(Archive a : archives){
				//Filter out Attachment & old Versions
				if (NodeType.ATTACHMENT.value().equals(a.getType())){
					continue;
				}else if (NodeType.CMIS_DOCUMENT.value().equals(a.getType())){
					boolean ilv = (a.isLatestVersion() != null) ? a.isLatestVersion() : false;
					if (!ilv) continue;
				}

				JSONObject o = buildArchiveJson(a.getId(), a.getType(), a.getName(), a.getParentId(), a.isDeletedWithParent(), a.getCreated(), a.getCreator());

				if(a.isDocument()){
					o.put("mimeType", a.getMimeType());
				}

				list.add(o);
			}
			result.put("archives", list);
		}catch(Exception e){
			e.printStackTrace();
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_GET_ARCHIVES);
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@PUT
	@Path("/restore/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String restore(@PathParam("repositoryId") String repositoryId, @PathParam("id") String id){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try{
			contentService.restoreArchive(repositoryId, id);
		}catch(Exception e){
			log.error(e, e);
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_RESTORE);
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}


	@SuppressWarnings("unchecked")
	private JSONObject buildArchiveJson(String objectId, String type, String name, String parentId, Boolean deletedWithParent, GregorianCalendar created, String creator){
		JSONObject archiveJson = new JSONObject();
		archiveJson.put("id", objectId);
		archiveJson.put("type", type);
		archiveJson.put("name", name);
		archiveJson.put("parentId", parentId);
		archiveJson.put("isDeletedWithParent", deletedWithParent);
		SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
		archiveJson.put("created", sdf.format(created.getTime()));
		archiveJson.put("creator", creator);
		return archiveJson;
	}
}

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
import java.util.List;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.exception.ParentNoLongerExistException;
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
	public String index(
			@PathParam("repositoryId") String repositoryId, 
			@QueryParam("skip") Integer skip, 
			@QueryParam("limit") Integer limit, 
			@QueryParam("desc") Boolean desc){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray list = new JSONArray();
		JSONArray errMsg = new JSONArray();
		
		try{
			List<Archive> archives = contentService.getArchives(repositoryId, skip, limit, desc);
			for(Archive a : archives){
				//Filter out Attachment & old Versions
				if (NodeType.ATTACHMENT.value().equals(a.getType())){
					continue;
				}else if (NodeType.CMIS_DOCUMENT.value().equals(a.getType())){
					boolean ilv = (a.isLatestVersion() != null) ? a.isLatestVersion() : false;
					if (!ilv) continue;
				}

				JSONObject o = buildArchiveJson(a);

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
		}catch(ParentNoLongerExistException e){
			log.error(e, e);
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_RESTORE_BECAUSE_PARENT_NO_LONGER_EXISTS);
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}
	
	@DELETE
	@Path("/destroy/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String destroy(@PathParam("repositoryId") String repositoryId, @PathParam("id") String id){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try{
			contentService.destroyArchive(repositoryId, id);
		}catch(Exception e){
			log.error(e, e);
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_DESTROY);
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@SuppressWarnings({ "unchecked" })
	private JSONObject buildArchiveJson(Archive archive){
		
		JSONObject archiveJson = new JSONObject();
		archiveJson.put("id", archive.getId());
		archiveJson.put("type", archive.getType());
		archiveJson.put("name", archive.getName());
		archiveJson.put("originalId", archive.getOriginalId());
		archiveJson.put("parentId", archive.getParentId());
		archiveJson.put("isDeletedWithParent", archive.isDeletedWithParent());
		try{
			String _created = new SimpleDateFormat(DATE_FORMAT).format(archive.getCreated().getTime());
			archiveJson.put("created", _created);
		}catch(Exception e){
			log.warn(String.format("Archive(%s) 'created' property is broken.", archive.getId()));
		}
		archiveJson.put("creator", archive.getCreator());
		return archiveJson;
	}
}

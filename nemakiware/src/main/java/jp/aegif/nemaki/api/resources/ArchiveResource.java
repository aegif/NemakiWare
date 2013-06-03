package jp.aegif.nemaki.api.resources;

import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.constant.NodeType;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

@Path("/archive")
public class ArchiveResource extends ResourceBase {
	
	//FIXME Attachment should always be got out of the output on this layer
	@SuppressWarnings("unchecked")
	@GET
	@Path("/index")
	@Produces(MediaType.APPLICATION_JSON)
	public String index(){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray list = new JSONArray();
		JSONArray errMsg = new JSONArray();
		
		try{
			List<Archive> archives = contentService.getAllArchives();
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
			addErrMsg(errMsg, ITEM_ARCHIVE, ERR_GET_ARCHIVES);
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}
	
	@PUT
	@Path("/restore/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String restore(@PathParam("id") String id){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		
		try{
			contentService.restoreArchive(id);
		}catch(Exception e){
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ERR_RESTORE);
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

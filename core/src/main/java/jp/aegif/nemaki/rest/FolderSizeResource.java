package jp.aegif.nemaki.rest;

import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocumentList;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;


@Component
@Path("/repo/{repositoryId}/foldersize")
public class FolderSizeResource extends ResourceBase {

	private SolrUtil solrUtil;

	public void setSolrUtil(SolrUtil solrUtil) {
		this.solrUtil = solrUtil;
	}

	@SuppressWarnings("unchecked")
	@GET
	@Path("/calc")
	@Produces(MediaType.APPLICATION_JSON)
	public String getFileSize(
			@PathParam("repositoryId") String repositoryId,
			@QueryParam("path") String folderPath,
			@QueryParam("includeSelf") String includeSelf) {

		boolean isIncludeSelf = true;
		if ( includeSelf != null ) {
			isIncludeSelf = Boolean.parseBoolean(includeSelf);
		}

		if (folderPath.endsWith("/")) {
			folderPath = folderPath.substring(0, folderPath.length() - 1);
		}

		SolrServer solrServer = this.solrUtil.getSolrServer();

		try {
			FolderInfo rootFolderInfo = this.getFolderInfo(repositoryId, folderPath, solrServer);
			if ( rootFolderInfo == null ) {
				JSONObject resultJson = new JSONObject();
				JSONArray errMsg = new JSONArray();
				this.addErrMsg(errMsg, ITEM_PATH, "folder not found");
				this.makeResult(false, resultJson, errMsg);
				return resultJson.toJSONString();
			}

			if ( isIncludeSelf ) {

				JSONObject folderJson = calcSizeUnderFolderPath(repositoryId, rootFolderInfo, solrServer);

				//build result json
				JSONObject foldersJson = new JSONObject();
				JSONArray foldersArray = new JSONArray();
				foldersArray.add(folderJson);
				foldersJson.put("folders", foldersArray);
				this.makeResult(true, foldersJson, new JSONArray());

				return foldersJson.toJSONString();
			}
			else {
				//calc from subFolder
				SolrQuery query = new SolrQuery();			
				query.setQuery("repository_id:" + ClientUtils.escapeQueryChars(repositoryId) +
						" AND path:" + ClientUtils.escapeQueryChars(folderPath) +"\\/*");

				QueryResponse response = solrServer.query(query);					
				SolrDocumentList result = response.getResults();

				List<FolderInfo> folderInfos = new ArrayList<FolderInfo>();

				for(int i = 0 ; i < result.size(); i++) {
					String baseType = (String)result.get(i).getFieldValue("basetype");
					if (baseType.equals("cmis:folder")) {
						FolderInfo fi = new FolderInfo();
						fi.name = (String)result.get(i).getFieldValue("name");
						fi.path = (String)result.get(i).getFieldValue("path");
						fi.objectId = (String)result.get(i).getFieldValue("object_id");
						folderInfos.add(fi);
					}					
				}

				JSONObject foldersJson = new JSONObject();
				JSONArray foldersArray = new JSONArray();

				//calc size for each paths
				for(FolderInfo fi : folderInfos) {				
					JSONObject folderJson = calcSizeUnderFolderPath(repositoryId, fi, solrServer);
					foldersArray.add(folderJson);
				}
				foldersJson.put("folders", foldersArray);
				this.makeResult(true, foldersJson, new JSONArray());

				return foldersJson.toJSONString();
			}
		}
		catch(Exception ex) {
			JSONObject resultJson = new JSONObject();
			JSONArray errMsg = new JSONArray();
			this.addErrMsg(errMsg, ITEM_PATH, ex.getMessage());
			ex.printStackTrace();
			this.makeResult(false, resultJson, errMsg);
			return resultJson.toJSONString();
		}
	}

	private FolderInfo getFolderInfo(String repositoryId, String folderPath, SolrServer solrServer) 
			throws SolrServerException {
		SolrQuery query = new SolrQuery();			
		query.setQuery("repository_id:" + ClientUtils.escapeQueryChars(repositoryId) +
				" AND path:" + ClientUtils.escapeQueryChars(folderPath));

		QueryResponse response = solrServer.query(query);					
		SolrDocumentList result = response.getResults();

		for(int i = 0 ; i < result.size(); i++) {
			String baseType = (String)result.get(i).getFieldValue("basetype");
			if (baseType.equals("cmis:folder")) {
				FolderInfo fi = new FolderInfo();
				fi.name = (String)result.get(i).getFieldValue("name");
				fi.path = (String)result.get(i).getFieldValue("path");
				fi.objectId = (String)result.get(i).getFieldValue("object_id");
				return fi;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private JSONObject calcSizeUnderFolderPath(String repositoryId, FolderInfo folderInfo, SolrServer solrServer) 
			throws SolrServerException {

		if (folderInfo.path.endsWith("/")) {
			folderInfo.path = folderInfo.path.substring(0, folderInfo.path.length() - 1);
		}

		long size = 0;
		SolrQuery query = new SolrQuery();			
		query.setQuery("repository_id:" + ClientUtils.escapeQueryChars(repositoryId) +
				" AND path:" + ClientUtils.escapeQueryChars(folderInfo.path) +"\\/*");

		QueryResponse response = solrServer.query(query);					
		SolrDocumentList result = response.getResults();
		List<String> parentIdList = new ArrayList<String>();

		for(int i = 0 ; i < result.size(); i++) {
			String baseType = (String)result.get(i).getFieldValue("basetype");
			if (baseType.equals("cmis:folder")) {
				parentIdList.add((String) result.get(i).getFieldValue("object_id"));
			}
		}
		//search document under given parent_ids
		parentIdList.add(folderInfo.objectId);
		size+= this.calcDocumentUnderParentIds(repositoryId, parentIdList, solrServer);

		//build result json
		JSONObject folderJson = new JSONObject();
		folderJson.put("name", folderInfo.name);
		folderJson.put("path", folderInfo.path);
		folderJson.put("id", folderInfo.objectId);
		folderJson.put("size", size);
		return folderJson;
	}

	private long calcDocumentUnderParentIds(String repositoryId, 
			List<String> parentIds, SolrServer solrServer) throws SolrServerException {

		long retSize = 0;

		SolrQuery query = new SolrQuery();
		StringBuilder sb = new StringBuilder();

		sb.append("repository_id:" + repositoryId + " AND basetype:cmis\\:document AND parent_id:(");
		for(String parentId : parentIds) {
			sb.append(parentId).append(" ");
		}
		sb.append(")");

		query.setQuery(sb.toString());
		QueryResponse response = solrServer.query(query);
		SolrDocumentList result = response.getResults();

		for(int i = 0 ; i < result.size(); i++ ) {
			Long contentLength = (Long)result.get(i).getFieldValue("content_length");
			retSize+= contentLength.longValue();
		}

		return retSize;

	}

	private static class FolderInfo {
		public String path;
		public String name;
		public String objectId;
	}

}

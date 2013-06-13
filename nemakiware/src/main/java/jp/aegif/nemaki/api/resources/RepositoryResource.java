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
package jp.aegif.nemaki.api.resources;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import jp.aegif.nemaki.util.PropertyManager;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;


@Component
@Path("/db")
public class RepositoryResource extends ResourceBase {
	
	private String host;
	
	public RepositoryResource(){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		String dbHost;
		String dbProtocol;
		String dbPort;
		
		try{
			PropertyManager propertyManager = new PropertyManager(FILEPATH_PROPERTIESFILE);
			dbHost = propertyManager.readValue(PROPERTY_DBHOST);
			dbProtocol = propertyManager.readValue(PROPERTY_DBPROTOCOL);
			dbPort = propertyManager.readValue(PROPERTY_DBPORT);
			this.setHost(dbProtocol + "://" +  dbHost + ":" + dbPort + "/");
		}catch(Exception ex){
			ex.printStackTrace();
			status=false;
			addErrMsg(errMsg, ITEM_COUCHDBRESTURL, ERR_READ);
		}
		
		result = makeResult(status, result, errMsg);
		
		if(!status){
			Logger.getLogger(RepositoryResource.class.getName()).log(Level.SEVERE, result.toJSONString());
		}
	}
	
	/**
	 * List up all databases with detail info of each one
	 * @return
	 */
	@SuppressWarnings("unchecked")
	@GET
	@Path("/list")
	@Produces(MediaType.APPLICATION_JSON)
	public String list(){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		JSONObject detailedJSON = new JSONObject();
		
		Map map = getCouchDbApiResponse(VIEW_ALL, errMsg);
		String response = (String) map.get("response");
		errMsg = (JSONArray) map.get("errMsg");
		
		if(response != null){
			try {
				JSONArray json = (JSONArray) JSONValue.parseWithException(response);

				//Add additional each db info to the JSON
				String dbId = SPACE;
				if (json != null) { 
				   int len = json.size();
				   
				   Map dbInfoMap = new HashMap();
				   JSONObject dbInfo = new JSONObject();
				   for (int i=0;i<len;i++){ 
					   dbId = SPACE;
					   dbId = json.get(i).toString();
					   
					   //call API
					   dbInfoMap = getEachDbInfo(dbId, errMsg);
					   dbInfo = (JSONObject) dbInfoMap.get("json");
					   errMsg = (JSONArray) dbInfoMap.get("errMsg");
					   
					   detailedJSON.put(dbId, dbInfo);
					   result.put(ITEM_DATABASES, detailedJSON);
				   } 
				} 
			} catch (ParseException e) {
				e.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_DATABASES, ERR_PARSEJSON);
			}
		}
		
		//API result output process
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}
	
	
	/**
	 * Create a new couchDB database registering a view document for it and 
	 * add its id to the property file 
	 * @param dbId
	 * @return
	 */
	@POST
	@Path("/create/{dbId}")
	@Produces(MediaType.APPLICATION_JSON)
	public String create(@PathParam("dbId") String dbId){
		
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		
		//Wrap REST API
		String urlString = host + dbId; 
		try {
			URL url = new URL(urlString);
			try {
				HttpURLConnection connection = (HttpURLConnection) url.openConnection ();
				
				connection.setRequestMethod("PUT");
				connection.connect();
				connection.disconnect();
				if( !(connection.getResponseCode() == HttpURLConnection.HTTP_CREATED)){
					status = false;
					addErrMsg(errMsg, ITEM_COUCHDBRESPONSE, "statusCode:" + connection.getResponseCode());
				}
			} catch (IOException e) {
				e.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_DATABASE, ERR_CREATE);
			}
		} catch (MalformedURLException e) {
			e.printStackTrace();
			status = false;
			addErrMsg(errMsg, ITEM_COUCHDBRESTURL, ERR_PARSEURL);
		}
		
		if(status){
			//Register a view to the db 
			try {
				createDesignDoc(dbId, DOCNAME_VIEW);
			} catch (Exception ex) {
				ex.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_VIEW, ERR_CREATE);
			}
		}
		
		if(status){
			//Add dbId to the property file
			try{
				PropertyManager propertyManager = new PropertyManager(FILEPATH_PROPERTIESFILE);
				propertyManager.addValue(PROPERTY_REPOSITORIES, dbId);
				//modify current repository info on the property file
				propertyManager.modifyValue(PROPERTY_INFO_REPOSITORY, dbId);
			}catch(Exception ex){
				ex.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_PROPERTIESFILE, ERR_ADD_REPOSITORY);
			}
		}
		
		//API result output process
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}
	
	/**
	 * Delete a database and remove its id from the property file
	 * @param dbId
	 * @return
	 */
	@DELETE
	@Path("/delete/{dbId}")
	@Produces(MediaType.APPLICATION_JSON)
	public String delete(@PathParam("dbId") String dbId){
		
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		
		//Wrap REST API
		String urlString = host + dbId; 
		try {
			URL url = new URL(urlString);
			try {
				HttpURLConnection connection = (HttpURLConnection) url.openConnection ();
				
				connection.setRequestMethod("DELETE");
				connection.connect();
				connection.disconnect();				
				if(! (connection.getResponseCode() == HttpURLConnection.HTTP_OK)){
					status = false;
					addErrMsg(errMsg, ITEM_COUCHDBRESPONSE, ERR_STATUSCODE + connection.getResponseCode());
				}				
			} catch (IOException e) {
				e.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_DATABASE, ERR_DELETE);
			}
		} catch (MalformedURLException e) {
			e.printStackTrace();
			status = false;
			addErrMsg(errMsg, ITEM_COUCHDBRESTURL, ERR_PARSEURL);
		}
		
		//Remove dbId from properties file
		if(status){
			try{
				PropertyManager propertyManager = new PropertyManager(FILEPATH_PROPERTIESFILE);
				propertyManager.removeValue(PROPERTY_REPOSITORIES, dbId);
				//modify current repository info on the property file
				String headValue = propertyManager.readHeadValue(PROPERTY_REPOSITORIES);
				propertyManager.modifyValue(PROPERTY_INFO_REPOSITORY, headValue);
			}catch(Exception ex){
				ex.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_PROPERTIESFILE, ERR_REMOVE_REPOSITORY);
			}
		}
		
		//API result output process
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}
	
	/**
	 * register a view docment(designdoc) to the specified database
	 * @param dbId
	 * @param docName
	 * @throws Exception
	 */
public void createDesignDoc(String dbId, String docName) throws Exception{
		
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		
		//TODO:daoServiceの使用に切り替える
		
		//Set REST URL connection of couchDB for creating a document
		String urlStr = host + dbId + "/" + docName;
		
		URL url = new URL(urlStr);
		HttpURLConnection connection;
		connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod("PUT");
		connection.setRequestProperty("content-type", "application/json; charset=utf-8");
		connection.setDoOutput(true);
		
		//Read views content(external json string)
		String viewStr = SPACE;
		Resource resource = context.getResource(FILEPATH_VIEW);
		if(resource.exists()){
			InputStream is = resource.getInputStream();
			InputStreamReader isr = new InputStreamReader(is);
			BufferedReader reader = new BufferedReader(isr);
			
			String line = reader.readLine();
			while(line!=null){
					viewStr = viewStr + line;
					line = reader.readLine();
			}
		}
			
		//Create a view designdoc via REST API
		OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());
	    writer.write(viewStr);
	    writer.close();
	
	    if(!(connection.getResponseCode() == HttpURLConnection.HTTP_CREATED)){
	    	throw new Exception();
	    }
	}

	/**
	 * Get simple string result of the HTTP body from couchDB REST API  
	 * @param param: REST parameter added to the host url
	 * @param errMsg 
	 * @return
	 */
	public Map getCouchDbApiResponse(String param, JSONArray errMsg){
		boolean status = true;
		
		String urlString = host + param;
		String response = SPACE;
		
		try {
			URL url = new URL(urlString);
			try {
				HttpURLConnection connection = (HttpURLConnection) url.openConnection ();
				
				connection.setRequestMethod("GET");
				connection.connect();
				
				BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
				while (true){
					String line = reader.readLine();
					if ( line == null ){
						break;
					}
					response = response + line;
				}
				reader.close();

				connection.disconnect();
				
				if(!(connection.getResponseCode() == HttpURLConnection.HTTP_OK)){
					status = false;
					addErrMsg(errMsg, ITEM_COUCHDBRESPONSE, ERR_STATUSCODE + connection.getResponseCode());
				}
				
			} catch (IOException e) {
				e.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_DATABASES, ERR_LIST);
			}
		} catch (MalformedURLException e) {
			e.printStackTrace();
			status = false;
			addErrMsg(errMsg, ITEM_COUCHDBRESTURL, ERR_PARSEURL);
		}
		
		Map map = new HashMap();
		map.put("response", response);
		map.put("errMsg", errMsg);
		return map;
	}
	
	/**
	 * Get detail info of each database
	 * @param dbId
	 * @param errMsg
	 * @return
	 */
	public Map getEachDbInfo(String dbId, JSONArray errMsg){
		boolean status = true;
		JSONObject json = new JSONObject();
		
		Map map = getCouchDbApiResponse(dbId, errMsg);
		String response = (String) map.get("response");
		errMsg = (JSONArray) map.get("errMsg");
		
		try {
			json = (JSONObject) JSONValue.parseWithException(response);
		} catch (ParseException e) {
			e.printStackTrace();
			status = false;
			addErrMsg(errMsg, ITEM_DATABASE + ":" + dbId, ERR_PARSEJSON);
		}
		
		Map dbInfoMap = new HashMap();
		dbInfoMap.put("json", json);
		dbInfoMap.put("errMsg", errMsg);
		return dbInfoMap;
	}
	
	//Setter
	public void setHost(String host) {
		this.host = host;
	}
}

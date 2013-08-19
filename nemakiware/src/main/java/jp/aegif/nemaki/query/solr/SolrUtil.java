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
package jp.aegif.nemaki.query.solr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import jp.aegif.nemaki.util.PropertyManager;

import org.antlr.runtime.tree.Tree;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.commons.lang.StringUtils;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.impl.HttpSolrServer;

/**
 * Common utility class for Solr query 
 * @author linzhixing
 *
 */
public class SolrUtil {

	public static final String FILEPATH_PROPERTIESFILE = "nemakiware.properties";
	public static final String FIELD_SOLRURL = "solr.url";
	
	/**
	 * Get Solr server instance
	 * @return
	 */
	public static SolrServer getSolrServer(){
		String solrUrl = null;
		try {
			PropertyManager propertyManager = new PropertyManager(FILEPATH_PROPERTIESFILE);
			solrUrl = propertyManager.readValue(FIELD_SOLRURL);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return new HttpSolrServer(solrUrl);
	}

	/**
	 * CMIS to Solr property name dictionary
	 * @param cmisColName
	 * @return
	 */
	public static String getPropertyNameInSolr(String cmisColName){
		//Make hashmap with key:CMIS, val:Solr property name
		HashMap<String,String>map = new HashMap<String, String>();
		map.put("cmis:document", "cmis\\:document");
		map.put("cmis:folder", "cmis\\:folder");
		map.put(PropertyIds.OBJECT_ID, "id");
		map.put(PropertyIds.OBJECT_TYPE_ID, "type");
		map.put(PropertyIds.NAME, "name");
		map.put(PropertyIds.PARENT_ID, "parentid");
		map.put(PropertyIds.PATH, "path");
		map.put(PropertyIds.CREATION_DATE, "created");
		map.put(PropertyIds.CREATED_BY, "creator");
		map.put(PropertyIds.LAST_MODIFICATION_DATE, "modified");
		map.put(PropertyIds.LAST_MODIFIED_BY, "modifier");
		map.put(PropertyIds.CONTENT_STREAM_ID, "attachment");
		
		map.put(PropertyIds.IS_IMMUTABLE, "is_imutable");
		map.put(PropertyIds.IS_LATEST_VERSION, "is_latest_version");
		map.put(PropertyIds.IS_MAJOR_VERSION, "is_major_version");
		map.put(PropertyIds.IS_LATEST_MAJOR_VERSION, "is_latest_major_version");
		map.put(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT, "is_version_series_checked_out");
		map.put(PropertyIds.CONTENT_STREAM_FILE_NAME, "filename");
		map.put(PropertyIds.CONTENT_STREAM_LENGTH, "length");
		map.put(PropertyIds.CONTENT_STREAM_MIME_TYPE, "content_type");	//SolrCell default
		
		String val = map.get(cmisColName);
		
		if (val == null){
			val = "dynamic.property." + cmisColName;
		}
		
		return val;
	}
	
	public static String convertToString(Tree propertyNode){
		List<String> _string = new ArrayList<String>();
		for(int i=0; i<propertyNode.getChildCount(); i++){
			_string.add(propertyNode.getChild(i).toString());
		}
		return StringUtils.join(_string, ".");
	}

}

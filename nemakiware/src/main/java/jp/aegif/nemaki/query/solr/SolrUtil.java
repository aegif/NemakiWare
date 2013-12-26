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
	
	private HashMap<String, String>map;
	
	public SolrUtil(){
		map = new HashMap<String, String>();
		map.put(PropertyIds.OBJECT_ID, "id");
		map.put(PropertyIds.BASE_TYPE_ID, "basetype");
		map.put(PropertyIds.OBJECT_TYPE_ID, "objecttype");
		map.put(PropertyIds.NAME, "name");
		map.put(PropertyIds.DESCRIPTION, "cmis_description");
		map.put(PropertyIds.CREATION_DATE, "created");
		map.put(PropertyIds.CREATED_BY, "creator");
		map.put(PropertyIds.LAST_MODIFICATION_DATE, "modified");
		map.put(PropertyIds.LAST_MODIFIED_BY, "modifier");
		map.put(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, "secondary_object_type_ids");
		
		map.put(PropertyIds.IS_MAJOR_VERSION, "is_major_version");
		map.put(PropertyIds.IS_PRIVATE_WORKING_COPY, "is_pwc");
		map.put(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT, "is_checkedout");
		map.put(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID, "checkedout_id");
		map.put(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY, "checkedout_by");
		map.put(PropertyIds.CHECKIN_COMMENT, "checkein_comment");
		map.put(PropertyIds.VERSION_LABEL, "version_label");
		map.put(PropertyIds.VERSION_SERIES_ID, "version_series_id");
		map.put(PropertyIds.CONTENT_STREAM_ID, "content_name");
		map.put(PropertyIds.CONTENT_STREAM_FILE_NAME, "content_id");
		map.put(PropertyIds.CONTENT_STREAM_LENGTH, "content_length");
		map.put(PropertyIds.CONTENT_STREAM_MIME_TYPE, "content_mimetype");	

		map.put(PropertyIds.PARENT_ID, "parent_id");
		map.put(PropertyIds.PATH, "path");
	}
	
	/**
	 * Get Solr server instance
	 * @return
	 */
	public SolrServer getSolrServer(){
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
	public String getPropertyNameInSolr(String cmisColName){
		String val = map.get(cmisColName);
		
		if (val == null){
			val = "dynamic.property." + cmisColName;
		}
		
		return val;
	}
	
	public String convertToString(Tree propertyNode){
		List<String> _string = new ArrayList<String>();
		for(int i=0; i<propertyNode.getChildCount(); i++){
			_string.add(propertyNode.getChild(i).toString());
		}
		return StringUtils.join(_string, ".");
	}
}

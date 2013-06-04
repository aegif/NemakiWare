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
 *     linzhixing - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.db;

import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.impl.PropertyManagerImpl;

import org.ektorp.CouchDbConnector;
import org.ektorp.CouchDbInstance;
import org.ektorp.http.HttpClient;
import org.ektorp.http.StdHttpClient;
import org.ektorp.impl.StdCouchDbInstance;


public class CouchConnector {
	/**
	 * CouchDB connector.
	 */
	private CouchDbConnector connector;

	/**
	 * Host, for instance: 127.0.0.1
	 */
	private String host;

	/**
	 * Repository id, for instance: books
	 */
	
	private String repositoryId;

	/**
	 * Max connections, for instance: 40
	 */
	private int maxConnections;


	
	//Originally this constructor was "init" method called by Spring.
	/**
	 * Initialize this class with host, maxConnections.
	 */
	public CouchConnector() {
		
		//TODO Spring or プロパティファイルに外出し
		PropertyManager manager = new PropertyManagerImpl("nemakisolr.properties");
		host = manager.readValue("couchdb.server.url");
		repositoryId = manager.readValue("couchdb.db.repository");
		maxConnections = Integer.parseInt(manager.readValue("couchdb.maxconnection"));
		
		HttpClient httpClient = new StdHttpClient.Builder().host(host)
				.maxConnections(maxConnections).build();
		CouchDbInstance dbInstance = new StdCouchDbInstance(httpClient);
		
		String repo = "";		
		try{
			repo = repositoryId;
		}catch(Exception ex){
			ex.printStackTrace();
		}
		connector = dbInstance.createConnector(repo, true);
	}

	public CouchDbConnector getConnection() {
		return connector;
	}

	public void setRepositoryId(String repositoryId) {	
		this.repositoryId = repositoryId;
	}
	
	public void setHost(String host) {
		this.host = host;
	}

	public void setMaxConnections(int maxConnections) {
		this.maxConnections = maxConnections;
	}
}

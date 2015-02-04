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
package jp.aegif.nemaki.cmis.factory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.PostConstruct;

import jp.aegif.nemaki.util.NemakiPropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Repository map for Nemaki
 */
public class RepositoryMap {

	private Map<String, Repository> repositories;
	private String mainRepositoryId;
	private NemakiPropertyManager propertyManager;

	private static final Log log = LogFactory
			.getLog(RepositoryMap.class);
	
	@PostConstruct
	public void init() {
		repositories = new HashMap<String, Repository>();
		new HashMap<String, String>();

		//If repositoryId is not specified, return default value;
		try {
			mainRepositoryId = propertyManager.readValue(PropertyKey.CMIS_REPOSITORY_MAIN);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * Adds a repository object.
	 */
	public void addRepository(Repository repository) {
		if ((repository == null) || (repository.getRepositoryInfo().getId() == null)) {
			return;
		}
		repositories.put(repository.getRepositoryInfo().getId(), repository);
	}

	/**
	 * Gets a repository object by id.
	 */
	public Repository getRepository(String repositoryId) {
		Repository repository = repositories.get(repositoryId);
		if (repository == null) {
			//If repositoryId is not specified, return default value
			return repositories.get(mainRepositoryId);
		}
		return repository;
	}

	/**
	 * Returns all repository objects.
	 */
	public Collection<Repository> getRepositories() {
		return repositories.values();
	}

	public void setMainRepositoryId(String mainRepositoryId) {
		this.mainRepositoryId = mainRepositoryId;
	}

	public void setPropertyManager(NemakiPropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
}

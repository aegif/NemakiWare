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
package jp.aegif.nemaki.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;

/**
 * Repository map for Nemaki
 */
public class RepositoryMap {

	private Map<String, NemakiRepository> repositories;

	public RepositoryMap() {
		repositories = new HashMap<String, NemakiRepository>();
		new HashMap<String, String>();
	}

	/**
	 * Adds a repository object.
	 */
	public void addRepository(NemakiRepository repository) {
		if ((repository == null) || (repository.getRepositoryInfo().getId() == null)) {
			return;
		}
		repositories.put(repository.getRepositoryInfo().getId(), repository);
	}

	/**
	 * Gets a repository object by id.
	 */
	public NemakiRepository getRepository(String repositoryId) {
		NemakiRepository repository = repositories.get(repositoryId);
		if (repository == null) {
			throw new CmisObjectNotFoundException("Unknown repository '"
					+ repositoryId + "'!");
		}
		return repository;
	}

	/**
	 * Returns all repository objects.
	 */
	public Collection<NemakiRepository> getRepositories() {
		return repositories.values();
	}
}

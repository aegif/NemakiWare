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
 * You should have received a copy of the GNU General Public Licensealong with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.service.cmis.impl;

import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.service.cmis.AuthenticationService;
import jp.aegif.nemaki.service.node.PrincipalService;

import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Authentication Service implementation.
 */
public class AuthenticationServiceImpl implements AuthenticationService {

	private static final Log log = LogFactory
			.getLog(AuthenticationServiceImpl.class);

	private PrincipalService principalService;

	@Override
	public User login(String userName, String password) {
		User u = principalService.getUserById(userName);
		// succeeded
		if (u != null ) {
			if(passwordMatches(password, u.getPasswordHash()) || principalService.isAnonymous(userName)){
				log.debug("[" + userName + "]Authentication succeeded");
				return u;
			}
		}

		// failed
		log.error("[userName=" + userName + ", password=" + password + "]" + "Authentication failed");
		throw new CmisPermissionDeniedException("[userName=" + userName + "]" + "Authentication failed");
	}

	/**
	 * Check whether a password matches a hash.
	 */
	private boolean passwordMatches(String candidate, String hashed) {
		return BCrypt.checkpw(candidate, hashed);
	}

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}
}

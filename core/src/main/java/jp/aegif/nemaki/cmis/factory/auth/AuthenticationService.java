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
/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.cmis.factory.auth;

import org.apache.chemistry.opencmis.commons.server.CallContext;

import jp.aegif.nemaki.model.UserItem;

/**
 * Authentication Service interface.
 */
public interface AuthenticationService {

	public boolean login(CallContext callContext);
	public boolean loginForNemakiConfDb(CallContext callContext);

	/**
	 * Check if a specific authentication method is allowed for a user.
	 *
	 * @param user The user to check
	 * @param method The authentication method ("password", "cloud", etc.)
	 * @return true if the method is allowed
	 */
	public boolean isAuthMethodAllowed(UserItem user, String method);

	/**
	 * The brute-force throttle, for a door that checks a password ITSELF instead of going through
	 * {@link #login(CallContext)} — the API v1 login, which verified the password and minted a token
	 * with nothing counting the attempts (3.4.1, round-5 review). Same counter and same key as the
	 * Basic-auth path, so a lockout holds across the doors rather than each one having its own
	 * allowance.
	 *
	 * @param request the door's servlet request, for the client address the key is built from; a
	 *        {@code null} request keys as {@code "unknown"} (one bucket for every caller), which is
	 *        why a door that has a request must pass it.
	 */
	public boolean isPasswordGuessingBlocked(String repositoryId, String username,
			jakarta.servlet.http.HttpServletRequest request);

	/** Count a wrong password at such a door. */
	public void recordPasswordFailure(String repositoryId, String username,
			jakarta.servlet.http.HttpServletRequest request);

	/** Clear the count after a password that was right AND accepted by the account's policy. */
	public void recordPasswordSuccess(String repositoryId, String username,
			jakarta.servlet.http.HttpServletRequest request);
}

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
package jp.aegif.nemaki.cmis.factory.auth.impl;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.factory.auth.AuthenticationService;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.model.User;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.lang.StringUtils;
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
     private TokenService tokenService;
    
     @Override
     public boolean authenticateUserByToken(String userName, String token){
          String registeredToken = tokenService.getToken(userName);
          return StringUtils.isNotEmpty(registeredToken) && registeredToken.equals(token);
     }

     @Override
     public boolean authenticateAdminByToken(String userName) {
          return tokenService.isAdmin(userName);
     }

     @Override
     public User getAuthenticatedUser(String userName, String password) {
          User u = principalService.getUserById(userName);
         
          // succeeded
          if (u != null ) {
               if(passwordMatches(password, u.getPasswordHash())){
                    log.debug("[" + userName + "]Authentication succeeded");
                    return u;
               }
          }

          // Check anonymous
          String anonymousId = principalService.getAnonymous();
          if(StringUtils.isNotBlank(anonymousId) && anonymousId.equals(userName)){
               if(u != null){
                    log.warn(anonymousId + " should have not been registered in the database.");
               }
               return null;
          }

          return null;
     }

     public String registerToken(CallContext callContext){
          return tokenService.setToken(callContext.getUsername());
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

     public void setTokenService(TokenService tokenService) {
          this.tokenService = tokenService;
     }
}
package jp.aegif.nemaki.repository.factory.impl;

import java.math.BigInteger;
import java.util.Map;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.repository.NemakiRepository;
import jp.aegif.nemaki.repository.RepositoryMap;
import jp.aegif.nemaki.repository.factory.NemakiCmisService;
import jp.aegif.nemaki.service.cmis.AuthenticationService;
import jp.aegif.nemaki.service.cmis.impl.AuthenticationServiceImpl;
import jp.aegif.nemaki.util.constant.NemakiConstant;

import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.impl.server.AbstractServiceFactory;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.chemistry.opencmis.server.support.CmisServiceWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Service factory class, specified in repository.properties.
 */
public class NemakiCmisServiceFactoryImpl extends AbstractServiceFactory implements CmisServiceFactory{

     private static final BigInteger DEFAULT_MAX_ITEMS_TYPES = BigInteger
               .valueOf(50);
     private static final BigInteger DEFAULT_DEPTH_TYPES = BigInteger
               .valueOf(-1);
     private static final BigInteger DEFAULT_MAX_ITEMS_OBJECTS = BigInteger
               .valueOf(200);
     private static final BigInteger DEFAULT_DEPTH_OBJECTS = BigInteger
               .valueOf(10);

     private static final Log log = LogFactory
               .getLog(AuthenticationServiceImpl.class);
     private NemakiRepository nemakiRepository;
     private AuthenticationService authenticationService;
    
     public NemakiCmisServiceFactoryImpl(){
          super();
     }

     /**
      * Repository reference to all repositories.
      */
     private RepositoryMap repositoryMap;

     /**
      * One CMIS service per thread.
      */
     private final ThreadLocal<CmisServiceWrapper<NemakiCmisService>> threadLocalService = new ThreadLocal<CmisServiceWrapper<NemakiCmisService>>();

     /**
      * Add NemakiRepository into repository map at first.
      */
     @Override
     public void init(Map<String, String> parameters) {
          repositoryMap.addRepository(nemakiRepository);
     }

     @Override
     public CmisService getService(CallContext callContext) {
         
          CmisServiceWrapper<NemakiCmisService> wrapperService = threadLocalService
                    .get();
          if (wrapperService == null) {
               wrapperService = new CmisServiceWrapper<NemakiCmisService>(
                         new NemakiCmisService(repositoryMap),
                         DEFAULT_MAX_ITEMS_TYPES, DEFAULT_DEPTH_TYPES,
                         DEFAULT_MAX_ITEMS_OBJECTS, DEFAULT_DEPTH_OBJECTS);
               threadLocalService.set(wrapperService);
          }

          wrapperService.getWrappedService().setCallContext(callContext);
         
          boolean auth = login(callContext);
          if(auth){
              log.info("[userName=" + callContext.getUsername() + "]" + "Authentication succeeded");
              return wrapperService;
          }else{
               throw new CmisPermissionDeniedException("[userName=" + callContext.getUsername() + "]" + "Authentication failed");
          }
     }

     private boolean login(CallContext callContext){
          boolean tokenAuth = loginWithToken(callContext);
         
          //Cookie token Authentication
          if(tokenAuth) {
               return true;
          }
         
          //Basic Authentication
          boolean basicAuth = loginWithBasicAuth(callContext);
          if(basicAuth){
               setTokenCookie(callContext);
               return true;
          }

          return false;
     }
    
     private boolean loginWithToken(CallContext callContext){
          HttpServletRequest req = (HttpServletRequest) callContext.get("httpServletRequest");
          Cookie[] cookies = req.getCookies();
          if(cookies != null){
               for(Cookie cookie : req.getCookies()){
                    if((NemakiConstant.AUTH_TOKEN_PREFIX + callContext.getUsername()).equals(cookie.getName())){
                         //Token based auth
                         String token = cookie.getValue();
                         String userName = callContext.getUsername();
                         if(authenticationService.authenticateUserByToken(userName, token)){
                              if(authenticationService.authenticateAdminByToken(userName)){
                                   setAdminFlagInContext(callContext, true);
                              }else{
                                   setAdminFlagInContext(callContext, false);
                              }
                              return true;
                         }
                    }
               }
          }
         
          log.info("[userName=" + callContext.getUsername() + "]" + "has no basic authentication token");
          return false;
     }
    
     private boolean loginWithBasicAuth(CallContext callContext){
          //Basic auth with id/password
          User user = authenticationService.getAuthenticatedUser(callContext.getUsername(), callContext.getPassword());
          if(user == null) return false;
          boolean isAdmin = user.isAdmin() == null ? false : true;
          if(user == null){
               return false;
          }else{
               setAdminFlagInContext(callContext, isAdmin);
               return true;
          }
     }

     private void setAdminFlagInContext(CallContext callContext, Boolean isAdmin){
          ((CallContextImpl)callContext).put(NemakiConstant.CALL_CONTEXT_IS_ADMIN, isAdmin);
     }
    
     private void setTokenCookie(CallContext callContext){
          String token = authenticationService.registerToken(callContext);
          Cookie cookie = new Cookie(NemakiConstant.AUTH_TOKEN_PREFIX + callContext.getUsername(), token);
          cookie.setMaxAge(60 * 60 * 12);

          HttpServletResponse response = (HttpServletResponse)callContext.get("httpServletResponse");
          response.addCookie(cookie);
     }

     @Override
     public void destroy() {
          super.destroy();
     }

     public void setAuthenticationService(AuthenticationService authenticationService) {
          this.authenticationService = authenticationService;
     }

     public void setNemakiRepository(NemakiRepository nemakiRepository) {
          this.nemakiRepository = nemakiRepository;
     }

     public void setRepositoryMap(RepositoryMap repositoryMap) {
          this.repositoryMap = repositoryMap;
     }

}
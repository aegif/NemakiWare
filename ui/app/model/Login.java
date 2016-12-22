package model;

import util.Util;

import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUnauthorizedException;

import play.Logger;
import play.Logger.ALogger;
import play.data.validation.*;
import play.i18n.Messages;

public  class Login {
	private static final ALogger logger = Logger.of(Login.class);

	 @Constraints.Required
	public String userId;

	public	String password;

	public String repositoryId;


    public String getUserId() {
        return userId;
    }

    public void setId(String userId) {
        this.userId = userId;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }


	public  String validate(){
		try{
			Util.createCmisSessionByBasicAuth(repositoryId, userId, password);
		}catch(CmisUnauthorizedException e){
			return Messages.get("view.auth.login.error");
		}catch(CmisConnectionException e){
			logger.warn("Login failure. : ", e);
			return Messages.get("view.auth.login.error.connection");
		}catch(Exception e){
			logger.error("Login failure. : ", e);
			return Messages.get("view.auth.login.error.unknown");
		}

		return null;
	}
}

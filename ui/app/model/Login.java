package model;

import util.Util;
import play.data.validation.*;
import play.i18n.Messages;

public  class Login {

	 @Constraints.Required
	public String id;

	public	String password;

	public String repositoryId;


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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
			Util.createCmisSessionByBasicAuth(repositoryId, id, password);
		}catch(Exception e){
			return Messages.get("view.auth.login.error");
		}

		return null;
	}
}

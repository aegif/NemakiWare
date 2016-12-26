package util.authentication;

import org.pac4j.core.profile.CommonProfile;

import constant.Token;

public class NemakiProfile extends CommonProfile {
	private static final long serialVersionUID = 1L;

	public NemakiProfile(String repositoryId, String userId, String password){
		super();
        this.setUserId(userId);
        this.setPassword(password);
        this.setRepositoryId(repositoryId);
		this.setId(userId + "@@@" + repositoryId);
	}

	public void setUserId(String userId){
		this.addAttribute(Token.LOGIN_USER_ID, userId);
	}

	public String getUserId(){
		return this.getAttribute(Token.LOGIN_USER_ID, String.class);
	}

	public void setPassword(String password){
		this.addAttribute(Token.LOGIN_USER_PASSWORD, password);
	}

	public String getPassword(){
		return this.getAttribute(Token.LOGIN_USER_PASSWORD, String.class);
	}

	public void setRepositoryId(String repositoryId){
		this.addAttribute(Token.LOGIN_REPOSITORY_ID, repositoryId);
	}

	public String getRepositoryId(){
		return this.getAttribute(Token.LOGIN_REPOSITORY_ID, String.class);
	}

	public void setIsAdmin(boolean isAdmin){
		this.addAttribute(Token.LOGIN_USER_IS_ADMIN, isAdmin);
	}

	public Boolean getIsAdmin(){
		return this.getAttribute(Token.LOGIN_USER_IS_ADMIN, Boolean.class);
	}


	public void setVersion(String version){
		this.addAttribute(Token.NEMAKIWARE_VERSION, version);
	}

	public String getVersion(){
		return this.getAttribute(Token.NEMAKIWARE_VERSION, String.class);
	}





}

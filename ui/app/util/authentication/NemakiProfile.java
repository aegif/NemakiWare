package util.authentication;

import java.util.ArrayList;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.commons.lang3.StringUtils;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.saml.exceptions.SAMLException;
import org.pac4j.saml.profile.SAML2Profile;

import constant.Token;
import util.NemakiConfig;
import util.Util;

public class NemakiProfile extends CommonProfile {
	public enum CmisAuthType {
		BASIC, HEADER
	}

	private static final long serialVersionUID = 1L;

	public static final String userRepoSeparator = "@@@";

	public static NemakiProfile ConvertSAML2ToNemakiProfile(SAML2Profile saml2Profile, String repositoryId) {
		NemakiProfile profile = new NemakiProfile();
		profile.setRepositoryId(repositoryId);

		String remoteUserId = null;
		String userKey = NemakiConfig.getRemoteUserIdKey();
		if (saml2Profile.containsAttribute(userKey)) {
			@SuppressWarnings("rawtypes")
			ArrayList list = (ArrayList) saml2Profile.getAttribute(userKey);
			remoteUserId = (String) list.get(0);
			profile.setUserId(remoteUserId);
		} else {
			throw new SAMLException("キー" + userKey + "の値を取得できません。IdPから正しくデータが渡ってきているかを確認して下さい。");
		}

		Session cmisSession = Util.createCmisSessionByAuthHeader(repositoryId, remoteUserId);
		String version = cmisSession.getRepositoryInfo().getProductVersion();
		boolean isAdmin = false; // TODO:
		profile.setIsAdmin(isAdmin);
		profile.setVersion(version);
		profile.setId(remoteUserId + userRepoSeparator + repositoryId);
		profile.setCmisAuthType(CmisAuthType.HEADER);
		return profile;
	}

	private NemakiProfile() {

	}

	public NemakiProfile(String repositoryId, String userId, String password) {
		super();
		this.setCmisAuthType(CmisAuthType.BASIC);
		this.setUserId(userId);
		this.setPassword(password);
		this.setRepositoryId(repositoryId);
		this.setId(userId + userRepoSeparator + repositoryId);
	}

	public void setCmisAuthType(CmisAuthType type) {
		this.addAttribute(Token.LOGIN_AUTH_TYPE, type);
	}

	public CmisAuthType getCmisAuthType() {
		return this.getAttribute(Token.LOGIN_AUTH_TYPE, CmisAuthType.class);
	}

	public void setUserId(String userId) {
		this.addAttribute(Token.LOGIN_USER_ID, userId);
	}

	public String getUserId() {
		return this.getAttribute(Token.LOGIN_USER_ID, String.class);
	}

	public void setPassword(String password) {
		this.addAttribute(Token.LOGIN_USER_PASSWORD, password);
	}

	public String getPassword() {
		return this.getAttribute(Token.LOGIN_USER_PASSWORD, String.class);
	}

	public void setRepositoryId(String repositoryId) {
		this.addAttribute(Token.LOGIN_REPOSITORY_ID, repositoryId);
	}

	public String getRepositoryId() {
		return this.getAttribute(Token.LOGIN_REPOSITORY_ID, String.class);
	}

	public void setIsAdmin(boolean isAdmin) {
		this.addAttribute(Token.LOGIN_USER_IS_ADMIN, isAdmin);
	}

	public Boolean getIsAdmin() {
		return this.getAttribute(Token.LOGIN_USER_IS_ADMIN, Boolean.class);
	}

	public void setVersion(String version) {
		this.addAttribute(Token.NEMAKIWARE_VERSION, version);
	}

	public String getVersion() {
		return this.getAttribute(Token.NEMAKIWARE_VERSION, String.class);
	}

}

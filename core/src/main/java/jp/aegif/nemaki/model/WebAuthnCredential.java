package jp.aegif.nemaki.model;

import java.util.GregorianCalendar;
import java.util.List;

/**
 * WebAuthn credential model for passkey authentication.
 * Stored in CouchDB with objectType "nemaki:webauthnCredential".
 */
public class WebAuthnCredential extends NodeBase {

	private String userId;
	private String credentialId;      // Base64url encoded
	private String publicKeyCose;     // Base64url encoded
	private long signCount;
	private String aaguid;
	private String displayName;
	private List<String> transports;
	private boolean discoverable;
	private GregorianCalendar createdAt;
	private String objectType;

	public WebAuthnCredential() {
		super();
	}

	public WebAuthnCredential(NodeBase n) {
		setId(n.getId());
		setRevision(n.getRevision());
		setType("cmis:item");
		setObjectType("nemaki:webauthnCredential");
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public String getCredentialId() {
		return credentialId;
	}

	public void setCredentialId(String credentialId) {
		this.credentialId = credentialId;
	}

	public String getPublicKeyCose() {
		return publicKeyCose;
	}

	public void setPublicKeyCose(String publicKeyCose) {
		this.publicKeyCose = publicKeyCose;
	}

	public long getSignCount() {
		return signCount;
	}

	public void setSignCount(long signCount) {
		this.signCount = signCount;
	}

	public String getAaguid() {
		return aaguid;
	}

	public void setAaguid(String aaguid) {
		this.aaguid = aaguid;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public List<String> getTransports() {
		return transports;
	}

	public void setTransports(List<String> transports) {
		this.transports = transports;
	}

	public boolean isDiscoverable() {
		return discoverable;
	}

	public void setDiscoverable(boolean discoverable) {
		this.discoverable = discoverable;
	}

	public GregorianCalendar getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(GregorianCalendar createdAt) {
		this.createdAt = createdAt;
	}

	public String getObjectType() {
		return objectType;
	}

	public void setObjectType(String objectType) {
		this.objectType = objectType;
	}
}

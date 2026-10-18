package jp.aegif.nemaki.model.couch;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jp.aegif.nemaki.model.WebAuthnCredential;

/**
 * CouchDB document representation of WebAuthnCredential.
 * Handles JSON serialization/deserialization for CouchDB storage.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CouchWebAuthnCredential extends CouchNodeBase {

	private static final long serialVersionUID = 1L;

	@JsonProperty("userId")
	private String userId;

	/** The id of the UserItem document this passkey was registered for (R170). */
	@JsonProperty("accountId")
	private String accountId;

	/** The account's credential generation when it was registered, as text (R170). */
	@JsonProperty("credentialGeneration")
	private String credentialGeneration;

	@JsonProperty("credentialId")
	private String credentialId;

	@JsonProperty("publicKeyCose")
	private String publicKeyCose;

	@JsonProperty("signCount")
	private long signCount;

	@JsonProperty("aaguid")
	private String aaguid;

	@JsonProperty("displayName")
	private String displayName;

	@JsonProperty("transports")
	private List<String> transports;

	@JsonProperty("discoverable")
	private boolean discoverable;

	@JsonProperty("createdAt")
	private GregorianCalendar createdAt;

	@JsonProperty("objectType")
	private String objectType;

	private Map<String, Object> additionalProperties = new HashMap<>();

	public CouchWebAuthnCredential() {
		super();
	}

	@JsonCreator
	public CouchWebAuthnCredential(Map<String, Object> properties) {
		super(properties);
		if (properties != null) {
			this.userId = (String) properties.get("userId");
			this.credentialId = (String) properties.get("credentialId");
			this.publicKeyCose = (String) properties.get("publicKeyCose");
			this.aaguid = (String) properties.get("aaguid");
			this.displayName = (String) properties.get("displayName");
			this.objectType = (String) properties.get("objectType");
			this.accountId = (String) properties.get("accountId");
			// As text, whatever is stored: a value the rule cannot read has to reach the rule and be
			// refused there, not blow up the read (R170).
			Object generation = properties.get("credentialGeneration");
			this.credentialGeneration = generation == null ? null : generation.toString();

			Object signCountObj = properties.get("signCount");
			if (signCountObj instanceof Number) {
				this.signCount = ((Number) signCountObj).longValue();
			}

			Object discoverableObj = properties.get("discoverable");
			if (discoverableObj instanceof Boolean) {
				this.discoverable = (Boolean) discoverableObj;
			}

			if (properties.containsKey("transports")) {
				Object transportsObj = properties.get("transports");
				if (transportsObj instanceof List) {
					@SuppressWarnings("unchecked")
					List<String> transportsList = (List<String>) transportsObj;
					this.transports = new ArrayList<>(transportsList);
				}
			}

			if (properties.containsKey("createdAt")) {
				this.createdAt = parseDateTime(properties.get("createdAt"));
			}

			this.additionalProperties.putAll(properties);
		}
	}

	public CouchWebAuthnCredential(WebAuthnCredential credential) {
		super(credential);
		setUserId(credential.getUserId());
		setCredentialId(credential.getCredentialId());
		setPublicKeyCose(credential.getPublicKeyCose());
		setSignCount(credential.getSignCount());
		setAaguid(credential.getAaguid());
		setDisplayName(credential.getDisplayName());
		setTransports(credential.getTransports());
		setDiscoverable(credential.isDiscoverable());
		setCreatedAt(credential.getCreatedAt());
		setObjectType(credential.getObjectType());
		setAccountId(credential.getAccountId());
		setCredentialGeneration(credential.getCredentialGeneration());
	}

	public WebAuthnCredential convert() {
		WebAuthnCredential credential = new WebAuthnCredential(super.convert());
		credential.setUserId(getUserId());
		credential.setCredentialId(getCredentialId());
		credential.setPublicKeyCose(getPublicKeyCose());
		credential.setSignCount(getSignCount());
		credential.setAaguid(getAaguid());
		credential.setDisplayName(getDisplayName());
		credential.setTransports(getTransports());
		credential.setDiscoverable(isDiscoverable());
		credential.setCreatedAt(getCreatedAt());
		credential.setObjectType(getObjectType());
		credential.setAccountId(getAccountId());
		credential.setCredentialGeneration(getCredentialGeneration());
		return credential;
	}

	// Getters and Setters

	public String getAccountId() {
		return accountId;
	}

	public void setAccountId(String accountId) {
		this.accountId = accountId;
	}

	public String getCredentialGeneration() {
		return credentialGeneration;
	}

	public void setCredentialGeneration(String credentialGeneration) {
		this.credentialGeneration = credentialGeneration;
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

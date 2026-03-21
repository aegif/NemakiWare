package jp.aegif.nemaki.api.setup.model;

public class AuthConfigRequest {
    private boolean passwordEnabled = true;
    private boolean googleEnabled;
    private boolean microsoftEnabled;
    private OidcSettings oidcSettings;

    // Keycloak SSO (OIDC + SAML)
    private boolean keycloakOidcEnabled;
    private boolean samlEnabled;
    private String keycloakIssuerUrl;
    private String keycloakClientId;

    // SAML configuration
    private String samlIdpSsoUrl;
    private String samlSpEntityId;
    private String samlIdpCertificate;   // PEM format
    private String samlSloUrl;           // optional
    private String samlAttributeMapping; // optional

    // Provider-specific fields for Setup Wizard apply.
    // These are used alongside or instead of the generic oidcSettings.
    private String googleClientId;
    private String microsoftClientId;
    private String microsoftTenantId;

    public boolean isPasswordEnabled() { return passwordEnabled; }
    public void setPasswordEnabled(boolean passwordEnabled) { this.passwordEnabled = passwordEnabled; }
    public boolean isGoogleEnabled() { return googleEnabled; }
    public void setGoogleEnabled(boolean googleEnabled) { this.googleEnabled = googleEnabled; }
    public boolean isMicrosoftEnabled() { return microsoftEnabled; }
    public void setMicrosoftEnabled(boolean microsoftEnabled) { this.microsoftEnabled = microsoftEnabled; }
    public OidcSettings getOidcSettings() { return oidcSettings; }
    public void setOidcSettings(OidcSettings oidcSettings) { this.oidcSettings = oidcSettings; }

    public String getGoogleClientId() { return googleClientId; }
    public void setGoogleClientId(String googleClientId) { this.googleClientId = googleClientId; }
    public String getMicrosoftClientId() { return microsoftClientId; }
    public void setMicrosoftClientId(String microsoftClientId) { this.microsoftClientId = microsoftClientId; }
    public String getMicrosoftTenantId() { return microsoftTenantId; }
    public void setMicrosoftTenantId(String microsoftTenantId) { this.microsoftTenantId = microsoftTenantId; }

    public boolean isKeycloakOidcEnabled() { return keycloakOidcEnabled; }
    public void setKeycloakOidcEnabled(boolean keycloakOidcEnabled) { this.keycloakOidcEnabled = keycloakOidcEnabled; }
    public boolean isSamlEnabled() { return samlEnabled; }
    public void setSamlEnabled(boolean samlEnabled) { this.samlEnabled = samlEnabled; }
    public String getKeycloakIssuerUrl() { return keycloakIssuerUrl; }
    public void setKeycloakIssuerUrl(String keycloakIssuerUrl) { this.keycloakIssuerUrl = keycloakIssuerUrl; }
    public String getKeycloakClientId() { return keycloakClientId; }
    public void setKeycloakClientId(String keycloakClientId) { this.keycloakClientId = keycloakClientId; }

    public String getSamlIdpSsoUrl() { return samlIdpSsoUrl; }
    public void setSamlIdpSsoUrl(String samlIdpSsoUrl) { this.samlIdpSsoUrl = samlIdpSsoUrl; }
    public String getSamlSpEntityId() { return samlSpEntityId; }
    public void setSamlSpEntityId(String samlSpEntityId) { this.samlSpEntityId = samlSpEntityId; }
    public String getSamlIdpCertificate() { return samlIdpCertificate; }
    public void setSamlIdpCertificate(String samlIdpCertificate) { this.samlIdpCertificate = samlIdpCertificate; }
    public String getSamlSloUrl() { return samlSloUrl; }
    public void setSamlSloUrl(String samlSloUrl) { this.samlSloUrl = samlSloUrl; }
    public String getSamlAttributeMapping() { return samlAttributeMapping; }
    public void setSamlAttributeMapping(String samlAttributeMapping) { this.samlAttributeMapping = samlAttributeMapping; }

    public static class OidcSettings {
        private String issuerUrl;
        private String clientId;
        private String clientSecret;

        public String getIssuerUrl() { return issuerUrl; }
        public void setIssuerUrl(String issuerUrl) { this.issuerUrl = issuerUrl; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    }
}

package jp.aegif.nemaki.api.setup.model;

public class SetupApplyRequest {
    private CouchDbConnectionRequest couchdb;
    private AuthConfigRequest auth;
    private VectorTestRequest vector;
    private String adminPassword;
    /**
     * Whether to record provenance (roadmap §2-3).
     *
     * <p>{@code null} means the wizard did not ask — an older UI, or a scripted apply. Absence
     * MUST leave the stored value alone: writing a default here would switch lineage off for a
     * deployment that had turned it on, which is the opposite of what a setup wizard is for.
     * Provenance cannot be reconstructed after the fact, so losing it is not recoverable.
     */
    private Boolean lineageJournaled;

    public CouchDbConnectionRequest getCouchdb() { return couchdb; }
    public void setCouchdb(CouchDbConnectionRequest couchdb) { this.couchdb = couchdb; }
    public AuthConfigRequest getAuth() { return auth; }
    public void setAuth(AuthConfigRequest auth) { this.auth = auth; }
    public VectorTestRequest getVector() { return vector; }
    public void setVector(VectorTestRequest vector) { this.vector = vector; }
    public Boolean getLineageJournaled() { return lineageJournaled; }
    public void setLineageJournaled(Boolean lineageJournaled) { this.lineageJournaled = lineageJournaled; }
    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
}

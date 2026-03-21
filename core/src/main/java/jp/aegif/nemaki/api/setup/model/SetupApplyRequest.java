package jp.aegif.nemaki.api.setup.model;

public class SetupApplyRequest {
    private CouchDbConnectionRequest couchdb;
    private AuthConfigRequest auth;
    private VectorTestRequest vector;
    private String adminPassword;

    public CouchDbConnectionRequest getCouchdb() { return couchdb; }
    public void setCouchdb(CouchDbConnectionRequest couchdb) { this.couchdb = couchdb; }
    public AuthConfigRequest getAuth() { return auth; }
    public void setAuth(AuthConfigRequest auth) { this.auth = auth; }
    public VectorTestRequest getVector() { return vector; }
    public void setVector(VectorTestRequest vector) { this.vector = vector; }
    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
}

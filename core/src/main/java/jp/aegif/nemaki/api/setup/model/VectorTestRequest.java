package jp.aegif.nemaki.api.setup.model;

public class VectorTestRequest {
    private String type; // "tei" | "bedrock"
    private String url;
    private String region;
    private String modelId;
    private String accessKeyId;
    private String secretAccessKey;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
    public String getSecretAccessKey() { return secretAccessKey; }
    public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }
}

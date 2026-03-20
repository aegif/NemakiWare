package jp.aegif.nemaki.rest.purview;

public class PurviewGovernanceBulkItemView {

    private final String objectId;
    private final String status;
    private final String message;
    private final PurviewGovernanceView governance;

    public PurviewGovernanceBulkItemView(String objectId, String status, String message, PurviewGovernanceView governance) {
        this.objectId = objectId;
        this.status = status;
        this.message = message;
        this.governance = governance;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public PurviewGovernanceView getGovernance() {
        return governance;
    }
}

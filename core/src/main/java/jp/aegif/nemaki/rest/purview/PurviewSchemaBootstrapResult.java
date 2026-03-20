package jp.aegif.nemaki.rest.purview;

public class PurviewSchemaBootstrapResult {

    private final PurviewJobState jobState;
    private final PurviewSchemaApplyResult applyResult;

    public PurviewSchemaBootstrapResult(
            PurviewJobState jobState,
            PurviewSchemaApplyResult applyResult) {
        this.jobState = jobState;
        this.applyResult = applyResult;
    }

    public PurviewJobState getJobState() {
        return jobState;
    }

    public PurviewSchemaApplyResult getApplyResult() {
        return applyResult;
    }
}

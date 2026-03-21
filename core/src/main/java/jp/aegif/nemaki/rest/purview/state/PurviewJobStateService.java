package jp.aegif.nemaki.rest.purview.state;

public interface PurviewJobStateService {

    PurviewJobState saveJobState(PurviewJobState jobState);

    PurviewJobState getJobState(String jobId);
}

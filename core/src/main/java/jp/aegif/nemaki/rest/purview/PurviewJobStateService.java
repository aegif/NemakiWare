package jp.aegif.nemaki.rest.purview;

public interface PurviewJobStateService {

    PurviewJobState saveJobState(PurviewJobState jobState);

    PurviewJobState getJobState(String jobId);
}

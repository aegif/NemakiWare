package jp.aegif.nemaki.rest.purview.state;

import java.util.List;

public interface PurviewJobStateService {

    PurviewJobState saveJobState(PurviewJobState jobState);

    PurviewJobState getJobState(String jobId);

    List<PurviewJobState> listJobStates();

    int purgeOldJobStates(int retainCount);
}

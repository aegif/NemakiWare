package jp.aegif.nemaki.rest.purview;

public interface PurviewTypeReconciliationService {

    PurviewJobState startTypeReconciliation(String repositoryId, String requestedBy);
}

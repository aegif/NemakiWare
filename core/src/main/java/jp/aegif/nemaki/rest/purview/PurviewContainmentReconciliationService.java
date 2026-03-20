package jp.aegif.nemaki.rest.purview;

public interface PurviewContainmentReconciliationService {

    PurviewJobState startContainmentReconciliation(String repositoryId, String requestedBy);
}

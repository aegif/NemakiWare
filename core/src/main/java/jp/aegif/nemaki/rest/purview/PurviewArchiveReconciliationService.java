package jp.aegif.nemaki.rest.purview;

public interface PurviewArchiveReconciliationService {

    PurviewJobState startArchiveReconciliation(String repositoryId, String requestedBy);
}

package jp.aegif.nemaki.rest.purview.governance;

import java.util.List;

import org.apache.chemistry.opencmis.commons.server.CallContext;

public interface PurviewGovernanceService {

    PurviewGovernanceView getGovernance(String repositoryId, String objectId, CallContext callContext);

    List<PurviewGovernanceBulkItemView> getGovernanceBulk(
            String repositoryId,
            List<String> objectIds,
            CallContext callContext);
}

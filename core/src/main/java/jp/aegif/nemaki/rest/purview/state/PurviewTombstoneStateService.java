package jp.aegif.nemaki.rest.purview.state;

import java.time.Instant;
import java.util.List;

public interface PurviewTombstoneStateService {

    PurviewTombstoneState saveTombstoneState(PurviewTombstoneState tombstoneState);

    PurviewTombstoneState getTombstoneState(String repositoryId, String objectId);

    List<PurviewTombstoneState> listTombstoneStates(String repositoryId);

    List<PurviewTombstoneState> listDueTombstoneStates(String repositoryId, Instant dueAtOrBefore);

    void deleteTombstoneState(String repositoryId, String objectId);
}

package jp.aegif.nemaki.rest.purview;

import java.time.Instant;
import java.util.List;

public interface PurviewTombstoneStateService {

    PurviewTombstoneState saveTombstoneState(PurviewTombstoneState tombstoneState);

    PurviewTombstoneState getTombstoneState(String repositoryId, String objectId);

    List<PurviewTombstoneState> listTombstoneStates(String repositoryId);

    List<PurviewTombstoneState> listDueTombstoneStates(String repositoryId, Instant dueAtOrBefore);

    void deleteTombstoneState(String repositoryId, String objectId);
}

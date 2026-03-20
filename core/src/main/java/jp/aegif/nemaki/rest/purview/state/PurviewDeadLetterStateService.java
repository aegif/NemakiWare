package jp.aegif.nemaki.rest.purview.state;

import java.util.List;

public interface PurviewDeadLetterStateService {

    PurviewDeadLetterState saveDeadLetterState(PurviewDeadLetterState deadLetterState);

    PurviewDeadLetterState getDeadLetterState(String repositoryId, String streamKind, String entryKey);

    List<PurviewDeadLetterState> listDeadLetterStates();

    List<PurviewDeadLetterState> listDeadLetterStates(String repositoryId);

    List<PurviewDeadLetterState> listDeadLetterStates(String repositoryId, String streamKind);

    int countDeadLetterStates(String repositoryId, String streamKind);

    void deleteDeadLetterState(String repositoryId, String streamKind, String entryKey);
}

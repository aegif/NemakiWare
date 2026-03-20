package jp.aegif.nemaki.rest.purview;

public interface PurviewCursorStateService {

    PurviewCursorState saveCursorState(PurviewCursorState cursorState);

    PurviewCursorState getCursorState(String repositoryId, String streamKind);
}

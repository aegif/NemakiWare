package jp.aegif.nemaki.rest.purview;

import java.util.List;

import jp.aegif.nemaki.model.Archive;

public interface PurviewArchiveLineageService {

    int upsertArchiveLineage(String repositoryId, List<Archive> archives);
}

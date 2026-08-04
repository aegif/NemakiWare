/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.purview.lineage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.journal.LineageEndpoint;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;

/** See {@link LineageCatalogReconciliationService}. */
@Service
public class LineageCatalogReconciliationServiceImpl
        implements LineageCatalogReconciliationService {

    private static final Logger logger =
            LoggerFactory.getLogger(LineageCatalogReconciliationServiceImpl.class);

    private static final String COMPANION_TYPE = "nemaki_folder_dataset";
    private static final int CHILD_PAGE_SIZE = 100;

    private final MetadataCatalogConnectionResolver connectionResolver;
    private final RepositoryInfoMap repositoryInfoMap;
    private final ContentDaoService contentDaoService;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;
    private final LineageFolderCompanionLifecycle lifecycle;

    public LineageCatalogReconciliationServiceImpl(
            MetadataCatalogConnectionResolver connectionResolver,
            RepositoryInfoMap repositoryInfoMap,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient,
            LineageFolderCompanionLifecycle lifecycle) {
        this.connectionResolver = connectionResolver;
        this.repositoryInfoMap = repositoryInfoMap;
        this.contentDaoService = contentDaoService;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
        this.lifecycle = lifecycle;
    }

    @Override
    public Report reconcile(String repositoryId, int maxFolders, boolean repair) {
        RepositoryInfo info = repositoryInfoMap == null ? null : repositoryInfoMap.get(repositoryId);
        if (info == null || info.getRootFolderId() == null || info.getRootFolderId().isBlank()) {
            // Nothing was examined, so nothing is in sync. Reported as undetermined rather than
            // as an empty clean pass.
            logger.warn("Reconciliation for '{}' has no root folder to walk from", repositoryId);
            return new Report(repositoryId, 0, 0, 0, 0, 1, 0, 0);
        }

        long checked = 0;
        long inSync = 0;
        long companionMissing = 0;
        long sourceMissing = 0;
        long undetermined = 0;
        long repaired = 0;
        long markedOrphan = 0;

        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();
        queue.add(info.getRootFolderId());
        int bound = Math.max(1, maxFolders);

        while (!queue.isEmpty() && checked < bound) {
            String folderId = queue.removeFirst();
            if (!seen.add(folderId)) {
                continue;
            }
            Content folder = contentDaoService.getContent(repositoryId, folderId);
            if (folder != null && folder.isFolder()) {
                for (Content child : childFolders(repositoryId, folderId)) {
                    if (!seen.contains(child.getId())) {
                        queue.addLast(child.getId());
                    }
                }
            }
            checked++;

            Presence companion = companionPresence(repositoryId, folderId);
            if (companion == Presence.UNKNOWN) {
                undetermined++;
                continue;
            }
            boolean folderExists = folder != null && folder.isFolder();
            if (folderExists && companion == Presence.PRESENT) {
                inSync++;
            } else if (folderExists) {
                companionMissing++;
                if (repair && republish(repositoryId, folder)) {
                    repaired++;
                }
            } else if (companion == Presence.PRESENT) {
                sourceMissing++;
                if (repair && lifecycle.markState(repositoryId, folderId,
                        PurviewEntityPayloadFactory.SOURCE_STATE_ORPHAN)) {
                    markedOrphan++;
                }
            }
            // folder gone AND companion absent: nothing to reconcile, and not a finding.
        }

        Report report = new Report(repositoryId, checked, inSync, companionMissing, sourceMissing,
                undetermined, repaired, markedOrphan);
        if (!report.clean()) {
            logger.warn("Reconciliation of '{}': checked={} inSync={} companionMissing={}"
                    + " sourceMissing={} undetermined={} repaired={} markedOrphan={}",
                    repositoryId, checked, inSync, companionMissing, sourceMissing, undetermined,
                    repaired, markedOrphan);
        }
        return report;
    }

    /** Three-valued on purpose: a failed read is not an absent entity. */
    private enum Presence { PRESENT, ABSENT, UNKNOWN }

    private Presence companionPresence(String repositoryId, String folderId) {
        try {
            Map<String, Object> read = entityRegistryClient.getEntityByUniqueAttribute(
                    connectionResolver.buildConnectionRequest(), COMPANION_TYPE, "qualifiedName",
                    LineageEndpoint.folderProxyQualifiedName(repositoryId, folderId));
            return read == null || read.isEmpty() ? Presence.ABSENT : Presence.PRESENT;
        } catch (PurviewClientException e) {
            logger.warn("Cannot determine a folder companion in '{}': {}",
                    repositoryId, e.getClass().getSimpleName());
            return Presence.UNKNOWN;
        }
    }

    /**
     * Republishes a companion and its tie.
     *
     * <p>Idempotent, so a repair racing the ordinary sync converges instead of conflicting: both
     * publish the same entity under the same qualified name, and the tie is create-if-absent.
     */
    private boolean republish(String repositoryId, Content folder) {
        Map<String, Object> companion = lifecycle.companionFor(repositoryId, folder);
        if (companion == null) {
            return false;
        }
        try {
            PurviewEntityPublishResult result = entityRegistryClient.bulkCreateOrUpdateEntities(
                    connectionResolver.buildConnectionRequest(),
                    entityPayloadFactory.buildBulkPayload(List.of(companion)));
            if (result == null || !result.isSuccess()) {
                return false;
            }
        } catch (PurviewClientException e) {
            logger.warn("Could not repair a folder companion in '{}': {}",
                    repositoryId, e.getClass().getSimpleName());
            return false;
        }
        return lifecycle.tie(repositoryId, List.of(folder), Map.of()) == 1;
    }

    private List<Content> childFolders(String repositoryId, String folderId) {
        List<Content> folders = new ArrayList<>();
        long total = Math.max(0L, contentDaoService.getChildrenCount(repositoryId, folderId));
        for (int skip = 0; skip < total; skip += CHILD_PAGE_SIZE) {
            List<Content> page = contentDaoService.getChildrenPaged(
                    repositoryId, folderId, skip, CHILD_PAGE_SIZE);
            if (page == null || page.isEmpty()) {
                break;
            }
            for (Content child : page) {
                if (child != null && child.isFolder() && child.getId() != null
                        && !child.getId().isBlank()) {
                    folders.add(child);
                }
            }
        }
        return folders;
    }
}

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
package jp.aegif.nemaki.rest.purview.journal;

import java.util.Optional;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Content;

/**
 * What the repository says about one subject: EXISTS, PURGED, or nothing.
 *
 * <h2>The three answers, and what each one requires</h2>
 *
 * <dl>
 *   <dt>{@code SOURCE_EXISTS}</dt>
 *   <dd>A live object was read, with its incarnation and revision. A positive observation.</dd>
 *   <dt>{@code SOURCE_PURGED}</dt>
 *   <dd>An authoritative purge mark was read for <em>this</em> subject, and it has not been
 *       superseded by a restore. Also a positive observation.</dd>
 *   <dt>{@code SOURCE_UNKNOWN}</dt>
 *   <dd>Everything else, including every failure.</dd>
 * </dl>
 *
 * <h2>What is deliberately not PURGED</h2>
 *
 * <p>A 404. An empty query result. A missing archive. A DAO that threw. Each of those means
 * "I did not find it", which is compatible with a stale replica, a lagging index, a wrong
 * query, an object that has not been created yet — and with an object that is sitting in the
 * repository right now. Turning any of them into PURGED writes a permanent tombstone into a
 * catalog for a live document, and the catalog has no way to learn otherwise.
 *
 * <p>So absence is never evidence here. The only thing that authorises PURGED is the ledger
 * entry the destroying code wrote at the moment it destroyed the object.
 */
public final class RepositorySourceDispositionResolver
        implements LineageObligationWiringConfig.KindBoundSourceResolver {

    private static final Logger logger =
            LoggerFactory.getLogger(RepositorySourceDispositionResolver.class);

    private final EndpointKind boundKind;
    private final ContentService contentService;
    private final LineagePurgeLedger purgeLedger;
    private final LongSupplier clockMs;
    private final jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory payloadFactory;

    /** Without a payload factory the verdicts are unchanged and no projection is offered. */
    public RepositorySourceDispositionResolver(EndpointKind boundKind,
            ContentService contentService, LineagePurgeLedger purgeLedger, LongSupplier clockMs) {
        this(boundKind, contentService, purgeLedger, clockMs, null);
    }

    public RepositorySourceDispositionResolver(EndpointKind boundKind,
            ContentService contentService, LineagePurgeLedger purgeLedger, LongSupplier clockMs,
            jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory payloadFactory) {
        if (boundKind == null) {
            throw new IllegalArgumentException("a source resolver must name its endpoint kind");
        }
        this.boundKind = boundKind;
        this.contentService = contentService;
        this.purgeLedger = purgeLedger;
        this.clockMs = clockMs;
        this.payloadFactory = payloadFactory;
    }

    @Override
    public EndpointKind endpointKind() {
        return boundKind;
    }

    @Override
    public SourceEvidence dispositionOf(String repositoryId, EndpointKind kind,
            String catalogQualifiedName) {
        return observeLive(repositoryId, kind, catalogQualifiedName).evidence();
    }

    /**
     * The verdict and, for a live object, its catalog entity — from the one read.
     *
     * <p>{@code dispositionOf} is this with the projection dropped, so a caller that wants both
     * pays for one repository read, not two. That matters beyond efficiency: two reads could
     * straddle a modification and produce a verdict about one instance with attributes from
     * another, which is the defect this exists to close.
     */
    @Override
    public LiveSourceObservation observeLive(String repositoryId, EndpointKind kind,
            String catalogQualifiedName) {
        long now = clockMs.getAsLong();
        if (kind != boundKind) {
            // Asked about a kind this resolver does not answer for. An answer here would be
            // attributed to a lookup it never performed.
            return unknownObservation(now);
        }
        if (repositoryId == null || repositoryId.isBlank()
                || catalogQualifiedName == null || catalogQualifiedName.isBlank()) {
            return unknownObservation(now);
        }

        // 1. Is it live? A positive read is the strongest answer and needs nothing else.
        try {
            Optional<LiveObject> live = liveObject(repositoryId, kind, catalogQualifiedName);
            if (live.isPresent()) {
                SourceEvidence evidence = SourceEvidence.of(repositoryId, kind,
                        catalogQualifiedName, LineageSourceDisposition.SOURCE_EXISTS,
                        live.get().incarnation(), live.get().revision(), null, now);
                // Built from the same object the verdict was made from. A failure here yields
                // no projection rather than a partial one: the caller must refuse to write, not
                // fall back to the event's older copy.
                return new LiveSourceObservation(evidence,
                        projectionOf(repositoryId, kind, live.get()));
            }
        } catch (RuntimeException e) {
            // A repository read that failed establishes nothing — least of all that the object
            // is gone. Stop here rather than falling through to the ledger, because the ledger
            // could hold an old mark for an object this read could not check.
            logger.warn("Live-object check for a {} subject failed: {}", kind,
                    e.getClass().getSimpleName());
            return unknownObservation(now);
        }

        // 2. Not found live. That is NOT purged — ask the ledger, which is the only thing that
        // can say the object was destroyed rather than merely not found.
        if (purgeLedger == null) {
            return unknownObservation(now);
        }
        Optional<LineagePurgeLedger.PurgeMark> mark;
        try {
            String subject = SourceEvidence.subjectDigest(repositoryId, kind,
                    catalogQualifiedName);
            mark = purgeLedger.find(repositoryId, kind, subject);
        } catch (RuntimeException e) {
            logger.warn("Purge ledger read for a {} subject failed: {}", kind,
                    e.getClass().getSimpleName());
            return unknownObservation(now);
        }
        if (!LineagePurgeLifecyclePolicy.canBePurged(kind)) {
            // NemakiWare never destroys a source of this kind, so no mark for it can be
            // authoritative. Refusing here rather than trusting the ledger means a mark
            // written by mistake — from a compensating cleanup, say — can never tombstone
            // an object that is still there.
            return unknownObservation(now);
        }
        if (mark.isEmpty() || !mark.get().authoritative()) {
            // No mark, or one a restore superseded. Both mean nobody can attest destruction.
            return unknownObservation(now);
        }
        LineagePurgeLedger.PurgeMark purge = mark.get();
        // A purge verdict never carries a projection: there is nothing live to project.
        return new LiveSourceObservation(SourceEvidence.of(repositoryId, kind,
                catalogQualifiedName, LineageSourceDisposition.SOURCE_PURGED, purge.incarnation(),
                purge.revision(), null, now), null);
    }

    private static LiveSourceObservation unknownObservation(long now) {
        return new LiveSourceObservation(SourceEvidence.unknown(now), null);
    }

    /**
     * The catalog entity for the object this read returned, or null.
     *
     * <p>Built by the same factory the ordinary catalog sync uses, and per kind by the type the
     * subject actually is — a folder subject is its DataSet proxy, not the folder. A second
     * mapping here would let the live-source route and the steady state disagree, and the
     * disagreement would look like drift nobody caused.
     *
     * <p>Null rather than a partial entity when anything is missing. The caller treats null as
     * "do not write"; the one thing it must never do is fall back to the event's older copy,
     * which is the substitution this whole mechanism exists to prevent.
     */
    private java.util.Map<String, Object> projectionOf(String repositoryId, EndpointKind kind,
            LiveObject live) {
        if (payloadFactory == null) {
            return null;
        }
        try {
            java.util.Map<String, Object> entity = switch (kind) {
                case CMIS_DOCUMENT -> live.content() == null ? null
                        : payloadFactory.buildDocumentEntity(repositoryId, live.content());
                case CMIS_FOLDER -> live.content() == null ? null
                        : payloadFactory.buildFolderDatasetEntity(repositoryId, live.content(),
                                jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory
                                        .SOURCE_STATE_ACTIVE);
                case ARCHIVE -> live.archive() == null ? null
                        : payloadFactory.buildArchiveEntity(repositoryId, live.archive());
                // Every other kind is owned by its own connector. Projecting one here would
                // publish attributes this node never observed.
                default -> null;
            };
            return entity == null || entity.isEmpty() ? null : entity;
        } catch (RuntimeException e) {
            // Class name only: a payload build failure echoes the object's own attributes.
            logger.warn("Could not project a live {} subject for publication: {}", kind,
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /** A live object, the identifiers that pin which instance was seen, and the object itself. */
    private record LiveObject(String incarnation, String revision, Content content,
            Archive archive) { }

    /**
     * Whether the repository holds the object now.
     *
     * <p>Reads by identity, never by search: a search result is an index's opinion, and this
     * question is about the object itself. The revision is the document's own change token, so
     * a re-created object at the same id is a different observation from the original.
     */
    private Optional<LiveObject> liveObject(String repositoryId, EndpointKind kind,
            String catalogQualifiedName) {
        if (contentService == null) {
            return Optional.empty();
        }
        String objectId = objectIdOf(repositoryId, kind, catalogQualifiedName);
        if (objectId == null) {
            return Optional.empty();
        }
        return switch (kind) {
            case CMIS_DOCUMENT, CMIS_FOLDER -> {
                Content content = contentService.getContent(repositoryId, objectId);
                yield content == null ? Optional.empty()
                        : Optional.of(new LiveObject(objectId, revisionOf(content), content,
                                null));
            }
            case ARCHIVE -> {
                // An archive's "live" state is the archive row itself: the object is in the
                // archive, which is a place it exists, not a place it has been destroyed.
                Archive archive = contentService.getArchive(repositoryId, objectId);
                yield archive == null ? Optional.empty()
                        : Optional.of(new LiveObject(objectId,
                                archive.getLastRevision() == null ? "0"
                                        : archive.getLastRevision(), null, archive));
            }
            default -> Optional.empty();
        };
    }

    /**
     * The revision of a live object.
     *
     * <p>The change token when there is one — it is what CMIS itself uses to detect that an
     * object changed. Falling back to the id would make every read of the same object look
     * like the same revision, which would let a restore-and-modify pass a re-verification.
     */
    private static String revisionOf(Content content) {
        String changeToken = content.getChangeToken();
        return changeToken == null || changeToken.isBlank() ? "0" : changeToken;
    }

    /**
     * The object id inside a qualified name, or null if it is not one of ours.
     *
     * <p>Parsed rather than searched. The forms are fixed by {@link LineageEndpoint} and a name
     * that does not match one exactly yields null — a partial match would resolve the wrong
     * object.
     */
    static String objectIdOf(String repositoryId, EndpointKind kind,
            String catalogQualifiedName) {
        String documentPrefix = "nemaki://" + repositoryId + "/objects/";
        String folderPrefix = "nemaki://" + repositoryId + "/folders/";
        String archivePrefix = "nemaki://" + repositoryId + "/archives/";
        return switch (kind) {
            case CMIS_DOCUMENT -> after(catalogQualifiedName, documentPrefix, null);
            case CMIS_FOLDER -> after(catalogQualifiedName, folderPrefix, "/dataset");
            case ARCHIVE -> after(catalogQualifiedName, archivePrefix, null);
            default -> null;
        };
    }

    private static String after(String value, String prefix, String suffix) {
        if (value == null || !value.startsWith(prefix)) {
            return null;
        }
        String rest = value.substring(prefix.length());
        if (suffix != null) {
            if (!rest.endsWith(suffix)) {
                return null;
            }
            rest = rest.substring(0, rest.length() - suffix.length());
        }
        // A remaining separator means the name has more structure than this form allows, and
        // taking the first segment would silently resolve a different object.
        return rest.isBlank() || rest.contains("/") ? null : rest;
    }
}

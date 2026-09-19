package jp.aegif.nemaki.rest.ingest;

import org.apache.chemistry.opencmis.commons.server.CallContext;

/**
 * Canonical import pipeline for external content ingestion.
 *
 * <p>All entry points (UI, API, scheduler) converge through this service.
 * The pipeline handles:
 * <ol>
 *   <li>Profile + connector resolution and validation</li>
 *   <li>Dedupe check (sourceSystem + sourceObjectType + sourceObjectId)</li>
 *   <li>Document creation or version update</li>
 *   <li>Secondary type attachment (externalIntegration + archetype-specific)</li>
 *   <li>Relationship generation (if applicable)</li>
 *   <li>Lineage event emission</li>
 * </ol>
 */
public interface CanonicalImportService {

    /**
     * Execute the canonical import pipeline.
     *
     * @param callContext CMIS call context for authentication/authorization
     * @param request     the ingest request
     * @return result with created objectId, lineage eventId, or errors
     */
    ExternalIngestResult execute(CallContext callContext, ExternalIngestRequest request);

    /**
     * Auto-resolve connector and profile by sourceSystem and archetype, then execute.
     * Used by legacy code paths (e.g. CloudDriveResource) that don't have explicit
     * connector/profile IDs.
     *
     * @param callContext CMIS call context
     * @param request     the ingest request (profileId and connectorId may be null)
     * @param sourceSystem source system name; for {@link SourceArchetype#FILE_SHARE}, {@code google}/{@code google_drive}
     *                     and {@code microsoft}/{@code onedrive} are interchangeable for connector lookup
     * @param archetype    source archetype for auto-resolution
     * @return result with created objectId, or error if auto-resolution fails
     */
    ExternalIngestResult executeWithAutoResolve(CallContext callContext, ExternalIngestRequest request,
                                                String sourceSystem, SourceArchetype archetype);

    /**
     * Import an .eml file: parse MIME, create message document + attachment documents,
     * apply nemaki:messageMetadata, create relationships.
     *
     * @param callContext CMIS context
     * @param request     ingest request with .eml contentStream
     * @return result for the message document (attachments imported as side effects)
     */
    ExternalIngestResult executeMailImport(CallContext callContext, ExternalIngestRequest request);

    /**
     * Import a compound note (page + attachments): create page document,
     * apply nemaki:noteMetadata, import attachments with relationships.
     *
     * <p>The request's metadata should contain note-specific fields:
     * pageId, pageUrl, parentPageId, workspaceId, author, lastEditedBy.
     * Attachments are passed in metadata as a JSON array under key "attachments",
     * or as separate ingest requests after the page import.
     */
    ExternalIngestResult executeNoteImport(CallContext callContext, ExternalIngestRequest request);

    /**
     * Import a business record: create document, apply nemaki:businessRecordMetadata.
     */
    ExternalIngestResult executeBusinessRecordImport(CallContext callContext, ExternalIngestRequest request);

    /**
     * Import chat context: create document, apply nemaki:chatContextMetadata.
     */
    ExternalIngestResult executeChatContextImport(CallContext callContext, ExternalIngestRequest request);

    /**
     * Create a CMIS relationship between two objects.
     *
     * <p>Used by the fetch orchestrators after their import has returned; carries no capture
     * scope. A link whose duplicate check could not be answered is still created and still
     * answers {@code null} — the fact is logged at WARN, because the callers put every
     * non-null answer into their fetch errors and a created link is not one. Inside an import
     * the same case is reported as a relationship warning.
     *
     * <p>The link IS authorised (R4). It used to be created with no profile at all, so a
     * delegated profile whose authorisation had been revoked while the fetch ran still got its
     * edges written — the check was not failed, it was never made. {@code authorizingProfile}
     * is the profile the fetch is running, and {@code request} names the connector its import
     * used; the delegation is re-asked against the folder's CURRENT ACL and the connector row
     * as it is now. The profile ROW is not re-read on this path — it is the row the fetch
     * started with — so {@code isDelegated} and {@code targetFolderId} are that row's values;
     * an in-import caller that passes no profile does re-read it. Passing null for both means the caller has nothing to authorise
     * against and no re-check happens, which is why {@code FetchSupport} refuses rather than
     * calling it that way.
     *
     * @return a message when the relationship was NOT created, null when it was (or already
     *         existed)
     */
    String createDirectRelationship(CallContext callContext, String repositoryId,
                                    String sourceId, String targetId,
                                    ImportProfileDefinition authorizingProfile,
                                    ExternalIngestRequest request);
}

# PR #395 Review (Revision 7) – Remaining Gaps

## Summary
The latest integration still diverges from the stated Revision 6 notes. Key behaviors in the current branch do not match the described fixes, leaving authorization gaps and contract mismatches that could surprise callers or bypass intended safeguards.

## Findings
1. **Batch endpoint still lacks per-object ACL evaluation and skip reporting.**
   - `RenditionController.generateRenditionsBatch` returns only `generatedIds`/counts and delegates straight to `ContentService.generateRenditionsBatch` with no per-object permission or type checks at the controller level.【F:core/src/main/java/jp/aegif/nemaki/rest/controller/RenditionController.java†L270-L334】
   - `ContentServiceImpl.generateRenditionsBatch` simply iterates and calls `generateRendition` without verifying read permission, object existence/type, or recording skipped items; the response fields `skipped`/`permittedCount` mentioned in the revision notes are absent.【F:core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java†L2815-L2838】
   - **Impact:** Admin-triggered batches will attempt conversion for every passed ID, including objects the admin cannot read or non-document IDs, and clients cannot tell which items were rejected.

2. **Renditions still accepted for folders despite “documents-only” contract callouts.**
   - `getRenditions` explicitly allows both documents and folders and only rejects other object types.【F:core/src/main/java/jp/aegif/nemaki/rest/controller/RenditionController.java†L167-L185】
   - Service-side `getRenditions` also populates rendition IDs for folders, so a folder request will succeed rather than return `400 Bad Request` as described in earlier review notes.【F:core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java†L2723-L2744】
   - **Impact:** API behavior deviates from the “document-only” promise called out in revision notes, which can confuse consumers and mask misuse.

## Recommendations
- Implement per-object validation in the batch path (existence, document type, read permission) and surface `generatedIds`, `skipped` (with reason), and `permittedCount` as stated in the Revision 6 notes.
- Align the rendition contract to “documents only” by rejecting folder targets (return `400`) at both controller and service layers, or update the public contract/documentation to explicitly allow folders and adjust earlier review notes accordingly.

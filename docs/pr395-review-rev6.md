# PR 395 Rendition Implementation Review (Revision 6)

Scope: branch `work` (origin/codex/review-detailed-design-for-rendition-feature` merged into PR #395). The changes fix earlier authentication and linkage gaps; remaining issues are below.

## 1) Rendition API still accepts folders (contract mismatch)
`RenditionController.getRenditions()` now performs authentication and read permission checks, but it explicitly allows folders (returns 200) while `generateRendition` rejects non-documents with 404. The design and requirements only call for document renditions. Accepting folders produces inconsistent API behavior and makes the CouchDB `renditionsByDocumentId` view unusable for those calls because folders never get renditions.

References: `RenditionController#getRenditions` type check (documents **or folders** allowed) vs `#generateRendition` (documents only).

## 2) Batch generation skips per-object permission checks
`generateRenditionsBatch` enforces admin-only access but never performs per-object ACL checks before calling `ContentServiceImpl.generateRendition`, which itself does not validate read permission. A single admin token can therefore trigger renditions for objects the caller cannot read, contrary to the per-request checks in the single-item endpoint. At minimum, the controller should reuse `hasReadPermission` per object (and skip/record failures) to keep behavior consistent.

References: `RenditionController#generateRenditionsBatch` (no `hasReadPermission` usage) and `ContentServiceImpl#generateRendition` (no permission check).

## 3) Converter lifecycle remains single-shot with hardcoded port 8100
`JodRenditionManagerImpl.convertToPdf` spins up a new `OfficeManager` per request on fixed port `8100`. If LibreOffice is already running (e.g., a long-lived pool or another conversion in progress), conversions will fail with port conflicts and return `null`, but the API reports "No rendition generated" without surfacing the underlying error. Using `LocalOfficeManager` with a pool and configurable ports (or reusing the existing manager) would make the service resilient under concurrent or repeated conversions.

References: `JodRenditionManagerImpl#convertToPdf` creates `DefaultOfficeManagerBuilder().setPortNumber(8100).build()` per call and tears it down each time.

## Suggested fixes
- Reject folders in `getRenditions` (return 400) to align with `generateRendition` and the view schema, or explicitly document folder renditions and implement folder support in DAO/service layers.
- Add per-object permission checks in `generateRenditionsBatch` (reuse `hasReadPermission`) and return partial results with failure reasons to prevent silent skips.
- Replace the per-call `DefaultOfficeManagerBuilder` with a pooled `LocalOfficeManager` configured via properties (home/ports, maxTasksPerProcess) and propagate conversion failures to clients (e.g., HTTP 503 with error detail) instead of silent null.

# PR395 Rendition Implementation Review (Revision 8)

Status: Reviewed latest `work` branch snapshot corresponding to PR #395 claims. Several issues remain unresolved despite prior "fixed" notes.

## 1) Batch generation still lacks per-object validation/ACL and skip reporting (Critical)
- `generateRenditionsBatch` simply loops object IDs and delegates to `generateRendition` without checking existence, document type, or read permission, and returns no skipped list or per-object errors. Evidence: `ContentServiceImpl.generateRenditionsBatch` lines 2816-2838 and `RenditionController.generateRenditionsBatch` lines 270-341. The controller only enforces admin + maxItems; it never filters out non-documents or unauthorized objects before invoking the service.
- This contradicts the Revision 6/7 notes claiming per-object ACL and skip reporting were added. The current implementation can attempt conversions for folders/unknown IDs and silently drop failures, which breaks the contract documented in the review thread.

## 2) Folder handling remains inconsistent with document-only generation (Major)
- Retrieval endpoint still accepts folders (`RenditionController#getRenditions` lines 167-174) and the service will return folder rendition IDs (`ContentServiceImpl#getRenditions` lines 2735-2743). However, generation is document-only (`generateRendition` starts by `getDocument` lines 2757-2762), so a folder request can never produce renditions. This inconsistency means folder calls succeed with empty results instead of a clear 400/404.
- The earlier review concern that folder inputs should be rejected at the controller/service boundary therefore still stands; the claimed fix "already handled" is not present in code.

## 3) Batch responses lack transparency (Major)
- Responses from `/batch` only return `generatedIds` and counts (controller lines 323-331); there is no `skipped` array or reason codes despite review feedback. This makes it impossible for callers to know which objects were ignored due to type, missing content, or permission denial. Coupled with point (1), operational visibility is low and mismatches the documented behavior.

## Recommended fixes before merge
1. Enforce per-object validation inside `generateRenditionsBatch` (or controller) to check existence, document type, and read permission; accumulate `generated`, `skipped` with reasons, and return that structure.
2. Reject folder targets in both `getRenditions` and batch/single generation endpoints (400) to align with document-only generation, or add folder rendition generation support end-to-end.
3. Extend batch response to include `skipped` entries (id + reason) and align tests/docs accordingly.

If these are addressed in another branch, please point to the specific commits; they are not present in the current snapshot.

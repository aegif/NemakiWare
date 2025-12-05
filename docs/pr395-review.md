# PR 395 Implementation Review (Rendition feature)

This review is based on the patch from https://github.com/aegif/NemakiWare/pull/395 (downloaded as `pr395.patch`). Key risks and fixes required before merge are summarized below.

## 1) Authentication / Authorization gaps

The new REST controller handles rendition endpoints without enforcing authentication or per-document permission checks. When no `CallContext` is present, it silently falls back to `SystemCallContext`, effectively bypassing ACLs:

```java
// core/src/main/java/jp/aegif/nemaki/rest/controller/RenditionController.java
if (callContext == null) {
    callContext = new SystemCallContext(repositoryId);
    log.warn("No CallContext found in request, using SystemCallContext for rendition generation");
}
String renditionId = getContentService().generateRendition(callContext, repositoryId, objectId, force);
```

The `getRenditions` and `supported-types` endpoints also lack any authentication or permission checks, so unauthenticated users can enumerate rendition metadata and supported MIME types. In addition, `web.xml` still maps `restAuthenticationFilter` only to `/rest/*` paths, leaving the new `/api/*` endpoints unprotected.

## 2) CouchDB view uses wrong field name

The new views emit `doc.documentId` and `doc.kind`, but the rendition model stores the owning document ID as `renditionDocumentId`:

```java
// core/src/main/java/jp/aegif/nemaki/patch/Patch_StandardCmisViews.java
addViewIfMissing(views, "renditionsByDocumentId", "function(doc) { if (doc.type == 'rendition' && doc.documentId) emit(doc.documentId, doc) }", null, repositoryId);
addViewIfMissing(views, "renditionsByKind", "function(doc) { if (doc.type == 'rendition' && doc.kind)  emit(doc.kind, doc) }", null, repositoryId);
```

```java
// core/src/main/java/jp/aegif/nemaki/model/Rendition.java
private String renditionDocumentId;
```

Because the emitted key does not match the stored field, these views will never return results, breaking the lookup paths described in the design.

## 3) Batch/admin enforcement is incomplete

The batch endpoint checks the caller against the admin list but still relies on the unprotected `/api/*` path and allows `force` regeneration without per-document permission checks. Until the authentication filter covers `/api/*`, these endpoints are callable anonymously. Even after filter mapping is fixed, the controller should validate read permission on the target document before generating renditions.

## Recommended fixes

1. Map `restAuthenticationFilter` (or equivalent) to `/api/*` in `web.xml` and require an authenticated `CallContext` for all rendition endpoints; remove the `SystemCallContext` fallback and add per-document permission checks.
2. Fix the CouchDB views to emit `doc.renditionDocumentId` (and `emit([doc.renditionDocumentId, doc.kind], doc)` for the kind-indexed view).
3. Enforce admin-only access for batch/force operations *after* authentication, and apply read/ACL checks for single-document generation and retrieval.

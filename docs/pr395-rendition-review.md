# PR395 Rendition Implementation – Follow-up Review (Dec 2025)

## Blocking / High-Risk Issues

1. **Rendition metadata is written with the *source* MIME type/length instead of the converted PDF values, and configuration is ignored.**
   - `createPreview` always sets kind to `cmis:preview` and copies the original stream’s MIME type and length before conversion, so a PDF rendition is stored as (e.g.) `application/vnd.openxmlformats-officedocument.wordprocessingml.document` with the original byte length. This breaks client-side type detection, size reporting, and any non-default rendition kind configuration. `renditionManager.isRenditionEnabled()`/`getRenditionKind()` are never consulted here, so disabling renditions or changing the default kind has no effect on auto-created previews. 【F:core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java†L2631-L2655】
   - `createPreviewAtomic` only checks `checkConvertible()` and still calls `createPreview`, so the above metadata/config issues affect all automatic preview creation paths (document create/update). There is no guard for `rendition.enabled=false`. 【F:core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java†L3423-L3438】

2. **Conversion lifecycle leaks resources and can leave soffice processes running.**
   - `convertToPdf` starts an `OfficeManager` but only stops it on the happy path; if `converter.convert(...)` throws, the manager is never stopped. The returned `ContentStream` wraps a `FileInputStream` that is never closed, so repeated conversions will leak file descriptors. These leaks are amplified because renditions are created during document create/update flows. 【F:core/src/main/java/jp/aegif/nemaki/businesslogic/rendition/impl/JodRenditionManagerImpl.java†L217-L279】

3. **UI is hard-wired to `cmis:preview` + PDF and will not show renditions when the default kind is configured differently.**
   - `OfficePreview` filters renditions strictly by `kind === 'cmis:preview' && mimetype === 'application/pdf'`, ignoring the configurable default kind. If an operator sets `rendition.default.kind=preview` (normalized to `cmis:preview`) it works, but any other kind or future multi-output mapping will be invisible to the UI. 【F:core/src/main/webapp/ui/src/components/PreviewComponent/OfficePreview.tsx†L82-L101】

## Recommended Fixes
- In `createPreview`, populate kind/mimetype/length from the *converted* stream and gate the operation with `renditionManager.isRenditionEnabled()` plus `getRenditionKind()`/`getTargetMimeType()` for consistency with the mapping. Apply the same guard in `createPreviewAtomic`.
- In `convertToPdf`, stop the `OfficeManager` in a `finally` block and close the returned `FileInputStream` (e.g., wrap in a `ContentStream` built from a byte array or manage the stream lifecycle in `contentDaoService.createRendition`).
- In `OfficePreview`, honor the configured/returned rendition kind (and possibly accept any PDF rendition) instead of a fixed `cmis:preview` string.


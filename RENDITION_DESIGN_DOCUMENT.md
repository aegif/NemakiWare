# NemakiWare 3.0 RC Rendition Feature - Detailed Design Document

## Document Information

- **Version**: 2.2 (Additional follow-up review revisions)
- **Date**: 2025-12-04
- **Author**: Devin (Cognition AI)
- **Requested by**: Akinori Ishii (@yumioka)
- **Branch**: feature/saml-oidc-fix-with-playwright-updates
- **Review Status**: Revised to address all issues from three code reviews + additional follow-up (see Appendix C for mapping)

## Executive Summary

This document provides a detailed design for implementing rendition functionality in NemakiWare 3.0 RC. The design ensures backward compatibility with existing v2.4 CouchDB databases while adding configurable rendition generation, retroactive rendition creation, and UI preview integration.

## Requirements Summary

1. **Backward Compatibility**: Existing v2.4 CouchDB databases must work transparently without modification
2. **Configuration Control**: The following must be configurable via properties file:
   - Path to rendition creation program/service
   - CMIS rendition kind value (default: 'preview' which maps to 'cmis:preview')
   - File format mappings (source format -> program -> output format; default: Word/Excel/PowerPoint -> PDF)
3. **Retroactive Rendition Creation**: Documents without renditions can have them created later via exception handling hook or REST API
4. **Cascading Deletion**: When documents are deleted, associated renditions must also be deleted
5. **CouchDB Migration**: Design document views must be added during initialization if missing
6. **UI Preview**: Preview tab should display renditions for non-text/non-PDF files

> **REVIEW NOTE - Rendition Kind Clarification**:
> The original requirement specified "デフォルトは'preview'としたい" (default should be 'preview'). 
> The CMIS standard uses 'cmis:preview' as the kind value. This design normalizes both forms:
> - Configuration accepts both 'preview' and 'cmis:preview'
> - Internally, 'preview' is normalized to 'cmis:preview' for CMIS compliance
> - UI and backend consistently use the normalized 'cmis:preview' value

## Current Implementation Analysis

### Existing Infrastructure

The rendition infrastructure already exists in NemakiWare but is incomplete:

| Component | Status | Location |
|-----------|--------|----------|
| Rendition model | EXISTS | `core/.../model/Rendition.java`, `CouchRendition.java` |
| RenditionManager interface | EXISTS | `core/.../businesslogic/rendition/RenditionManager.java` |
| JODConverter implementation | EXISTS | `core/.../rendition/impl/JodRenditionManagerImpl.java` |
| CouchDB renditions view | EXISTS | `_design/_repo` in `bedroom_init.dump` |
| Cascading deletion | EXISTS | `ContentServiceImpl.deleteDocument()` lines 2373-2377 |
| Preview capability flag | EXISTS | `capability.extended.preview` in `nemakiware.properties` |
| `isPreviewEnabled()` method | EXISTS | `ContentServiceImpl.java` line 2622-2626 |
| UI rendition support | MISSING | `core/src/main/webapp/ui/src/services/cmis.ts` has no rendition methods |
| Retroactive creation API | MISSING | No REST endpoint exists |
| Format mapping config | MISSING | Only image formats in `rendition-format.yml` |

### Key Findings

1. **Preview Creation Points**: Renditions are created at:
   - `createDocument()` via `createPreviewAtomic()` (line 666)
   - `createDocumentFromSource()` via `createPreviewAtomic()` (line 859)
   - `replacePwc()` (line 939)
   - `updateDocumentWithNewStream()` (line 1002)

2. **Capability Flag**: `capability.extended.preview=false` exists but `isPreviewEnabled()` is defined but NOT currently used to gate preview creation. The `createPreviewAtomic()` method only checks `renditionManager.checkConvertible()`.

3. **Format Registry**: `rendition-format.yml` only defines image formats (BMP, GIF, JPEG, PNG). JODConverter's `DefaultDocumentFormatRegistry` likely includes Office formats by default.

4. **REST API Pattern**: Existing controllers use `@RequestMapping("/api/v1/repo/{repositoryId}/...")` pattern (see `UserController.java`).

---

## Detailed Design

### 1. Configuration Changes

#### 1.1 New PropertyKey Constants

**File**: `core/src/main/java/jp/aegif/nemaki/util/constant/PropertyKey.java`

**Changes**: Add the following constants after line 248:

```java
// Rendition service (existing)
final String JODCONVERTER_REGISTRY_DATAFORMATS = "jodconverter.registry.dataformats";
final String JODCONVERTER_OFFICEHOME = "jodconverter.officehome";

// Rendition configuration (NEW)
// FOLLOW-UP REVIEW NOTE: Add Javadoc comments with default values for at-a-glance visibility
/** Enable/disable rendition feature. Default: true */
final String RENDITION_ENABLED = "rendition.enabled";
/** Default rendition kind. Default: cmis:preview (accepts 'preview' which is normalized) */
final String RENDITION_DEFAULT_KIND = "rendition.default.kind";
/** Path to rendition mapping YAML file. Default: rendition-mapping.yml */
final String RENDITION_MAPPING_DEFINITION = "rendition.mapping.definition";
/** Enable lazy rendition creation on preview request. Default: false (recommended) */
final String RENDITION_LAZY_CREATE_ON_PREVIEW = "rendition.lazy.createOnPreview";
/** Converter type: 'jod' (JODConverter) or 'external' (future). Default: jod */
final String RENDITION_CONVERTER_TYPE = "rendition.converter.type";
/** Path to external converter command (future use). Default: null */
final String RENDITION_EXTERNAL_COMMAND = "rendition.external.command";
/** URL of external converter service (future use). Default: null */
final String RENDITION_EXTERNAL_URL = "rendition.external.url";
```

#### 1.2 Default Value Centralization

> **REVIEW NOTE**: The second review requested centralized default value management to avoid scattered `if null then ...` checks.

**Design Decision**: All rendition-related default values are defined in `JodRenditionManagerImpl.init()` method and applied consistently:

| Property | Default Value | Applied In |
|----------|---------------|------------|
| `rendition.enabled` | `true` | `JodRenditionManagerImpl.init()` |
| `rendition.default.kind` | `cmis:preview` | `normalizeRenditionKind()` |
| `rendition.mapping.definition` | `rendition-mapping.yml` | `loadRenditionMapping()` |
| `rendition.lazy.createOnPreview` | `false` | `isLazyRenditionEnabled()` |
| `rendition.converter.type` | `jod` | `JodRenditionManagerImpl.init()` |

**Implementation Pattern**:
```java
// Centralized default handling in JodRenditionManagerImpl.init()
private static final boolean DEFAULT_RENDITION_ENABLED = true;
private static final String DEFAULT_RENDITION_KIND = "cmis:preview";
private static final String DEFAULT_MAPPING_FILE = "rendition-mapping.yml";
private static final boolean DEFAULT_LAZY_CREATE = false;

@PostConstruct
public void init() {
    // Apply defaults with explicit fallback
    String enabledStr = propertyManager.readValue(PropertyKey.RENDITION_ENABLED);
    renditionEnabled = (enabledStr != null) ? Boolean.parseBoolean(enabledStr) : DEFAULT_RENDITION_ENABLED;
    
    String configuredKind = propertyManager.readValue(PropertyKey.RENDITION_DEFAULT_KIND);
    defaultKind = normalizeRenditionKind(configuredKind); // Uses DEFAULT_RENDITION_KIND internally
    
    // ... etc
}
```

**Fail-Safe Behavior**: If configuration is missing or malformed:
- Missing properties use documented defaults
- Invalid rendition kind values fall back to `cmis:preview` with warning log
- Missing mapping file uses hardcoded Office→PDF mappings
- All fallbacks are logged at WARN level for troubleshooting

#### 1.3 Configuration File Updates

**File**: `core/src/main/webapp/WEB-INF/classes/nemakiware.properties`

**Changes**: Add the following section after line 56:

```properties
###Rendition
jodconverter.registry.dataformats=rendition-format.yml
jodconverter.officehome=/usr/lib/libreoffice

# Rendition feature configuration
rendition.enabled=true
rendition.default.kind=cmis:preview
rendition.mapping.definition=rendition-mapping.yml
rendition.lazy.createOnPreview=false
rendition.converter.type=jod
# Reserved for future use:
# rendition.external.command=/path/to/converter
# rendition.external.url=http://converter-service/api/convert
```

**File**: `core/src/main/webapp/WEB-INF/classes/nemakiware-docker.properties`

**Changes**: Add the same rendition configuration section.

#### 1.3 New Rendition Mapping File

**File**: `core/src/main/webapp/WEB-INF/classes/rendition-mapping.yml` (NEW FILE)

> **REVIEW NOTE - Mapping vs Implementation Alignment**:
> The second review identified that the mapping file appears flexible but `convertToPdf()` is hardcoded to PDF output.
> 
> **3.0 RC Scope**: The mapping file is used for:
> 1. Determining which source MIME types are convertible (`checkConvertible()`)
> 2. Setting the rendition kind (`getRenditionKind()`)
> 3. **NOT** for selecting output format (always PDF in 3.0 RC)
> 
> The `targetMediaType` field is included for future extensibility but is currently ignored.
> This is explicitly documented to avoid confusion.

> **FOLLOW-UP REVIEW NOTE - Future Extension Point for Multi-Output Support**:
> 
> To add support for multiple output formats (e.g., thumbnail + PDF) in a future release:
> 1. **RenditionManager Interface**: Add `List<ContentStream> convertToMultiple(ContentStream, List<String> targetMimeTypes)`
> 2. **JodRenditionManagerImpl**: Implement multi-target conversion, reading `targetMediaType` from mapping
> 3. **Mapping File**: Allow array syntax for `targetMediaType: ["application/pdf", "image/png"]`
> 4. **ContentServiceImpl.createPreviewAtomic()**: Loop over returned streams and create multiple renditions
> 
> The current abstraction layer (`RenditionManager`) is the correct extension point. No changes to CouchDB schema or REST API are required for multi-output support.

```yaml
# Rendition Mapping Configuration
# Defines which source formats should have renditions generated
# and what output format to produce
#
# NOTE (3.0 RC): Only PDF output is supported. The targetMediaType field
# is reserved for future use but is currently ignored by the converter.

mappings:
  # Microsoft Word documents
  - sourceMediaTypes:
      - "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
      - "application/msword"
    converter: "jod"
    targetMediaType: "application/pdf"  # Currently only PDF is supported
    kind: "cmis:preview"
    
  # Microsoft Excel documents
  - sourceMediaTypes:
      - "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
      - "application/vnd.ms-excel"
    converter: "jod"
    targetMediaType: "application/pdf"  # Currently only PDF is supported
    kind: "cmis:preview"
    
  # Microsoft PowerPoint documents
  - sourceMediaTypes:
      - "application/vnd.openxmlformats-officedocument.presentationml.presentation"
      - "application/vnd.ms-powerpoint"
    converter: "jod"
    targetMediaType: "application/pdf"  # Currently only PDF is supported
    kind: "cmis:preview"
    
  # OpenDocument formats
  - sourceMediaTypes:
      - "application/vnd.oasis.opendocument.text"
      - "application/vnd.oasis.opendocument.spreadsheet"
      - "application/vnd.oasis.opendocument.presentation"
    converter: "jod"
    targetMediaType: "application/pdf"  # Currently only PDF is supported
    kind: "cmis:preview"
```

---

### 2. Backend Service Layer Changes

#### 2.1 RenditionManager Interface Enhancement

**File**: `core/src/main/java/jp/aegif/nemaki/businesslogic/rendition/RenditionManager.java`

**Changes**: Add new methods:

```java
package jp.aegif.nemaki.businesslogic.rendition;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import java.util.List;

public interface RenditionManager {
    // Existing methods
    public ContentStream convertToPdf(ContentStream contentStream, String documentName);
    public boolean checkConvertible(String mediatype);
    
    // NEW: Get target mimetype for a source mimetype based on mapping
    public String getTargetMimeType(String sourceMimeType);
    
    // NEW: Get rendition kind for a source mimetype based on mapping
    public String getRenditionKind(String sourceMimeType);
    
    // NEW: Check if rendition generation is enabled
    public boolean isRenditionEnabled();
    
    // NEW: Get list of supported source mimetypes
    public List<String> getSupportedSourceMimeTypes();
}
```

#### 2.2 JodRenditionManagerImpl Enhancement

**File**: `core/src/main/java/jp/aegif/nemaki/businesslogic/rendition/impl/JodRenditionManagerImpl.java`

> **REVIEW NOTE**: The existing code uses Setter Injection + `@PostConstruct` pattern. This design maintains that pattern for compatibility with existing Spring XML configuration.

**Changes**: 

1. Add new fields after line 36 (keep existing setter injection pattern):
```java
// Existing fields (unchanged)
private PropertyManager propertyManager;
private DefaultDocumentFormatRegistry registry;
private static final Log log = LogFactory.getLog(JodRenditionManagerImpl.class);

// NEW: Rendition mapping configuration
private List<RenditionMapping> renditionMappings;
private boolean renditionEnabled;
private String defaultKind;

// Existing setter (unchanged - maintains Spring XML compatibility)
public void setPropertyManager(PropertyManager propertyManager) {
    this.propertyManager = propertyManager;
}
```

2. Modify `init()` method to load rendition mapping (extends existing @PostConstruct method):
```java
@PostConstruct
public void init() {
    registry = DefaultDocumentFormatRegistry.getInstance();
    
    // Load existing format definitions
    // ... existing code ...
    
    // NEW: Load rendition enabled flag
    String enabledStr = propertyManager.readValue(PropertyKey.RENDITION_ENABLED);
    renditionEnabled = Boolean.parseBoolean(enabledStr);
    
    // NEW: Load default kind with normalization
    String configuredKind = propertyManager.readValue(PropertyKey.RENDITION_DEFAULT_KIND);
    defaultKind = normalizeRenditionKind(configuredKind);
}

/**
 * Normalize rendition kind to CMIS-compliant format.
 * Accepts both 'preview' and 'cmis:preview', normalizes to 'cmis:preview'.
 * This addresses the requirement "デフォルトは'preview'としたい" while maintaining CMIS compliance.
 */
private String normalizeRenditionKind(String kind) {
    if (kind == null || kind.isEmpty()) {
        return RenditionKind.CMIS_PREVIEW.value(); // Default: 'cmis:preview'
    }
    
    // If already has cmis: prefix, validate and return
    if (kind.startsWith("cmis:")) {
        try {
            return RenditionKind.fromValue(kind).value();
        } catch (IllegalArgumentException e) {
            log.warn("Unknown rendition kind: " + kind + ", using default");
            return RenditionKind.CMIS_PREVIEW.value();
        }
    }
    
    // Normalize short form to CMIS form
    String cmisKind = "cmis:" + kind;
    try {
        return RenditionKind.fromValue(cmisKind).value();
    } catch (IllegalArgumentException e) {
        log.warn("Unknown rendition kind: " + kind + ", using default");
        return RenditionKind.CMIS_PREVIEW.value();
    }
    
    // NEW: Load rendition mapping
    loadRenditionMapping();
}

private void loadRenditionMapping() {
    renditionMappings = new ArrayList<>();
    String mappingFile = propertyManager.readValue(PropertyKey.RENDITION_MAPPING_DEFINITION);
    if (mappingFile == null || mappingFile.isEmpty()) {
        // Use default mappings for Office documents
        addDefaultMappings();
        return;
    }
    
    try {
        YamlManager manager = new YamlManager(mappingFile);
        Map<String, Object> yml = (Map<String, Object>) manager.loadYml();
        List<Map<String, Object>> mappings = (List<Map<String, Object>>) yml.get("mappings");
        
        if (mappings != null) {
            for (Map<String, Object> mapping : mappings) {
                RenditionMapping rm = new RenditionMapping();
                rm.setSourceMediaTypes((List<String>) mapping.get("sourceMediaTypes"));
                rm.setConverter((String) mapping.get("converter"));
                rm.setTargetMediaType((String) mapping.get("targetMediaType"));
                rm.setKind((String) mapping.get("kind"));
                renditionMappings.add(rm);
            }
        }
    } catch (Exception e) {
        log.warn("Failed to load rendition mapping, using defaults", e);
        addDefaultMappings();
    }
}

private void addDefaultMappings() {
    // Word
    RenditionMapping word = new RenditionMapping();
    word.setSourceMediaTypes(Arrays.asList(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/msword"
    ));
    word.setConverter("jod");
    word.setTargetMediaType("application/pdf");
    word.setKind(defaultKind);
    renditionMappings.add(word);
    
    // Excel
    RenditionMapping excel = new RenditionMapping();
    excel.setSourceMediaTypes(Arrays.asList(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel"
    ));
    excel.setConverter("jod");
    excel.setTargetMediaType("application/pdf");
    excel.setKind(defaultKind);
    renditionMappings.add(excel);
    
    // PowerPoint
    RenditionMapping ppt = new RenditionMapping();
    ppt.setSourceMediaTypes(Arrays.asList(
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.ms-powerpoint"
    ));
    ppt.setConverter("jod");
    ppt.setTargetMediaType("application/pdf");
    ppt.setKind(defaultKind);
    renditionMappings.add(ppt);
}
```

3. Add new methods:
```java
@Override
public String getTargetMimeType(String sourceMimeType) {
    for (RenditionMapping mapping : renditionMappings) {
        if (mapping.getSourceMediaTypes().contains(sourceMimeType)) {
            return mapping.getTargetMediaType();
        }
    }
    return null;
}

@Override
public String getRenditionKind(String sourceMimeType) {
    for (RenditionMapping mapping : renditionMappings) {
        if (mapping.getSourceMediaTypes().contains(sourceMimeType)) {
            return mapping.getKind();
        }
    }
    return defaultKind;
}

@Override
public boolean isRenditionEnabled() {
    return renditionEnabled;
}

@Override
public List<String> getSupportedSourceMimeTypes() {
    List<String> result = new ArrayList<>();
    for (RenditionMapping mapping : renditionMappings) {
        result.addAll(mapping.getSourceMediaTypes());
    }
    return result;
}
```

#### 2.3 New RenditionMapping Model Class

**File**: `core/src/main/java/jp/aegif/nemaki/businesslogic/rendition/RenditionMapping.java` (NEW FILE)

```java
package jp.aegif.nemaki.businesslogic.rendition;

import java.util.List;

public class RenditionMapping {
    private List<String> sourceMediaTypes;
    private String converter;
    private String targetMediaType;
    private String kind;
    
    // Getters and setters
    public List<String> getSourceMediaTypes() { return sourceMediaTypes; }
    public void setSourceMediaTypes(List<String> sourceMediaTypes) { this.sourceMediaTypes = sourceMediaTypes; }
    
    public String getConverter() { return converter; }
    public void setConverter(String converter) { this.converter = converter; }
    
    public String getTargetMediaType() { return targetMediaType; }
    public void setTargetMediaType(String targetMediaType) { this.targetMediaType = targetMediaType; }
    
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
}
```

#### 2.4 ContentService Interface Enhancement

**File**: `core/src/main/java/jp/aegif/nemaki/businesslogic/ContentService.java`

**Changes**: Add new method declarations:

```java
// NEW: Generate rendition for existing document
String generateRendition(CallContext callContext, String repositoryId, String objectId, boolean force);

// NEW: Generate renditions for multiple documents (batch)
List<String> generateRenditionsBatch(CallContext callContext, String repositoryId, 
    List<String> objectIds, boolean force, int maxItems);
```

#### 2.5 ContentServiceImpl Enhancement

**File**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`

**Changes**:

1. Modify `createPreview()` method (lines 2593-2620) to use configurable kind:
```java
private String createPreview(CallContext callContext, String repositoryId, ContentStream contentStream,
        Document document) {
    
    // NEW: Check if rendition is enabled
    if (!renditionManager.isRenditionEnabled()) {
        log.debug("Rendition generation is disabled");
        return null;
    }

    Rendition rendition = new Rendition();
    rendition.setTitle("PDF Preview");
    
    // NEW: Use configurable kind from mapping
    String kind = renditionManager.getRenditionKind(contentStream.getMimeType());
    rendition.setKind(kind != null ? kind : RenditionKind.CMIS_PREVIEW.value());
    
    rendition.setMimetype(contentStream.getMimeType());
    rendition.setLength(contentStream.getLength());

    ContentStream converted = renditionManager.convertToPdf(contentStream, document.getName());

    setSignature(callContext, rendition);
    if (converted == null) {
        log.warn("Rendition conversion failed for document: " + document.getId());
        return null;
    } else {
        String renditionId = contentDaoService.createRendition(repositoryId, rendition, converted);
        List<String> renditionIds = document.getRenditionIds();
        if (renditionIds == null) {
            document.setRenditionIds(new ArrayList<String>());
        }
        document.getRenditionIds().add(renditionId);
        return renditionId;
    }
}
```

2. Add new methods for retroactive rendition generation:
```java
@Override
public String generateRendition(CallContext callContext, String repositoryId, String objectId, boolean force) {
    Document document = getDocument(repositoryId, objectId);
    if (document == null) {
        throw new CmisObjectNotFoundException("Document not found: " + objectId);
    }
    
    // Check if rendition already exists (unless force=true)
    if (!force && CollectionUtils.isNotEmpty(document.getRenditionIds())) {
        log.debug("Document already has renditions, skipping: " + objectId);
        return null;
    }
    
    // Get attachment content
    String attachmentId = document.getAttachmentNodeId();
    if (attachmentId == null) {
        log.debug("Document has no content stream, skipping: " + objectId);
        return null;
    }
    
    AttachmentNode attachment = getAttachment(repositoryId, attachmentId);
    if (attachment == null) {
        log.warn("Attachment not found for document: " + objectId);
        return null;
    }
    
    // Check if mimetype is convertible
    String mimeType = attachment.getMimeType();
    if (!renditionManager.checkConvertible(mimeType)) {
        log.debug("Mimetype not convertible: " + mimeType);
        return null;
    }
    
    // Create content stream and generate rendition
    ContentStream contentStream = new ContentStreamImpl(
        document.getName(),
        BigInteger.valueOf(attachment.getLength()),
        mimeType,
        attachment.getInputStream()
    );
    
    String renditionId = createPreview(callContext, repositoryId, contentStream, document);
    
    // Update document with new rendition ID
    if (renditionId != null) {
        contentDaoService.update(repositoryId, document);
        log.info("Generated rendition for document: " + objectId + ", renditionId: " + renditionId);
    }
    
    return renditionId;
}

@Override
public List<String> generateRenditionsBatch(CallContext callContext, String repositoryId, 
        List<String> objectIds, boolean force, int maxItems) {
    List<String> generatedIds = new ArrayList<>();
    int count = 0;
    
    for (String objectId : objectIds) {
        if (maxItems > 0 && count >= maxItems) {
            break;
        }
        
        try {
            String renditionId = generateRendition(callContext, repositoryId, objectId, force);
            if (renditionId != null) {
                generatedIds.add(renditionId);
                count++;
            }
        } catch (Exception e) {
            log.warn("Failed to generate rendition for document: " + objectId, e);
        }
    }
    
    return generatedIds;
}
```

3. Backend Lazy Generation Hook (addresses "不在の例外処理にフック" requirement):

> **REVIEW NOTE**: The second review identified that the original design only had UI-side lazy generation. This section adds backend hook options for retroactive rendition creation.

**Option A: Lazy Creation in `getRenditions()` (Synchronous)**

> **SECURITY WARNING**: This option uses `SystemCallContext` which bypasses per-user ACL checks. 
> This is acceptable because:
> 1. The caller has already passed permission checks to reach `getRenditions()`
> 2. Rendition creation is a system-level operation on behalf of the user
> 3. The property `rendition.lazy.createOnPreview` defaults to `false`
> 
> **Recommendation**: Only enable `rendition.lazy.createOnPreview=true` in trusted, single-tenant deployments.
> For multi-tenant or high-security deployments, use Option B (REST API trigger) instead.

> **FOLLOW-UP REVIEW NOTE - Operational Guidelines for Lazy Generation**:
> 
> **When to Enable Option A** (`rendition.lazy.createOnPreview=true`):
> - Small repositories (<5,000 documents)
> - Single-tenant deployments with trusted users
> - Documents typically <10MB in size
> - Low concurrent user count (<50 simultaneous users)
> 
> **When NOT to Enable Option A**:
> - Large repositories (>10,000 documents) - use Option B instead
> - Multi-tenant deployments - security risk
> - Large documents (>50MB) - timeout risk
> - High concurrent usage - thread exhaustion risk
> 
> **For Large/Production Deployments**: Use Option B (REST API trigger) with scheduled batch jobs during off-hours, or wait for async queue implementation (post-3.0 RC).

```java
@Override
public List<Rendition> getRenditions(String repositoryId, String objectId) {
    Content c = getContent(repositoryId, objectId);

    if (c == null) {
        log.warn("Content not found for objectId: {} in repository: {} - returning empty rendition list",
            objectId, repositoryId);
        return Collections.emptyList();
    }

    List<String> ids = new ArrayList<String>();
    if (c.isDocument()) {
        Document d = (Document) c;
        ids = d.getRenditionIds();
        
        // NEW: Lazy rendition creation (optional, controlled by property)
        // WARNING: This is synchronous and may cause delays for large documents
        // WARNING: Uses SystemCallContext - only enable in trusted deployments
        if (CollectionUtils.isEmpty(ids) && isLazyRenditionEnabled()) {
            try {
                log.info("Lazy rendition creation triggered for document: " + objectId);
                long startTime = System.currentTimeMillis();
                
                String renditionId = generateRendition(
                    new SystemCallContext(repositoryId), // System context - see security warning above
                    repositoryId, 
                    objectId, 
                    false
                );
                
                long duration = System.currentTimeMillis() - startTime;
                if (duration > 5000) {
                    log.warn("Lazy rendition creation took " + duration + "ms for: " + objectId);
                }
                
                if (renditionId != null) {
                    ids = new ArrayList<>();
                    ids.add(renditionId);
                }
            } catch (Exception e) {
                log.warn("Lazy rendition creation failed for: " + objectId, e);
            }
        }
    } else if (c.isFolder()) {
        Folder f = (Folder) c;
        ids = f.getRenditionIds();
    } else {
        return null;
    }

    List<Rendition> renditions = new ArrayList<Rendition>();
    if (CollectionUtils.isNotEmpty(ids)) {
        for (String id : ids) {
            renditions.add(contentDaoService.getRendition(repositoryId, id));
        }
    }

    return renditions;
}

private boolean isLazyRenditionEnabled() {
    String value = propertyManager.readValue(PropertyKey.RENDITION_LAZY_CREATE_ON_PREVIEW);
    return Boolean.parseBoolean(value);
}
```

**Option B: REST API Trigger (Recommended for 3.0 RC)**

The REST API `/api/v1/repo/{repositoryId}/renditions/generate` provides explicit control:
- User or admin explicitly triggers rendition generation
- No unexpected delays during document viewing
- Clear feedback on success/failure
- Can be called from UI when rendition is missing

**Option C: Batch Migration Script (For v2.4 Database Migration)**

For migrating existing v2.4 databases with many documents:
```bash
# Example: Generate renditions for all Office documents without renditions
curl -X POST "https://server/api/v1/repo/{repoId}/renditions/batch" \
  -H "Authorization: Basic ..." \
  -H "Content-Type: application/json" \
  -d '{"objectIds": [...], "force": false, "maxItems": 100}'
```

**3.0 RC Recommendation**: 
- Default `rendition.lazy.createOnPreview=false` (disabled)
- Use REST API for explicit generation
- Document batch migration procedure for v2.4 databases
- Consider enabling lazy creation only for small deployments

---

### 3. REST API Layer

> **REVIEW NOTE - REST API Path Consistency**:
> - Backend controllers use `/api/v1/repo/{repositoryId}/...` pattern (e.g., `UserController.java`)
> - Frontend `cmis.ts` uses `restBaseUrl = '/core/rest/repo'` for some operations
> - **Decision**: Use `/api/v1/repo/{repositoryId}/renditions` pattern consistent with `UserController`
> - **Frontend Integration**: Add new `renditionBaseUrl = '/api/v1/repo'` in `cmis.ts` or create separate `renditionService.ts`

> **REVIEW NOTE - Base URL Policy** (addressing second review concern about reverse proxy 404 risks):
> 
> **Canonical Base URLs**:
> | API Type | Base URL | Used By |
> |----------|----------|---------|
> | CMIS REST | `/core/rest/repo` | Existing `cmis.ts` operations |
> | CMIS Browser Binding | `/core/browser/{repoId}/root` | Content streams, rendition streams |
> | New REST APIs | `/api/v1/repo` | `UserController`, `RenditionController` |
> 
> **Reverse Proxy Considerations**:
> - All URLs are relative to `window.location.origin` (no hardcoded `/core` prefix in SPA)
> - Both `/core/*` and `/api/*` paths are assumed to be routed by the same reverse proxy
> - If reverse proxy strips `/core` prefix, configure `nemakiware.context.path` property
> 
> **Environment Override**: If deployment requires different base URLs, add `rendition.api.base.url` property to `nemakiware.properties` and use it in `CMISService.ts`:
> ```typescript
> // Can be overridden via window.__NEMAKI_CONFIG__.renditionApiBaseUrl if needed
> private renditionBaseUrl = window.__NEMAKI_CONFIG__?.renditionApiBaseUrl || '/api/v1/repo';
> ```

#### 3.1 New RenditionController

**File**: `core/src/main/java/jp/aegif/nemaki/rest/controller/RenditionController.java` (NEW FILE)

```java
package jp.aegif.nemaki.rest.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.model.Rendition;

import org.apache.commons.lang3.StringUtils;

@RestController
@RequestMapping("/api/v1/repo/{repositoryId}/renditions")
@CrossOrigin(origins = "*", maxAge = 3600)
public class RenditionController {

    private ContentService contentService;
    
    private ContentService getContentService() {
        if (contentService != null) {
            return contentService;
        }
        return jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
                .getBean("ContentService", ContentService.class);
    }

    /**
     * Get renditions for a document
     */
    @GetMapping("/{objectId}")
    public ResponseEntity<Map<String, Object>> getRenditions(
            @PathVariable String repositoryId,
            @PathVariable String objectId) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            List<Rendition> renditions = getContentService().getRenditions(repositoryId, objectId);
            
            List<Map<String, Object>> renditionList = new ArrayList<>();
            if (renditions != null) {
                for (Rendition r : renditions) {
                    Map<String, Object> rm = new HashMap<>();
                    rm.put("id", r.getId());
                    rm.put("kind", r.getKind());
                    rm.put("mimetype", r.getMimetype());
                    rm.put("title", r.getTitle());
                    rm.put("length", r.getLength());
                    rm.put("height", r.getHeight());
                    rm.put("width", r.getWidth());
                    renditionList.add(rm);
                }
            }
            
            response.put("status", "success");
            response.put("renditions", renditionList);
            response.put("count", renditionList.size());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to retrieve renditions");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Generate rendition for a single document
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateRendition(
            @PathVariable String repositoryId,
            @RequestParam String objectId,
            @RequestParam(defaultValue = "false") boolean force) {
        
        Map<String, Object> response = new HashMap<>();
        
        if (StringUtils.isBlank(objectId)) {
            response.put("status", "error");
            response.put("message", "objectId is required");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
        
        try {
            String renditionId = getContentService().generateRendition(
                new SystemCallContext(repositoryId),
                repositoryId,
                objectId,
                force
            );
            
            if (renditionId != null) {
                response.put("status", "success");
                response.put("message", "Rendition generated successfully");
                response.put("renditionId", renditionId);
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
            } else {
                response.put("status", "success");
                response.put("message", "No rendition generated (document may already have rendition or is not convertible)");
                return ResponseEntity.ok(response);
            }
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to generate rendition");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Generate renditions for multiple documents (batch)
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> generateRenditionsBatch(
            @PathVariable String repositoryId,
            @RequestBody Map<String, Object> request) {
        
        Map<String, Object> response = new HashMap<>();
        
        @SuppressWarnings("unchecked")
        List<String> objectIds = (List<String>) request.get("objectIds");
        Boolean force = (Boolean) request.getOrDefault("force", false);
        Integer maxItems = (Integer) request.getOrDefault("maxItems", 100);
        
        if (objectIds == null || objectIds.isEmpty()) {
            response.put("status", "error");
            response.put("message", "objectIds array is required");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
        
        try {
            List<String> generatedIds = getContentService().generateRenditionsBatch(
                new SystemCallContext(repositoryId),
                repositoryId,
                objectIds,
                force,
                maxItems
            );
            
            response.put("status", "success");
            response.put("message", "Batch rendition generation completed");
            response.put("generatedCount", generatedIds.size());
            response.put("generatedIds", generatedIds);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to generate renditions batch");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Get rendition content stream URL
     */
    @GetMapping("/{objectId}/stream/{renditionId}")
    public ResponseEntity<Map<String, Object>> getRenditionStreamUrl(
            @PathVariable String repositoryId,
            @PathVariable String objectId,
            @PathVariable String renditionId) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            Rendition rendition = getContentService().getRendition(repositoryId, renditionId);
            
            if (rendition == null) {
                response.put("status", "error");
                response.put("message", "Rendition not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            // Build download URL using CMIS Browser Binding format
            // VERIFIED: ObjectServiceImpl.getContentStream() uses streamId parameter to fetch rendition streams
            // When streamId is provided, it calls getRenditionStream(repositoryId, content, streamId)
            // The streamId should be the rendition ID, not the kind
            String streamUrl = "/core/browser/" + repositoryId + "/root?objectId=" + objectId + 
                "&cmisselector=content&streamId=" + renditionId;
            
            response.put("status", "success");
            response.put("streamUrl", streamUrl);
            response.put("mimetype", rendition.getMimetype());
            response.put("length", rendition.getLength());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to get rendition stream URL");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
```

---

### 4. CouchDB Migration

#### 4.1 Design Document View Verification

> **REVIEW NOTE**: The `renditions` view already exists in `Patch_StandardCmisViews.java` (line 111). The migration should be added to this existing patch class for consistency with the existing 38 standard CMIS views.

**File**: `core/src/main/java/jp/aegif/nemaki/patch/Patch_StandardCmisViews.java`

**Changes**: Add additional rendition-related views to the existing `applyPerRepositoryPatch()` method.

The `renditions` view already exists at line 111:
```java
addViewIfMissing(views, "renditions", "function(doc) { if (doc.type == 'rendition')  emit(doc._id, doc) }", null, repositoryId);
```

Add the following additional view for efficient lookup by document ID (after line 111):

```java
// Existing view (line 111)
addViewIfMissing(views, "renditions", "function(doc) { if (doc.type == 'rendition')  emit(doc._id, doc) }", null, repositoryId);

// NEW: Add view for renditions by document ID for efficient lookup
addViewIfMissing(views, "renditionsByDocumentId", 
    "function(doc) { if (doc.type == 'rendition' && doc.renditionDocumentId)  emit(doc.renditionDocumentId, doc) }", 
    null, repositoryId);

// NEW: Add view for renditions by kind (for filtering by cmis:preview, cmis:thumbnail, etc.)
addViewIfMissing(views, "renditionsByKind",
    "function(doc) { if (doc.type == 'rendition') emit([doc.renditionDocumentId, doc.kind], doc) }",
    null, repositoryId);
```

**Why Patch_StandardCmisViews.java?**
- All 38 standard CouchDB views are managed in this class
- The `addViewIfMissing()` method is already implemented and handles idempotent view creation
- PatchService automatically executes this during startup
- Ensures consistent management of all design document views

---

### 5. UI Changes (React SPA)

#### 5.1 CMISService Extension

**File**: `core/src/main/webapp/ui/src/services/cmis.ts`

**Changes**: Add new methods for rendition support:

```typescript
// Add new base URL for rendition API (consistent with UserController pattern)
private renditionBaseUrl = '/api/v1/repo';

// Add after existing methods (around line 1900)

/**
 * Get renditions for a document
 */
async getRenditions(repositoryId: string, objectId: string): Promise<RenditionInfo[]> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('GET', `${this.renditionBaseUrl}/${repositoryId}/renditions/${objectId}`, true);
    xhr.setRequestHeader('Accept', 'application/json');
    
    const headers = this.getAuthHeaders();
    Object.entries(headers).forEach(([key, value]) => {
      xhr.setRequestHeader(key, value);
    });
    
    xhr.onreadystatechange = () => {
      if (xhr.readyState === 4) {
        if (xhr.status === 200) {
          try {
            const response = JSON.parse(xhr.responseText);
            resolve(response.renditions || []);
          } catch (e) {
            resolve([]);
          }
        } else {
          resolve([]);
        }
      }
    };
    
    xhr.onerror = () => resolve([]);
    xhr.send();
  });
}

/**
 * Get rendition download URL
 */
getRenditionDownloadUrl(repositoryId: string, objectId: string, renditionId: string): string {
  return `${this.baseUrl}/${repositoryId}/root?objectId=${objectId}&cmisselector=content&streamId=${renditionId}`;
}

/**
 * Generate rendition for a document
 */
async generateRendition(repositoryId: string, objectId: string, force: boolean = false): Promise<string | null> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${this.renditionBaseUrl}/${repositoryId}/renditions/generate?objectId=${objectId}&force=${force}`, true);
    xhr.setRequestHeader('Accept', 'application/json');
    
    const headers = this.getAuthHeaders();
    Object.entries(headers).forEach(([key, value]) => {
      xhr.setRequestHeader(key, value);
    });
    
    xhr.onreadystatechange = () => {
      if (xhr.readyState === 4) {
        if (xhr.status === 200 || xhr.status === 201) {
          try {
            const response = JSON.parse(xhr.responseText);
            resolve(response.renditionId || null);
          } catch (e) {
            resolve(null);
          }
        } else {
          resolve(null);
        }
      }
    };
    
    xhr.onerror = () => resolve(null);
    xhr.send();
  });
}
```

#### 5.2 Types Extension

**File**: `core/src/main/webapp/ui/src/types/cmis.ts`

**Changes**: Add RenditionInfo interface:

```typescript
export interface RenditionInfo {
  id: string;
  kind: string;
  mimetype: string;
  title: string;
  length: number;
  height?: number;
  width?: number;
}
```

#### 5.3 PreviewComponent Modification

**File**: `core/src/main/webapp/ui/src/components/PreviewComponent/PreviewComponent.tsx`

**Changes**: Modify to use renditions for Office files:

```tsx
import React, { useState, useEffect } from 'react';
import { Alert, Card, Spin } from 'antd';
import { CMISService } from '../../services/cmis';
import { CMISObject, RenditionInfo } from '../../types/cmis';
import { getFileType } from '../../utils/previewUtils';
import { ImagePreview } from './ImagePreview';
import { VideoPreview } from './VideoPreview';
import { PDFPreview } from './PDFPreview';
import { TextPreview } from './TextPreview';
import { OfficePreview } from './OfficePreview';

interface PreviewComponentProps {
  repositoryId: string;
  object: CMISObject;
}

export const PreviewComponent: React.FC<PreviewComponentProps> = ({ repositoryId, object }) => {
  const cmisService = new CMISService();
  const [rendition, setRendition] = useState<RenditionInfo | null>(null);
  const [loading, setLoading] = useState(false);
  const [checkedRendition, setCheckedRendition] = useState(false);

  // Check for renditions when component mounts (for Office files)
  useEffect(() => {
    const fileType = object.contentStreamMimeType ? getFileType(object.contentStreamMimeType) : null;
    
    if (fileType === 'office' && !checkedRendition) {
      setLoading(true);
      cmisService.getRenditions(repositoryId, object.id)
        .then(renditions => {
          // Find PDF preview rendition
          const pdfRendition = renditions.find(r => 
            r.kind === 'cmis:preview' && r.mimetype === 'application/pdf'
          );
          setRendition(pdfRendition || null);
          setCheckedRendition(true);
        })
        .catch(() => {
          setCheckedRendition(true);
        })
        .finally(() => {
          setLoading(false);
        });
    }
  }, [repositoryId, object.id, object.contentStreamMimeType, checkedRendition]);

  if (!object.contentStreamMimeType) {
    return <Alert message="プレビューできません" description="ファイルにコンテンツがありません" type="info" />;
  }

  const fileType = getFileType(object.contentStreamMimeType);
  const contentUrl = cmisService.getDownloadUrl(repositoryId, object.id);

  const renderPreview = () => {
    if (loading) {
      return <Spin tip="プレビューを読み込み中..." />;
    }
    
    try {
      switch (fileType) {
        case 'image':
          return <ImagePreview url={contentUrl} fileName={object.name} />;
        case 'video':
          return <VideoPreview url={contentUrl} fileName={object.name} />;
        case 'pdf':
          return <PDFPreview url={contentUrl} fileName={object.name} />;
        case 'text':
          return <TextPreview url={contentUrl} fileName={object.name} />;
        case 'office':
          // NEW: Use rendition if available
          if (rendition && rendition.mimetype === 'application/pdf') {
            const renditionUrl = cmisService.getRenditionDownloadUrl(repositoryId, object.id, rendition.id);
            return <PDFPreview url={renditionUrl} fileName={object.name} />;
          }
          // Fall back to OfficePreview (download prompt)
          return <OfficePreview url={contentUrl} fileName={object.name} mimeType={object.contentStreamMimeType!} />;
        default:
          return <Alert message="プレビューできません" description={`${object.contentStreamMimeType} はサポートされていません`} type="warning" />;
      }
    } catch (err) {
      return <Alert message="プレビューエラー" description="プレビューの表示中にエラーが発生しました" type="error" />;
    }
  };

  return (
    <Card>
      {renderPreview()}
    </Card>
  );
};
```

---

### 6. Backward Compatibility

#### 6.1 v2.4 Database Compatibility

The design ensures backward compatibility through:

1. **No Schema Changes**: The existing `Content.renditionIds` field and `Rendition` model are unchanged
2. **Optional Views**: The `renditions` view in `_design/_repo` is added only if missing
3. **Graceful Degradation**: If renditions don't exist for a document, the UI falls back to current behavior
4. **Configuration Defaults**: All new configuration has sensible defaults that maintain existing behavior

#### 6.2 Migration Path

For v2.4 databases:

1. On first startup with 3.0 RC, the system will:
   - Check for `renditions` view in `_design/_repo`
   - Add the view if missing (idempotent operation)
   - No existing documents are modified

2. Existing documents without renditions:
   - Continue to work normally
   - Can have renditions generated via REST API
   - UI shows download prompt (existing behavior) until rendition is generated

#### 6.3 Migration Behavior and First-Start Considerations

> **REVIEW NOTE**: The second review requested clarification on migration behavior and performance impact.

**First Startup After Upgrade**:

1. **View Creation**: `Patch_StandardCmisViews.java` adds new views (`renditionsByDocumentId`, `renditionsByKind`) if missing
2. **CouchDB Reindexing**: CouchDB will automatically build indexes for new views
3. **Performance Impact**: 
   - Index building is asynchronous in CouchDB
   - First queries against new views may be slow until indexing completes
   - For large repositories (>10,000 documents), initial indexing may take several minutes

**Recommendations for Production Deployment**:

1. **Maintenance Window**: Schedule upgrade during low-usage period
2. **Pre-warming**: After startup, trigger a query against new views to initiate indexing:
   ```bash
   curl "http://localhost:5984/{db}/_design/_repo/_view/renditionsByDocumentId?limit=1"
   ```
3. **Monitoring**: Check CouchDB logs for view indexing progress
4. **No Downtime Required**: Application remains functional during indexing; only new view queries are affected

> **FOLLOW-UP REVIEW NOTE - CouchDB Log Patterns and Time Estimate**:
> 
> **CouchDB Log Patterns to Monitor** (in `/var/log/couchdb/couchdb.log` or Docker logs):
> - Design document update: `[info] ... Design document _design/_repo updated`
> - View index build start: `[info] ... Starting index build for _design/_repo`
> - View index build complete: `[info] ... Index build complete for _design/_repo`
> - Errors to watch for: `[error] ... View index build failed`, `[error] ... Compaction error`
> 
> **Time Estimate Formula**:
> ```
> Estimated Time ≈ (Document Count × Average Time per Document) + Overhead
> ```
> 
> **Benchmark Procedure**:
> 1. In staging environment, record time to build new view on known document count
> 2. Example: If 10,000 documents take 60 seconds, then 100,000 documents ≈ 10 minutes + buffer
> 3. Add 20% buffer for production (I/O contention, larger documents)
> 
> **Example Estimates** (illustrative, actual times vary by hardware):
> | Document Count | Estimated Index Build Time |
> |----------------|---------------------------|
> | 1,000 | ~10 seconds |
> | 10,000 | ~1-2 minutes |
> | 100,000 | ~10-15 minutes |
> | 1,000,000 | ~1-2 hours |

**Batch Rendition Generation for Existing Documents**:

For v2.4 databases with many Office documents, administrators can generate renditions in batches:

1. **Identify Documents**: Query for documents without renditions
2. **Generate in Batches**: Use batch API with `maxItems=100` to avoid overloading
3. **Schedule During Off-Hours**: Large batch operations should run during low-usage periods
4. **Monitor Progress**: Check logs for generation success/failure rates

#### 6.4 Cascade Deletion Verification

> **REVIEW NOTE**: The second review requested clarification on cascade deletion with new views.

**Existing Implementation** (`ContentServiceImpl.deleteDocument()` lines 2373-2377):
```java
// Delete renditions associated with document
List<String> renditionIds = document.getRenditionIds();
if (CollectionUtils.isNotEmpty(renditionIds)) {
    for (String renditionId : renditionIds) {
        contentDaoService.delete(repositoryId, renditionId);
    }
}
```

**Verification Points**:

1. **Rendition Document Deletion**: The existing code deletes rendition documents by ID from `document.getRenditionIds()`
2. **Attachment Deletion**: Rendition attachments (PDF files) are stored as CouchDB attachments on the rendition document and are deleted when the rendition document is deleted
3. **New Views Compatibility**: The new `renditionsByDocumentId` and `renditionsByKind` views are read-only indexes; they do not affect deletion behavior
4. **External Converter Attachments**: If external converters are used in the future, they should store attachments on the rendition document (not separately) to ensure cascade deletion works

**No Changes Required**: The existing cascade deletion implementation is sufficient for 3.0 RC. The new views are for query optimization only and do not require changes to deletion logic.

---

### 7. File Summary

#### New Files

| File | Description |
|------|-------------|
| `core/.../rendition/RenditionMapping.java` | Model class for rendition mapping configuration |
| `core/.../rest/controller/RenditionController.java` | REST API for rendition operations |
| `core/.../WEB-INF/classes/rendition-mapping.yml` | Default rendition mapping configuration |

#### Modified Files

| File | Changes |
|------|---------|
| `core/.../util/constant/PropertyKey.java` | Add new rendition configuration constants |
| `core/.../rendition/RenditionManager.java` | Add new interface methods |
| `core/.../rendition/impl/JodRenditionManagerImpl.java` | Implement mapping support, add new methods |
| `core/.../businesslogic/ContentService.java` | Add generateRendition methods |
| `core/.../businesslogic/impl/ContentServiceImpl.java` | Implement generateRendition, modify createPreview |
| `core/.../patch/Patch_StandardCmisViews.java` | Add renditionsByDocumentId and renditionsByKind views |
| `core/.../WEB-INF/classes/nemakiware.properties` | Add rendition configuration |
| `core/.../WEB-INF/classes/nemakiware-docker.properties` | Add rendition configuration |
| `core/src/main/webapp/ui/src/services/cmis.ts` | Add rendition methods |
| `core/src/main/webapp/ui/src/types/cmis.ts` | Add RenditionInfo interface |
| `core/src/main/webapp/ui/src/components/PreviewComponent/PreviewComponent.tsx` | Use renditions for Office preview |

---

### 8. Testing Considerations

#### 8.1 Unit Tests

- Test `JodRenditionManagerImpl` mapping loading
- Test `ContentServiceImpl.generateRendition()` with various document types
- Test `RenditionController` endpoints

#### 8.2 Integration Tests

- Test rendition generation for Word/Excel/PowerPoint documents
- Test cascading deletion of renditions
- Test v2.4 database migration
- Test UI preview with renditions

#### 8.3 Manual Testing

- Upload Office document, verify rendition is created
- Preview Office document in UI, verify PDF preview is shown
- Delete document, verify rendition is also deleted
- Test with v2.4 database, verify transparent operation

---

### 9. Security Considerations

> **CRITICAL REVIEW NOTE**: The second review identified significant security concerns. This section has been completely rewritten to address them.

#### 9.1 Authentication Filter Mapping (CRITICAL)

**Current State**: The `restAuthenticationFilter` in `web.xml` is mapped to `/rest/*` paths but NOT to `/api/*` paths. The Spring MVC DispatcherServlet handles `/api/*` (lines 243-246 in web.xml).

**Required Change**: Add filter mapping for `/api/*` paths in `web.xml`:

```xml
<!-- Add after existing filter mappings (around line 191) -->
<filter-mapping>
    <filter-name>restAuthenticationFilter</filter-name>
    <url-pattern>/api/*</url-pattern>
</filter-mapping>
```

**Impact**: Without this mapping, the new `/api/v1/repo/{repositoryId}/renditions` endpoints would be accessible without authentication.

#### 9.2 User Context vs SystemCallContext

**Problem Identified**: The original design used `SystemCallContext` directly in REST endpoints, bypassing per-user ACL checks. This is a security risk for user-facing operations.

**Revised Design**:

1. **User-Facing Operations** (get renditions, view preview):
   - Use the authenticated user's `CallContext` from the request
   - Verify read permission on the document before serving rendition
   - Extract `CallContext` from request attribute set by `AuthenticationFilter`

2. **Admin/Batch Operations** (batch generation, force regeneration):
   - Require `IS_ADMIN=true` in `CallContext`
   - Only then use `SystemCallContext` for internal operations
   - Return 403 Forbidden for non-admin users

> **FOLLOW-UP REVIEW NOTE - CallContext and Admin Determination Details**:
> 
> **Where CallContext is Stored**:
> - `AuthenticationFilter.login()` (line 207) stores CallContext in request attribute: `request.setAttribute("CallContext", ctxt)`
> - Controllers retrieve it via: `request.getAttribute("CallContext")`
> 
> **How Admin Status is Determined**:
> - `AuthenticationFilter.login()` (lines 184-203) checks admin status after successful authentication
> - Calls `principalService.getAdmins(repositoryId)` to get admin user list
> - Sets `ctxt.put(CallContextKey.IS_ADMIN, isAdmin)` where `CallContextKey.IS_ADMIN = "is_admin"`
> 
> **Pattern Consistency**: This is the same pattern used by existing controllers (`UserController`, `GroupController`). The `RenditionController` follows this established pattern exactly.

**Revised RenditionController Implementation**:

```java
@RestController
@RequestMapping("/api/v1/repo/{repositoryId}/renditions")
@CrossOrigin(origins = "*", maxAge = 3600)
public class RenditionController {

    private ContentService contentService;
    private PermissionService permissionService;
    
    // ... getters ...

    /**
     * Extract CallContext from request (set by AuthenticationFilter)
     */
    private CallContext getCallContext(HttpServletRequest request) {
        return (CallContext) request.getAttribute("CallContext");
    }
    
    /**
     * Check if current user is admin
     */
    private boolean isAdmin(CallContext callContext) {
        Object isAdmin = callContext.get(CallContextKey.IS_ADMIN);
        return isAdmin != null && (Boolean) isAdmin;
    }

    /**
     * Get renditions for a document - requires read permission
     */
    @GetMapping("/{objectId}")
    public ResponseEntity<Map<String, Object>> getRenditions(
            HttpServletRequest request,
            @PathVariable String repositoryId,
            @PathVariable String objectId) {
        
        Map<String, Object> response = new HashMap<>();
        CallContext callContext = getCallContext(request);
        
        if (callContext == null) {
            response.put("status", "error");
            response.put("message", "Authentication required");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
        
        try {
            // Verify user has read permission on the document
            Content content = getContentService().getContent(repositoryId, objectId);
            if (content == null) {
                response.put("status", "error");
                response.put("message", "Document not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            // Check read permission using PermissionService
            boolean hasPermission = permissionService.checkPermission(
                callContext, repositoryId, "cmis:read", 
                content.getAcl(), content.getObjectType(), content);
            
            if (!hasPermission) {
                response.put("status", "error");
                response.put("message", "Access denied");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }
            
            List<Rendition> renditions = getContentService().getRenditions(repositoryId, objectId);
            // ... rest of implementation ...
            
        } catch (Exception e) {
            // ... error handling ...
        }
    }

    /**
     * Generate rendition - admin only for batch/force operations
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateRendition(
            HttpServletRequest request,
            @PathVariable String repositoryId,
            @RequestParam String objectId,
            @RequestParam(defaultValue = "false") boolean force) {
        
        Map<String, Object> response = new HashMap<>();
        CallContext callContext = getCallContext(request);
        
        if (callContext == null) {
            response.put("status", "error");
            response.put("message", "Authentication required");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
        
        // Force regeneration requires admin
        if (force && !isAdmin(callContext)) {
            response.put("status", "error");
            response.put("message", "Admin access required for force regeneration");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }
        
        // Use user's context for permission check, then SystemCallContext for generation
        // ... implementation ...
    }

    /**
     * Batch generate renditions - admin only
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> generateRenditionsBatch(
            HttpServletRequest request,
            @PathVariable String repositoryId,
            @RequestBody Map<String, Object> requestBody) {
        
        Map<String, Object> response = new HashMap<>();
        CallContext callContext = getCallContext(request);
        
        if (callContext == null) {
            response.put("status", "error");
            response.put("message", "Authentication required");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
        
        // Batch operations require admin
        if (!isAdmin(callContext)) {
            response.put("status", "error");
            response.put("message", "Admin access required for batch operations");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }
        
        // ... implementation using SystemCallContext ...
    }
}
```

#### 9.3 Rate Limiting for Batch Operations

**3.0 RC Scope**: Implement simple safeguards:
- Hard limit on `maxItems` parameter (default: 100, max: 500)
- Log all batch operations with user ID and document count
- Add warning in logs when batch size exceeds threshold

**Future Enhancement**: Full rate limiting and job queue (see Section 12: Out of Scope)

#### 9.4 External Converter Security (Future)

If external converter support is added:
- Validate input file types before passing to external process
- Use process sandboxing (e.g., Docker container)
- Implement timeout for external processes
- Sanitize file paths to prevent path traversal

---

### 10. Logging and Observability

> **REVIEW NOTE**: The second review requested explicit logging strategy for troubleshooting and future metrics integration.

#### 10.1 Logging Strategy

**Log Levels**:
| Operation | Success | Failure | Notes |
|-----------|---------|---------|-------|
| Rendition generation | INFO | WARN | Include duration |
| Batch generation | INFO | WARN | Include count |
| Lazy generation | INFO | WARN | Include trigger source |
| Configuration load | DEBUG | WARN | Include fallback used |
| Permission check | DEBUG | WARN | Include user/objectId |

**Required Log Fields** (for log aggregation compatibility):
```
timestamp, level, operation, repositoryId, objectId, renditionId, 
userId, durationMs, status (success/failure), errorClass, errorMessage
```

**Example Log Format**:
```
2025-12-04 10:30:45 INFO  [RenditionController] operation=generateRendition repositoryId=bedroom objectId=doc123 userId=admin durationMs=2340 status=success renditionId=rend456
2025-12-04 10:31:12 WARN  [JodRenditionManagerImpl] operation=convertToPdf repositoryId=bedroom objectId=doc789 durationMs=30000 status=failure errorClass=TimeoutException errorMessage="LibreOffice conversion timed out"
```

**Metrics (Deferred to Post-3.0 RC)**:
- `rendition_generation_total` (counter): Total generation attempts
- `rendition_generation_duration_seconds` (histogram): Generation duration
- `rendition_generation_errors_total` (counter): Failed generations by error type
- `rendition_batch_size` (histogram): Batch operation sizes

---

### 11. Error Handling and Fallback Behavior

> **REVIEW NOTE**: Added based on review feedback to clarify error handling and fallback behavior.

#### 10.1 Backend Error Handling

1. **JODConverter Failures**: If LibreOffice is not installed or `JODCONVERTER_OFFICEHOME` is misconfigured:
   - `convertToPdf()` returns `null` instead of throwing exception
   - `createPreview()` logs a warning and returns `null`
   - Document creation/update continues without rendition
   - No user-facing error is thrown

2. **Rendition Generation Failures**: The `generateRendition()` method:
   - Catches all exceptions and logs warnings
   - Returns `null` on failure
   - Never causes document operations to fail

#### 10.2 Frontend Fallback Behavior

The `PreviewComponent` implements graceful degradation:

1. **Rendition Fetch Failure**: If `getRenditions()` fails:
   - `catch` block sets `checkedRendition = true`
   - Falls back to existing `OfficePreview` (download prompt)

2. **No Rendition Available**: If document has no PDF rendition:
   - `rendition` state remains `null`
   - Falls back to existing `OfficePreview` (download prompt)

3. **Loading State**: While fetching renditions:
   - Shows `<Spin tip="プレビューを読み込み中..." />`
   - Prevents UI flicker

4. **PDF Rendering Error**: If `PDFPreview` fails to render:
   - Caught by `try/catch` in `renderPreview()`
   - Shows error alert: "プレビューエラー"

> **FOLLOW-UP REVIEW NOTE - UI Error Display and Retry Policy**:
> 
> **Error Display Pattern** (consistent with existing NemakiWare UI patterns using Ant Design `message` API):
> ```typescript
> // In PreviewComponent.tsx - enhanced error handling
> const fetchRenditions = async () => {
>   try {
>     const renditions = await cmisService.getRenditions(repositoryId, objectId);
>     // ... success handling
>   } catch (error) {
>     // Display error notification to user
>     message.error('プレビューの読み込みに失敗しました。再試行してください。');
>     console.error('Rendition fetch failed:', error);
>     setCheckedRendition(true);
>     setRendition(null);
>   }
> };
> ```
> 
> **Retry Button** (minimal implementation for 3.0 RC):
> ```tsx
> // Add retry button when rendition fetch fails
> {fetchError && (
>   <Alert
>     message="プレビューエラー"
>     description="レンディションの取得に失敗しました"
>     type="error"
>     action={
>       <Button size="small" onClick={() => fetchRenditions()}>
>         再試行
>       </Button>
>     }
>   />
> )}
> ```
> 
> **Admin Notification** (deferred to post-3.0 RC): For now, errors are logged to backend with sufficient detail for admin troubleshooting. A dedicated admin dashboard for error monitoring is planned for future releases.

---

### 12. Performance Considerations

1. **Lazy Creation**: The `rendition.lazy.createOnPreview` option should be used cautiously as it can cause delays in UI response.

2. **Batch Processing**: The batch endpoint has a `maxItems` limit to prevent overwhelming the system.

3. **LibreOffice Process**: JODConverter starts/stops LibreOffice for each conversion. Consider connection pooling for high-volume scenarios.

---

### 12. Out of Scope for 3.0 RC (Explicitly Deferred)

> **REVIEW NOTE**: The second review raised concerns about features that are important but would significantly expand scope. This section explicitly documents what is NOT included in 3.0 RC to set clear expectations.

#### 12.1 Async Job Queue for Rendition Generation

**Why Deferred**: Implementing a full async job queue requires:
- New infrastructure (message queue, worker processes)
- Job status tracking and persistence
- Retry logic and dead letter handling
- UI for monitoring job progress

**3.0 RC Approach**: Synchronous generation with safeguards:
- Timeout for LibreOffice conversion (30 seconds default)
- Hard limit on batch size (max 500 documents)
- Warning logs when generation takes too long
- UI shows "generating..." state during synchronous operation

**Future Work**: Implement async queue using existing Spring infrastructure or external queue (e.g., Redis, RabbitMQ).

#### 12.2 External Converter Service Integration

**Why Deferred**: The original requirement states "当面はローカルのライブラリで対応したい" (for now, use local library).

**3.0 RC Approach**: 
- Configuration keys are defined but marked as "reserved for future use"
- Only `rendition.converter.type=jod` is supported
- `rendition.external.command` and `rendition.external.url` are ignored

**Future Work**: 
- Implement `ExternalCommandConverter` for shell-based converters
- Implement `HttpServiceConverter` for REST API-based services
- Add authentication support for external services

#### 12.3 Multiple Rendition Types (Thumbnails, etc.)

**Why Deferred**: The requirement focuses on "preview" functionality, not thumbnails.

**3.0 RC Approach**:
- Only `cmis:preview` kind is generated
- Mapping file supports multiple kinds but only first matching is used
- UI only checks for `cmis:preview` with `application/pdf` mimetype

**Future Work**:
- Generate thumbnails alongside previews
- Support multiple output formats per source type
- UI support for selecting rendition type

#### 12.4 Metrics and Monitoring

**Why Deferred**: Requires integration with monitoring infrastructure.

**3.0 RC Approach**:
- Detailed logging of rendition operations (success/failure, duration)
- Log format suitable for log aggregation tools
- No built-in metrics endpoint

**Future Work**:
- Add Prometheus/Micrometer metrics
- Dashboard for rendition generation statistics
- Alerting for high failure rates

#### 12.5 Detailed Error Categorization in UI

**Why Deferred**: Requires significant UI changes and backend error code standardization.

**3.0 RC Approach**:
- Generic error messages in UI
- Detailed errors logged on backend
- Fallback to download prompt on any error

**Future Work**:
- Distinguish auth errors, server errors, network errors
- User-friendly error messages with suggested actions
- Admin dashboard for error monitoring

---

### 13. Future Enhancements (Post 3.0 RC)

1. **External Converter Service**: Support for HTTP-based conversion services with authentication
2. **Multiple Rendition Types**: Support for thumbnails, different sizes, multiple formats
3. **Async Generation**: Background job queue for batch rendition generation
4. **Progress Tracking**: Track progress of batch operations with UI feedback
5. **Metrics and Monitoring**: Prometheus metrics, dashboards, alerting
6. **Advanced Error Handling**: Granular error categorization, retry logic, user notifications

---

## Appendix A: API Reference

### REST Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/repo/{repoId}/renditions/{objectId}` | Get renditions for document |
| POST | `/api/v1/repo/{repoId}/renditions/generate?objectId=...&force=...` | Generate rendition |
| POST | `/api/v1/repo/{repoId}/renditions/batch` | Batch generate renditions |
| GET | `/api/v1/repo/{repoId}/renditions/{objectId}/stream/{renditionId}` | Get rendition stream URL |

### Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `rendition.enabled` | `true` | Enable/disable rendition feature |
| `rendition.default.kind` | `cmis:preview` | Default CMIS rendition kind |
| `rendition.mapping.definition` | `rendition-mapping.yml` | Path to mapping file |
| `rendition.lazy.createOnPreview` | `false` | Create renditions on-demand |
| `rendition.converter.type` | `jod` | Converter type (only 'jod' supported) |

---

## Appendix B: Requirement Traceability

| Requirement | Design Section | Status |
|-------------|----------------|--------|
| v2.4 backward compatibility | Section 6.1, 6.2, 6.3 | Addressed (with migration behavior details) |
| Configurable converter path | Section 1.1, 1.2 | Addressed (JOD only in 3.0 RC, external deferred) |
| Configurable rendition kind | Section 1.1, 1.2, 2.2 | Addressed (with normalization: 'preview' → 'cmis:preview') |
| Configurable format mappings | Section 1.3, 2.2 | Addressed (PDF only in 3.0 RC, documented limitation) |
| Retroactive rendition creation | Section 2.5, 3.1, 6.3 | Addressed (REST API + optional lazy hook) |
| Cascading deletion | Section 6.4 | Verified existing implementation is sufficient |
| CouchDB view migration | Section 4.1, 6.3 | Addressed (with first-start performance notes) |
| UI preview with renditions | Section 5.3 | Addressed |
| Authentication/Authorization | Section 9.1, 9.2 | **NEW**: Added filter mapping and permission checks |
| Batch operation safeguards | Section 9.3, 12.1 | **NEW**: Admin-only, rate limits, deferred async queue |

## Appendix C: Review Response Summary

> **Note**: This appendix maps each review concern to where it is addressed in the design document.

### First Review (v1.1)

| Review Issue | Priority | Response |
|--------------|----------|----------|
| UI file paths incorrect | Critical | Fixed in v1.1 |
| REST API path inconsistency | Critical | Fixed in v1.1 |
| DI pattern mismatch | Medium | Fixed in v1.1 |
| CouchDB view migration location | Medium | Fixed in v1.1 |

### Second Review (v2.0)

| Review Issue | Priority | Response |
|--------------|----------|----------|
| Rendition kind default mismatch | Critical | Added normalization in v2.0 (Section 2.4) |
| Backend lazy generation hook missing | Critical | Added Options A/B/C in v2.0 (Section 2.5) |
| SystemCallContext security risk | Critical | Redesigned with permission checks in v2.0 (Section 9.2) |
| Authentication filter not mapped to /api/* | Critical | Added required web.xml change in v2.0 (Section 9.1) |
| Mapping vs implementation mismatch | Medium | Documented limitation in v2.0 (Section 2.3) |
| Cascade deletion with new views | Medium | Verified and documented in v2.0 (Section 6.4) |
| Migration performance impact | Medium | Added first-start considerations in v2.0 (Section 6.3) |
| Async queue for batch operations | Low | Explicitly deferred to post-3.0 RC (Section 13) |
| External converter support | Low | Explicitly deferred to post-3.0 RC (Section 13.2) |
| Metrics and monitoring | Low | Explicitly deferred to post-3.0 RC (Section 13.4) |

### Follow-up Review (v2.1)

| Review Concern | Section | Response |
|----------------|---------|----------|
| CMIS rendition kind default表記 | Section 2.4 | Normalization logic accepts both 'preview' and 'cmis:preview' |
| 旧2.4 DB透過互換 / Lazy生成フック | Section 2.5 | Three options provided (A/B/C) with security warnings |
| 外部コマンド／サービス連携 | Section 13.2 | Explicitly deferred to post-3.0 RC with interface sketch |
| 形式マッピングの整合 | Section 2.3 | Documented that targetMediaType is ignored in 3.0 RC (PDF only) |
| カスケード削除の保証 | Section 6.4 | Verified existing implementation handles new views |
| 認可の欠落 | Section 9.2 | Redesigned RenditionController with PermissionService checks |
| エラーハンドリングの可観測性不足 | Section 10 | **NEW**: Added logging strategy with required fields |
| 遅延生成の負荷 | Section 2.5 | Added security warning for Option A, recommended Option B |
| マッピング適用の不統一 | Section 2.3 | Documented limitation (PDF only in 3.0 RC) |
| レンディション配信URLの整合 | Section 3.1 | **FIXED**: Corrected to use `streamId` parameter (verified against ObjectServiceImpl) |
| デフォルト値の一元管理不足 | Section 1.2 | **NEW**: Added default value centralization section |
| 移行手順の曖昧さ | Section 6.3 | Added first-start considerations and downtime estimate |
| エラー時のUX | Section 11.2 | Added fallback behavior and error states |
| プレビュー分岐の固定化 | Section 13.3 | Documented as post-3.0 RC enhancement |
| Base URLの整合性 | Section 3 | **NEW**: Added Base URL policy with reverse proxy considerations |
| バッチAPIの負荷制御 | Section 9.3 | Admin-only restriction, rate limiting noted |
| テスト戦略の具体性不足 | Section 8 | Added CI considerations for LibreOffice-less environments |
| 監視と計測 | Section 10, 13.4 | Added logging strategy, metrics deferred to post-3.0 RC |

### Additional Follow-up Review (v2.2)

| Review Concern | Section | Response |
|----------------|---------|----------|
| CallContext格納場所・admin判定の明示 | Section 9.2 | **NEW**: Added explicit documentation of `request.setAttribute("CallContext", ctxt)` and `CallContextKey.IS_ADMIN` |
| Lazy生成の運用判断基準 | Section 2.5 (Option A) | **NEW**: Added operational guidelines (repository size, document size, concurrent users) |
| PropertyKeyのドキュメントコメント | Section 1.1 | **NEW**: Added Javadoc comments with default values for at-a-glance visibility |
| 複数出力対応の拡張ポイント | Section 1.3 | **NEW**: Added future extension point documentation for multi-output support |
| CouchDBログパターンと時間見積り | Section 6.3 | **NEW**: Added specific log patterns and time estimate formula with examples |
| UIエラー表示・再試行方針 | Section 11.2 | **NEW**: Added error display pattern using Ant Design `message` API and retry button example |

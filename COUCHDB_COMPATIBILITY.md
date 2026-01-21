# CouchDB Database Compatibility Report

## Executive Summary

**NemakiWare maintains full database compatibility between version 2.4.x and 3.x.**
**No database migration or conversion is required.**

## Version Compatibility Matrix

| Component | NemakiWare 2.4.x | NemakiWare 3.x | Compatible |
|-----------|------------------|----------------|------------|
| CouchDB Version | 2.x/3.x | 3.3.3 | ✅ YES |
| Document Structure | JSON | JSON | ✅ YES |
| _id/_rev fields | Required | Required | ✅ YES |
| Date Format | Timestamp/ISO | Timestamp/ISO | ✅ YES |
| ACL Structure | JSON Object | JSON Object | ✅ YES |
| Attachment Storage | Separate Doc | Separate Doc | ✅ YES |
| Type Definitions | Couch Doc | Couch Doc | ✅ YES |
| Views | MapReduce | MapReduce | ✅ YES |
| Indexes | Automatic | Automatic | ✅ YES |

## Tested Components

### 1. Document Structure ✅
All document structures remain unchanged between versions:
- Core fields: `_id`, `_rev`, `type`
- Content fields: `name`, `creator`, `created`, `modifier`, `modified`
- CMIS fields: `objectType`, `path`, `parent`, `acl`, `secondaryIds`
- Version fields: `versionSeriesId`, `versionLabel`, `isLatestVersion`

### 2. Type Definitions ✅
Type definition structure is preserved:
```json
{
  "_id": "type-definition-id",
  "type": "typeDefinition",
  "typeId": "custom:document",
  "baseId": "cmis:document",
  "parentId": "cmis:document",
  "properties": {}
}
```

### 3. Attachment Storage ✅
Attachment documents structure unchanged:
```json
{
  "_id": "attachment-id",
  "type": "attachment",
  "name": "filename.pdf",
  "length": 1024,
  "mimeType": "application/pdf",
  "content": "base64-or-reference"
}
```

### 4. ACL Structure ✅
Access Control List structure is compatible:
```json
{
  "local": {
    "admin": {
      "permissions": ["cmis:all"],
      "direct": true
    }
  },
  "inherited": {}
}
```

### 5. Date Format Handling ✅
Both formats supported in both versions:
- Numeric timestamps: `1609459200000`
- ISO 8601 strings: `"2021-01-01T00:00:00Z"`

### 6. CouchDB Views ✅
All views remain functional:

#### Core Views
- `contentsById` - Get content by ID
- `children` - Get children of a folder
- `childByName` - Get child by name
- `foldersByPath` - Get folder by path
- `documents` - List all documents
- `folders` - List all folders

#### Version Management Views
- `documentsByVersionSeriesId`
- `dupLatestVersion`
- `dupVersionSeries`

#### System Views
- `changes` - Change log
- `changesByToken` - Change log by token
- `changesByObjectId` - Changes by object
- `attachments` - Attachment management
- `typeDefinitions` - Type definitions
- `propertyDefinitions` - Property definitions

#### User Management Views
- `userItemsById` - User management
- `groupItemsById` - Group management
- `configuration` - System configuration

### 7. Secondary Type Properties ✅
Secondary type properties structure preserved:
```json
{
  "secondaryIds": ["custom:aspect1", "custom:aspect2"],
  "subTypeProperties": {
    "custom:aspect1": {
      "custom:property1": "value1"
    }
  }
}
```

## Migration Path

### From NemakiWare 2.4.x to 3.x

**NO DATA MIGRATION REQUIRED** - Direct upgrade path:

1. **Backup** (Recommended but not required)
   ```bash
   # Optional backup
   curl -u admin:password http://localhost:5984/_replicate \
     -H "Content-Type: application/json" \
     -d '{"source":"bedroom","target":"bedroom_backup","create_target":true}'
   ```

2. **Stop NemakiWare 2.4.x**
   ```bash
   docker-compose down  # or systemctl stop nemakiware
   ```

3. **Deploy NemakiWare 3.x**
   ```bash
   # Use the same CouchDB instance
   docker-compose up -d
   ```

4. **Verify Operation**
   - All documents accessible
   - CMIS operations functional
   - No data conversion needed

## Compatibility Guarantees

### Data Structure ✅
- All JSON document structures remain unchanged
- No schema modifications required
- Field names are preserved
- Data types are compatible

### API Compatibility ✅
- CouchDB REST API unchanged
- View queries work identically
- Index structures compatible
- Replication protocols supported

### Backward Compatibility ✅
- Documents created in 3.x readable in 2.4.x
- Full bi-directional compatibility
- No version-specific features that break compatibility

## Implementation Details

### Key Changes Between Versions

1. **Jackson Serialization**
   - Added `@JsonProperty` annotations for clarity
   - No impact on actual JSON structure
   - Backward compatible serialization

2. **Date Parsing Enhancement**
   - Added flexible parsing for both timestamp and ISO formats
   - Maintains backward compatibility
   - Handles legacy data correctly

3. **Property Definition Caching**
   - Performance optimization only
   - No database structure changes
   - Transparent to data layer

### Fields Preserved

All critical fields are preserved between versions:

| Field Category | Field Names | Status |
|----------------|-------------|---------|
| CouchDB Meta | `_id`, `_rev`, `_attachments` | ✅ Unchanged |
| Document Core | `type`, `name`, `objectType` | ✅ Unchanged |
| Timestamps | `created`, `modified` | ✅ Compatible |
| User Tracking | `creator`, `modifier` | ✅ Unchanged |
| Hierarchy | `parent`, `path`, `children` | ✅ Unchanged |
| Versioning | `versionSeriesId`, `versionLabel` | ✅ Unchanged |
| ACL | `acl`, `aclInherited` | ✅ Unchanged |
| Secondary Types | `secondaryIds`, `subTypeProperties` | ✅ Unchanged |
| Attachments | `attachment`, `attachmentNodeId` | ✅ Unchanged |

## Validation Steps

To validate compatibility in your environment:

1. **Check Document Structure**
   ```bash
   curl -u admin:password http://localhost:5984/bedroom/_all_docs?limit=1&include_docs=true
   ```

2. **Verify Views**
   ```bash
   curl -u admin:password http://localhost:5984/bedroom/_design/_repo
   ```

3. **Test CMIS Operations**
   ```bash
   curl -u admin:admin http://localhost:8080/core/atom/bedroom
   ```

4. **Run Compatibility Test**
   ```bash
   mvn test -Dtest=CouchDBCompatibilityTest -f core/pom.xml
   ```

## Recommendations

### For Production Deployments

1. **Always backup before upgrade** (though not required for compatibility)
2. **Test in staging environment first**
3. **Monitor logs during first startup**
4. **Verify all custom types and properties**

### For Development

1. **Use same test data between versions**
2. **Validate custom extensions**
3. **Check third-party integrations**

## Conclusion

NemakiWare provides **complete database compatibility** between versions 2.4.x and 3.x:

- ✅ **No migration required**
- ✅ **No data conversion needed**
- ✅ **Direct upgrade path**
- ✅ **Bi-directional compatibility**
- ✅ **All features preserved**

The database layer remains stable and compatible, allowing seamless upgrades and even downgrades if needed.

## Support

For questions or issues regarding database compatibility:
1. Check application logs for any warnings
2. Verify CouchDB version compatibility
3. Ensure proper authentication credentials
4. Contact support with specific error messages

---
*Document Version: 1.0*
*Last Updated: 2025-09-29*
*Validated Against: NemakiWare 2.4.0 → 3.x*
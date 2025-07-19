# Repository Initialization Guide

## Overview
NemakiWare requires initialization of 4 repositories for proper operation:
- `bedroom` (main document repository)
- `bedroom_closet` (archive for bedroom)
- `canopy` (system management repository)
- `canopy_closet` (archive for canopy)

## Important Notes

### CMIS Authentication Compatibility
The `canopy_init.dump` file has been updated to use CMIS-compatible user formats to prevent Spring initialization failures. Key changes:

1. **User type**: Changed from `"type": "user"` to `"type": "cmis:item"`
2. **Object type**: Set to `"objectType": "nemaki:user"` 
3. **Admin view**: Updated to search for CMIS format users
4. **User properties**: Structured as CMIS subTypeProperties

### Installation Recommendations

#### For Docker environments:
- Use `docker-compose-simple.yml` which automatically initializes all 4 repositories
- Each repository uses the appropriate dump file:
  - `bedroom` → `bedroom_init.dump`
  - `bedroom_closet` → `archive_init.dump`  
  - `canopy` → `canopy_init.dump` (UPDATED with CMIS format)
  - `canopy_closet` → `archive_init.dump`

#### For installer-based deployments:
- **CRITICAL**: Use `canopy_init.dump` for canopy repository initialization
- **DO NOT** use `bedroom_init.dump` for canopy as it was done historically
- **UPDATED (2025-06-29)**: All initialization now uses `cloudant-init.jar` with modern HTTP Client 5.x
- This ensures proper CMIS authentication for both repositories and eliminates Ektorp dependencies

### Historical Context
Previous versions used `bedroom_init.dump` for both repositories, causing:
- Authentication failures for canopy repository
- Spring TokenService initialization errors
- "One or more listeners failed to start" errors

### Verification Commands
After initialization, verify proper setup:

```bash
# Check all databases exist
curl -u admin:password http://localhost:5984/_all_dbs
# Expected: ["bedroom","bedroom_closet","canopy","canopy_closet","nemaki_conf"]

# Test admin views work for both repositories  
curl -u admin:password "http://localhost:5984/bedroom/_design/_repo/_view/admin?key=\"admin\""
curl -u admin:password "http://localhost:5984/canopy/_design/_repo/_view/admin?key=\"admin\""

# Test CMIS endpoints
curl -u admin:admin http://localhost:8080/core/atom/bedroom
curl -u admin:admin http://localhost:8080/core/atom/canopy
```

All commands should return HTTP 200 with valid data.

## Files Modified
- `canopy_init.dump`: Updated to CMIS format (2025-06-21)
- `docker-compose-simple.yml`: Added 4-repository initialization
- `install.xml`: Migrated from bjornloka.jar to cloudant-init.jar (2025-06-28)
- `cloudant-init-wrapper.sh`: Modern wrapper script with HTTP Client 5.x
- Various Spring configuration fixes for startup reliability

## Migration from Ektorp to Cloudant SDK
As of version 2.4.1, NemakiWare has migrated from Ektorp to IBM Cloudant Java SDK:
- **Installer**: Uses `cloudant-init.jar` and `cloudant-init-wrapper.sh`
- **Docker**: Uses `cloudant-init.jar` in initialization containers  
- **Core application**: Uses Cloudant SDK with HTTP Client 5.x
- **Benefits**: Eliminates Ektorp threading issues, modernizes HTTP client stack
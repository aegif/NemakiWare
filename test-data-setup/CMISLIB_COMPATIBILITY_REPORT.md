# cmislib Compatibility Report for NemakiWare

## Overview

This document describes the compatibility issues encountered when using the Python cmislib library (v0.7.0+) with NemakiWare's CMIS 1.1 implementation.

## Environment

- **NemakiWare Version**: 3.0.0-RC1
- **cmislib Version**: 0.7.0
- **Python Version**: 3.8+
- **CMIS Binding Tested**: AtomPub, Browser Binding

## Issues Identified

### 1. RepositoryService Binding Error

**Error Message**:
```
AttributeError: 'RepositoryService' object has no attribute 'binding'
```

**Context**:
When attempting to create folders or documents using cmislib's standard methods, the library fails with an internal error accessing the `binding` attribute.

**Reproduction**:
```python
from cmislib import CmisClient

client = CmisClient('http://localhost:8080/core/atom/bedroom', 'admin', 'admin')
repo = client.getDefaultRepository()
root = repo.getRootFolder()
folder = root.createFolder('TestFolder')  # Error occurs here
```

**Analysis**:
The cmislib library's internal `RepositoryService` class expects a `binding` attribute to determine which CMIS binding protocol to use. However, this attribute initialization fails when connecting to NemakiWare, possibly due to:
- Differences in NemakiWare's AtomPub response format
- Version mismatch in CMIS namespace declarations
- Custom extensions in NemakiWare's CMIS implementation

### 2. AtomPub Endpoint URL Requirements

**Issue**:
cmislib expects a base CMIS service URL, but NemakiWare requires the repository ID to be included in the URL path for authentication.

**Non-working URL patterns**:
- `http://localhost:8080/core/atom11` (404 Not Found)
- `http://localhost:8080/core/atom` (401 Unauthorized)

**Working URL pattern**:
- `http://localhost:8080/core/atom/bedroom` (with repository ID)

This differs from the CMIS standard where the service document should be accessible at the base URL and the repository ID selected programmatically.

### 3. Browser Binding Compatibility

The cmislib library provides limited support for CMIS Browser Binding. While NemakiWare's Browser Binding implementation is fully functional, cmislib's internal methods may not properly handle all response formats.

### 4. Browser Binding ObjectId Query Parameter (Fixed in 3.0.0-RC1)

**Issue (now fixed)**:
The CMIS 1.1 Browser Binding specification supports two URL patterns for navigation:

1. Path-based: `/browser/{repo}/{objectId}?cmisselector=children`
2. Query parameter: `/browser/{repo}/root?cmisselector=children&objectId={id}`

Prior to the fix, NemakiWare only handled the path-based pattern, ignoring the `objectId` query parameter.

**Fix applied in NemakiBrowserBindingServlet.java**:
- The `handleChildrenOperation` and `handleDescendantsOperation` methods now check for the `objectId` query parameter
- Query parameter takes precedence when the path ends with "root"
- Both URL patterns now work correctly

### 5. INTEGER Property Type Conversion (Fixed in 3.0.0-RC1)

**Issue (now fixed)**:
CMIS 1.1 specification uses `BigInteger` for INTEGER property values. However, some internal code was directly casting values to `Long`, causing a `ClassCastException`:

```
java.lang.ClassCastException: class java.math.BigInteger cannot be cast to class java.lang.Long
```

**Fix applied**:
- `ExceptionServiceImpl.constraintIntegerPropertyValue()` now safely handles BigInteger, Long, Integer, and other Number types
- `DataUtil.toLongSafe()` helper method added for safe type conversion
- Custom types with integer properties (like `test:sum` on invoices) now work correctly

### 6. Service Document Access Without Repository ID (Fixed in 3.0.0-RC1)

**Issue (now fixed)**:
cmislib and other CMIS clients expect to access `/atom` without specifying a repository ID to retrieve the service document listing available repositories. Previously, NemakiWare required a repository ID in the URL path for authentication, causing 401 Unauthorized errors when accessing `/atom`.

**Fix applied in CmisServiceFactory.java**:
- When `repositoryId` is null or empty in the CallContext, the default repository is used for authentication
- Added `RepositoryInfoMap.getDefaultRepositoryId()` method to provide a fallback repository
- cmislib can now connect to `http://localhost:8080/core/atom` and discover repositories

**cmislib compatibility verified**:
```python
from cmislib import CmisClient
client = CmisClient('http://localhost:8080/core/atom', 'admin', 'admin')
repo = client.getDefaultRepository()  # Works!
root = repo.getRootFolder()           # Works!
folder = root.createFolder('test')    # Works!
folder.delete()                        # Works!
```

## Workaround: Direct HTTP API Usage

The recommended approach for Python scripts interacting with NemakiWare is to use direct HTTP requests with the Browser Binding API:

### Browser Binding URL Format
```
http://localhost:8080/core/browser/{repository_id}/root
```

### Example: Get Folder Children
```python
import requests
from requests.auth import HTTPBasicAuth

auth = HTTPBasicAuth('admin', 'admin')
base_url = 'http://localhost:8080/core/browser/bedroom'

response = requests.get(
    f'{base_url}/root',
    params={'cmisselector': 'children'},
    auth=auth
)
children = response.json().get('objects', [])
```

### Example: Create Folder
```python
response = requests.post(
    f'{base_url}',
    data={
        'cmisaction': 'createFolder',
        'propertyId[0]': 'cmis:objectTypeId',
        'propertyValue[0]': 'cmis:folder',
        'propertyId[1]': 'cmis:name',
        'propertyValue[1]': 'NewFolder',
    },
    params={'objectId': parent_folder_id},
    auth=auth
)
folder_id = response.json().get('succinctProperties', {}).get('cmis:objectId')
```

### Example: Upload Document
```python
files = {
    'content': ('document.pdf', file_bytes, 'application/pdf')
}
data = {
    'cmisaction': 'createDocument',
    'propertyId[0]': 'cmis:objectTypeId',
    'propertyValue[0]': 'cmis:document',
    'propertyId[1]': 'cmis:name',
    'propertyValue[1]': 'document.pdf',
}
response = requests.post(
    f'{base_url}',
    params={'objectId': folder_id},
    data=data,
    files=files,
    auth=auth
)
```

## Recommendations

### For NemakiWare Users

1. **Use Browser Binding**: Prefer the Browser Binding API over AtomPub for Python scripts
2. **Use requests library**: Direct HTTP calls with the `requests` library provide more reliable results
3. **Avoid cmislib for write operations**: cmislib can be used for simple read operations but should be avoided for creating/updating content

### For NemakiWare Development

1. **Document CMIS URL patterns**: Clearly document the expected URL patterns for each binding
2. **Consider CMIS compliance testing**: Run official CMIS TCK tests against the AtomPub binding
3. **Test with common clients**: Validate compatibility with popular CMIS client libraries

## References

- [CMIS 1.1 Specification](http://docs.oasis-open.org/cmis/CMIS/v1.1/CMIS-v1.1.html)
- [cmislib Documentation](https://chemistry.apache.org/python/cmislib.html)
- [NemakiWare Documentation](https://github.com/NemakiWare/NemakiWare)
- [Apache Chemistry OpenCMIS](https://chemistry.apache.org/)

## Appendix: Test Environment Setup Script

The `setup_test_environment.py` script in this directory demonstrates the direct HTTP API approach for NemakiWare. It successfully creates:
- User groups (organizations)
- User accounts
- Custom CMIS types
- Folder structures with ACL permissions
- Sample documents in various formats

All operations are performed using the Browser Binding API without relying on cmislib for CMIS operations.

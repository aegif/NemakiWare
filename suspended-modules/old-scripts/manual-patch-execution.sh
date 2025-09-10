#!/bin/bash

# Manual Patch_20160815 Execution Script
# This script manually executes the critical system initialization patch

echo "=== Manual Patch_20160815 Execution ==="

# 1. Create System folder in bedroom repository
echo "1. Creating System folder..."
curl -s -X POST -u admin:admin \
  -H "Content-Type: application/atom+xml" \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<atom:entry xmlns:atom="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/">
    <atom:title>System</atom:title>
    <cmisra:object>
        <cmis:properties>
            <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
                <cmis:value>cmis:folder</cmis:value>
            </cmis:propertyId>
            <cmis:propertyString propertyDefinitionId="cmis:name">
                <cmis:value>System</cmis:value>
            </cmis:propertyString>
        </cmis:properties>
    </cmisra:object>
</atom:entry>' \
  "http://localhost:8080/core/atom/bedroom/children?id=e02f784f8360a02cc14d1314c10038ff" | grep -o 'cmis:objectId.*value>[^<]*' | sed 's/.*value>//g' > /tmp/system_folder_id.txt

SYSTEM_FOLDER_ID=$(cat /tmp/system_folder_id.txt)
echo "System folder created with ID: $SYSTEM_FOLDER_ID"

# 2. Create users subfolder
echo "2. Creating users subfolder..."
curl -s -X POST -u admin:admin \
  -H "Content-Type: application/atom+xml" \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<atom:entry xmlns:atom="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/">
    <atom:title>users</atom:title>
    <cmisra:object>
        <cmis:properties>
            <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
                <cmis:value>cmis:folder</cmis:value>
            </cmis:propertyId>
            <cmis:propertyString propertyDefinitionId="cmis:name">
                <cmis:value>users</cmis:value>
            </cmis:propertyString>
        </cmis:properties>
    </cmisra:object>
</atom:entry>' \
  "http://localhost:8080/core/atom/bedroom/children?id=$SYSTEM_FOLDER_ID" | grep -o 'cmis:objectId.*value>[^<]*' | sed 's/.*value>//g' > /tmp/users_folder_id.txt

USERS_FOLDER_ID=$(cat /tmp/users_folder_id.txt)
echo "Users folder created with ID: $USERS_FOLDER_ID"

# 3. Create groups subfolder
echo "3. Creating groups subfolder..."
curl -s -X POST -u admin:admin \
  -H "Content-Type: application/atom+xml" \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<atom:entry xmlns:atom="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/">
    <atom:title>groups</atom:title>
    <cmisra:object>
        <cmis:properties>
            <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
                <cmis:value>cmis:folder</cmis:value>
            </cmis:propertyId>
            <cmis:propertyString propertyDefinitionId="cmis:name">
                <cmis:value>groups</cmis:value>
            </cmis:propertyString>
        </cmis:properties>
    </cmisra:object>
</atom:entry>' \
  "http://localhost:8080/core/atom/bedroom/children?id=$SYSTEM_FOLDER_ID" | grep -o 'cmis:objectId.*value>[^<]*' | sed 's/.*value>//g' > /tmp/groups_folder_id.txt

GROUPS_FOLDER_ID=$(cat /tmp/groups_folder_id.txt)
echo "Groups folder created with ID: $GROUPS_FOLDER_ID"

# 4. Create configuration document to store system folder ID
echo "4. Creating configuration document..."
curl -s -X PUT -u admin:password \
  -H "Content-Type: application/json" \
  -d "{\"_id\": \"bedroom\", \"type\": \"configuration\", \"configuration\": {\"system.folder\": \"$SYSTEM_FOLDER_ID\", \"users.folder\": \"$USERS_FOLDER_ID\", \"groups.folder\": \"$GROUPS_FOLDER_ID\"}}" \
  "http://localhost:5984/bedroom/bedroom"

echo "Configuration document created with system folder ID: $SYSTEM_FOLDER_ID"

echo "=== Manual Patch Execution Completed ==="
echo "System Folder ID: $SYSTEM_FOLDER_ID"
echo "Users Folder ID: $USERS_FOLDER_ID"  
echo "Groups Folder ID: $GROUPS_FOLDER_ID"

# 5. Verify configuration
echo "5. Verifying configuration..."
curl -s -u admin:password "http://localhost:5984/bedroom/bedroom" | jq '.configuration'

echo "=== Verification Complete ==="
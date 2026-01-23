# OpenLDAP Test Environment for Directory Sync

This directory contains configuration files for setting up an OpenLDAP test environment to test the NemakiWare Directory Sync feature.

## Quick Start

```bash
# Start the LDAP environment with NemakiWare
cd docker
docker-compose -f docker-compose-ldap.yml up -d

# Wait for services to be healthy
docker-compose -f docker-compose-ldap.yml ps

# Access phpLDAPadmin (web-based LDAP management)
# URL: https://localhost:8443
# Login DN: cn=admin,dc=example,dc=org
# Password: admin
```

## Test Data

The bootstrap directory contains LDIF files that are automatically loaded when the OpenLDAP container starts:

### Users (ou=users,dc=example,dc=org)

| User ID   | Name           | Email                  |
|-----------|----------------|------------------------|
| yamada    | Taro Yamada    | yamada@example.org     |
| suzuki    | Hanako Suzuki  | suzuki@example.org     |
| tanaka    | Ichiro Tanaka  | tanaka@example.org     |
| sato      | Yuki Sato      | sato@example.org       |
| watanabe  | Kenji Watanabe | watanabe@example.org   |

All users have password: `password`

### Groups (ou=groups,dc=example,dc=org)

| Group Name    | Members                              |
|---------------|--------------------------------------|
| engineering   | yamada, tanaka, watanabe             |
| sales         | suzuki, sato                         |
| managers      | yamada, suzuki                       |
| all-staff     | yamada, suzuki, tanaka, sato, watanabe |
| project-alpha | yamada, tanaka, sato                 |

## LDAP Connection Settings

For NemakiWare configuration:

```properties
directory.sync.enabled=true
directory.sync.ldap.url=ldap://openldap:389
directory.sync.ldap.baseDn=dc=example,dc=org
directory.sync.ldap.bindDn=cn=admin,dc=example,dc=org
directory.sync.ldap.bindPassword=admin
directory.sync.group.searchBase=ou=groups
directory.sync.group.searchFilter=(objectClass=groupOfNames)
directory.sync.user.searchBase=ou=users
directory.sync.user.searchFilter=(objectClass=inetOrgPerson)
directory.sync.group.idAttribute=cn
directory.sync.group.nameAttribute=cn
directory.sync.group.memberAttribute=member
directory.sync.user.idAttribute=uid
```

## Testing Directory Sync

### 1. Trigger Manual Sync

```bash
# Test connection
curl -X GET "http://localhost:8080/core/rest/repo/bedroom/directory-sync/test-connection" \
  -u admin:admin

# Preview sync (dry run)
curl -X GET "http://localhost:8080/core/rest/repo/bedroom/directory-sync/preview" \
  -u admin:admin

# Execute sync
curl -X POST "http://localhost:8080/core/rest/repo/bedroom/directory-sync/trigger" \
  -u admin:admin
```

### 2. Verify Results

After sync, you should see:
- 5 new users created in NemakiWare (with ldap_ prefix or as configured)
- 5 new groups created in NemakiWare (ldap_engineering, ldap_sales, etc.)
- Group memberships properly mapped

### 3. Add/Modify LDAP Data

Use phpLDAPadmin at https://localhost:8443 to:
- Add new users or groups
- Modify group memberships
- Delete entries

Then trigger another sync to verify diff detection works correctly.

## Troubleshooting

### Check LDAP Container Logs
```bash
docker-compose -f docker-compose-ldap.yml logs openldap
```

### Manual LDAP Query
```bash
# List all users
docker exec -it $(docker ps -qf "name=openldap") \
  ldapsearch -x -H ldap://localhost:389 -b "ou=users,dc=example,dc=org" -D "cn=admin,dc=example,dc=org" -w admin

# List all groups
docker exec -it $(docker ps -qf "name=openldap") \
  ldapsearch -x -H ldap://localhost:389 -b "ou=groups,dc=example,dc=org" -D "cn=admin,dc=example,dc=org" -w admin
```

### Reset LDAP Data
```bash
# Stop and remove volumes
docker-compose -f docker-compose-ldap.yml down -v

# Start fresh
docker-compose -f docker-compose-ldap.yml up -d
```

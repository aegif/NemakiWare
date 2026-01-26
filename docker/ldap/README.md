# OpenLDAP Test Environment for Directory Sync

This directory contains configuration files for setting up an OpenLDAP test environment to test the NemakiWare Directory Sync feature.

## Quick Start

```bash
# Start the LDAP+Keycloak environment (recommended)
cd docker
docker compose -f docker-compose-ldap-keycloak-test.yml up -d

# Or use the comprehensive test script
./run-ldap-keycloak-tests.sh --only-setup

# Access services
# - NemakiWare: http://localhost:8080/core/ui/
# - Keycloak:   http://localhost:8088 (admin/admin)
```

## Domain Configuration

| Setting | Value |
|---------|-------|
| Domain | nemakiware.example.com |
| Base DN | dc=nemakiware,dc=example,dc=com |
| Admin DN | cn=admin,dc=nemakiware,dc=example,dc=com |
| Admin Password | adminpassword |

## Test Data

The bootstrap directory contains LDIF files that are automatically loaded when the OpenLDAP container starts.

### Users (ou=users,dc=nemakiware,dc=example,dc=com)

#### OIDC Integration Test Users
| User ID | Name | Email | Password |
|---------|------|-------|----------|
| ldapuser1 | LDAP User One | ldapuser1@nemakiware.example.com | ldappass1 |
| ldapuser2 | LDAP User Two | ldapuser2@nemakiware.example.com | ldappass2 |
| ldapadmin | LDAP Administrator | ldapadmin@nemakiware.example.com | ldapadminpass |

#### Directory Sync Test Users
| User ID | Name | Email | Password |
|---------|------|-------|----------|
| yamada | Taro Yamada | yamada@nemakiware.example.com | yamadapass |
| suzuki | Hanako Suzuki | suzuki@nemakiware.example.com | suzukipass |
| tanaka | Ichiro Tanaka | tanaka@nemakiware.example.com | tanakapass |
| sato | Yuki Sato | sato@nemakiware.example.com | satopass |
| watanabe | Kenji Watanabe | watanabe@nemakiware.example.com | watanabepass |

### Groups (ou=groups,dc=nemakiware,dc=example,dc=com)

#### OIDC Integration Test Groups
| Group Name | Members |
|------------|---------|
| nemaki-users | ldapuser1, ldapuser2, ldapadmin |
| nemaki-admins | ldapadmin |

#### Directory Sync Test Groups
| Group Name | Members |
|------------|---------|
| engineering | yamada, tanaka, watanabe |
| sales | suzuki, sato |
| managers | yamada, suzuki |
| all-staff | yamada, suzuki, tanaka, sato, watanabe |
| project-alpha | yamada, tanaka, sato |

## LDAP Connection Settings

For NemakiWare configuration (nemakiware.properties or environment variables):

```properties
directory.sync.enabled=true
directory.sync.ldap.url=ldap://openldap:389
directory.sync.ldap.base.dn=dc=nemakiware,dc=example,dc=com
directory.sync.ldap.bind.dn=cn=admin,dc=nemakiware,dc=example,dc=com
directory.sync.ldap.bind.password=adminpassword
directory.sync.group.search.base=ou=groups
directory.sync.group.search.filter=(objectClass=groupOfNames)
directory.sync.user.search.base=ou=users
directory.sync.user.search.filter=(objectClass=inetOrgPerson)
directory.sync.group.id.attribute=cn
directory.sync.group.name.attribute=cn
directory.sync.group.member.attribute=member
directory.sync.user.id.attribute=uid
directory.sync.group.prefix=ldap_
directory.sync.user.prefix=
```

## Testing

### Run Comprehensive Tests

```bash
# Full test (builds, starts containers, runs tests, cleans up)
./run-ldap-keycloak-tests.sh

# Skip build (faster)
./run-ldap-keycloak-tests.sh --skip-build

# Keep containers running after tests
./run-ldap-keycloak-tests.sh --keep-running
```

### Manual Testing

#### Test Connection
```bash
curl -X GET "http://localhost:8080/core/rest/repo/bedroom/sync/test-connection" \
  -u admin:admin
```

#### Preview Sync (Dry Run)
```bash
curl -X GET "http://localhost:8080/core/rest/repo/bedroom/sync/preview" \
  -u admin:admin
```

#### Execute Sync
```bash
curl -X POST "http://localhost:8080/core/rest/repo/bedroom/sync/trigger" \
  -u admin:admin
```

#### Check Sync Status
```bash
curl -X GET "http://localhost:8080/core/rest/repo/bedroom/sync/status" \
  -u admin:admin
```

### Verify Results

After sync, you should see:
- 8 new users created in NemakiWare (ldapuser1, ldapuser2, ldapadmin, yamada, suzuki, tanaka, sato, watanabe)
- 7 new groups created in NemakiWare (with ldap_ prefix: ldap_nemaki-users, ldap_engineering, etc.)
- Group memberships properly mapped

## Troubleshooting

### Check LDAP Container Logs
```bash
docker logs openldap
```

### Manual LDAP Query
```bash
# List all users
docker exec openldap ldapsearch -x -H ldap://localhost:389 \
  -b "ou=users,dc=nemakiware,dc=example,dc=com" \
  -D "cn=admin,dc=nemakiware,dc=example,dc=com" -w adminpassword

# List all groups
docker exec openldap ldapsearch -x -H ldap://localhost:389 \
  -b "ou=groups,dc=nemakiware,dc=example,dc=com" \
  -D "cn=admin,dc=nemakiware,dc=example,dc=com" -w adminpassword
```

### Reset Environment
```bash
# Stop and remove volumes
docker compose -f docker-compose-ldap-keycloak-test.yml down -v

# Start fresh
docker compose -f docker-compose-ldap-keycloak-test.yml up -d --build
```

## Related Files

| File | Description |
|------|-------------|
| `bootstrap/01-users-groups.ldif` | LDAP initial data |
| `test-directory-sync.sh` | Comprehensive sync test script |
| `../docker-compose-ldap-keycloak-test.yml` | Docker Compose configuration |
| `../run-ldap-keycloak-tests.sh` | One-command test runner |
| `../README-LDAP-KEYCLOAK-TEST.md` | Integration test documentation |

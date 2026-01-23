# ディレクトリ同期機能 設計書

## 1. 概要

### 1.1 目的
Active Directory (AD) や LDAP などのディレクトリサービスで管理されている組織・所属情報を、NemakiWare のグループ情報として取り込む機能を実装する。

### 1.2 背景
多くの企業では、ユーザーと組織構造の管理に Active Directory や LDAP を使用している。NemakiWare を既存の企業インフラと統合するために、これらのディレクトリサービスからグループ情報を自動的に同期する機能が必要である。

### 1.3 スコープ

**対象範囲:**
- LDAP/AD からのグループ情報の読み取り
- NemakiWare グループへのマッピングと同期
- スケジュール実行による定期同期
- 手動同期トリガー（REST API）
- 同期設定の管理

**対象外（将来の拡張）:**
- LDAP/AD を使用した認証（既存の SAML/OIDC 認証を継続使用）
- NemakiWare から LDAP/AD への書き戻し
- リアルタイム同期（プッシュ型）

### 1.4 同期方式: プル型同期

本機能では「プル型同期」を採用する。

**プル型同期とは:**
NemakiWare 側から定期的に LDAP/AD サーバーに接続してグループ情報を「取りに行く」方式。

```
┌─────────────┐                    ┌─────────────┐
│ NemakiWare  │ ──── 接続要求 ────▶ │  LDAP/AD    │
│             │ ◀─── グループ情報 ── │             │
└─────────────┘                    └─────────────┘
      │
      ▼
  グループ同期
```

**対照的な「プッシュ型同期」:**
LDAP/AD 側で変更があった際に NemakiWare に通知を送る方式（本機能では対象外）。

**プル型同期のメリット:**
- 実装がシンプル
- LDAP/AD 側の設定変更が不要
- NemakiWare 側で同期タイミングを完全にコントロール可能
- ファイアウォール設定が容易（NemakiWare → LDAP/AD の一方向のみ）

**プル型同期のデメリット:**
- リアルタイム性がない（同期間隔に依存）
- 変更がなくても定期的に LDAP/AD に接続する

**同期タイミング:**
- スケジュール実行（例: 毎時、毎日など、Cron式で設定可能）
- 手動トリガー（REST API または管理画面から）

## 2. アーキテクチャ

### 2.1 全体構成

```
┌─────────────────────────────────────────────────────────────────┐
│                        NemakiWare                                │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    REST API Layer                         │   │
│  │  ┌─────────────────────────────────────────────────────┐ │   │
│  │  │ DirectorySyncResource                               │ │   │
│  │  │ - POST /sync/trigger (手動同期)                     │ │   │
│  │  │ - GET /sync/status (同期状態)                       │ │   │
│  │  │ - GET /sync/config (設定取得)                       │ │   │
│  │  │ - PUT /sync/config (設定更新)                       │ │   │
│  │  └─────────────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                  Business Logic Layer                     │   │
│  │  ┌─────────────────────────────────────────────────────┐ │   │
│  │  │ DirectorySyncService                                │ │   │
│  │  │ - 同期ロジックの実行                                │ │   │
│  │  │ - 差分検出                                          │ │   │
│  │  │ - マッピング処理                                    │ │   │
│  │  └─────────────────────────────────────────────────────┘ │   │
│  │  ┌─────────────────────────────────────────────────────┐ │   │
│  │  │ DirectorySyncScheduler                              │ │   │
│  │  │ - スケジュール実行管理                              │ │   │
│  │  └─────────────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    Data Access Layer                      │   │
│  │  ┌─────────────────────────────────────────────────────┐ │   │
│  │  │ LdapDirectoryConnector                              │ │   │
│  │  │ - LDAP/AD 接続管理                                  │ │   │
│  │  │ - グループ/ユーザー検索                             │ │   │
│  │  └─────────────────────────────────────────────────────┘ │   │
│  │  ┌─────────────────────────────────────────────────────┐ │   │
│  │  │ PrincipalService (既存)                             │ │   │
│  │  │ - NemakiWare グループ CRUD                          │ │   │
│  │  └─────────────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    External Directory                            │
│  ┌─────────────────────┐    ┌─────────────────────┐             │
│  │   Active Directory  │    │      OpenLDAP       │             │
│  │   (LDAP Protocol)   │    │   (LDAP Protocol)   │             │
│  └─────────────────────┘    └─────────────────────┘             │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 コンポーネント詳細

#### 2.2.1 DirectorySyncResource (REST API)
管理者向けの REST API エンドポイントを提供する。

| エンドポイント | メソッド | 説明 |
|---------------|---------|------|
| `/api/v1/repositories/{repoId}/sync/trigger` | POST | 手動同期の実行 |
| `/api/v1/repositories/{repoId}/sync/status` | GET | 同期状態の取得 |
| `/api/v1/repositories/{repoId}/sync/history` | GET | 同期履歴の取得 |
| `/api/v1/repositories/{repoId}/sync/config` | GET | 同期設定の取得 |
| `/api/v1/repositories/{repoId}/sync/config` | PUT | 同期設定の更新 |
| `/api/v1/repositories/{repoId}/sync/preview` | POST | 同期プレビュー（ドライラン） |

#### 2.2.2 DirectorySyncService
同期ロジックのコア実装。

**主要メソッド:**
- `syncGroups(repositoryId, dryRun)`: グループ同期の実行
- `detectChanges(repositoryId)`: 差分検出
- `mapLdapGroupToNemaki(ldapGroup, mappingConfig)`: マッピング処理

#### 2.2.3 LdapDirectoryConnector
LDAP/AD への接続と検索を担当。

**主要メソッド:**
- `connect()`: 接続確立
- `searchGroups(baseDn, filter)`: グループ検索
- `searchUsers(baseDn, filter)`: ユーザー検索
- `getGroupMembers(groupDn)`: グループメンバー取得

## 3. データモデル

### 3.1 同期設定 (DirectorySyncConfig)

```java
public class DirectorySyncConfig {
    private String id;
    private String repositoryId;
    private boolean enabled;
    
    // LDAP接続設定
    private String ldapUrl;              // ldap://ad.example.com:389
    private String ldapBaseDn;           // dc=example,dc=com
    private String ldapBindDn;           // cn=admin,dc=example,dc=com
    private String ldapBindPassword;     // (暗号化して保存)
    private boolean useTls;
    private boolean useStartTls;
    
    // 検索設定
    private String groupSearchBase;      // ou=groups
    private String groupSearchFilter;    // (objectClass=group)
    private String userSearchBase;       // ou=users
    private String userSearchFilter;     // (objectClass=user)
    
    // 属性マッピング
    private String groupIdAttribute;     // cn または sAMAccountName
    private String groupNameAttribute;   // displayName
    private String groupMemberAttribute; // member または memberUid
    private String userIdAttribute;      // uid または sAMAccountName
    
    // 同期オプション
    private boolean syncNestedGroups;    // ネストされたグループを同期
    private boolean createMissingUsers;  // 存在しないユーザーを作成
    private boolean deleteOrphanGroups;  // 同期元にないグループを削除
    private String groupPrefix;          // 同期グループの接頭辞 (例: "ldap_")
    
    // スケジュール設定
    private String cronExpression;       // 0 0 * * * * (毎時)
    private boolean scheduleEnabled;
    
    // メタデータ
    private GregorianCalendar created;
    private GregorianCalendar modified;
    private String lastSyncTime;
    private String lastSyncStatus;
}
```

### 3.2 同期結果 (DirectorySyncResult)

```java
public class DirectorySyncResult {
    private String syncId;
    private String repositoryId;
    private GregorianCalendar startTime;
    private GregorianCalendar endTime;
    private SyncStatus status;           // SUCCESS, PARTIAL, FAILED
    
    private int groupsCreated;
    private int groupsUpdated;
    private int groupsDeleted;
    private int groupsSkipped;
    private int usersAdded;
    private int usersRemoved;
    
    private List<SyncError> errors;
    private List<SyncWarning> warnings;
}
```

### 3.3 LDAP グループ (LdapGroup)

```java
public class LdapGroup {
    private String dn;                   // 識別名
    private String groupId;              // グループID
    private String groupName;            // 表示名
    private List<String> memberDns;      // メンバーのDNリスト
    private List<String> memberUserIds;  // メンバーのユーザーIDリスト
    private List<String> memberGroupIds; // ネストされたグループIDリスト
    private Map<String, Object> attributes; // その他の属性
}
```

## 4. 同期フロー

### 4.1 基本同期フロー

```
1. 同期開始
   │
2. LDAP接続確立
   │
3. LDAPからグループ一覧を取得
   │
4. 各グループについて:
   │  ├─ グループメンバーを取得
   │  ├─ NemakiWareのグループIDにマッピング
   │  └─ メンバーをNemakiWareのユーザーIDにマッピング
   │
5. NemakiWareの既存グループと比較
   │  ├─ 新規グループ → 作成
   │  ├─ 既存グループ → 更新（メンバー変更）
   │  └─ 削除されたグループ → 削除（オプション）
   │
6. 同期結果を記録
   │
7. 同期完了
```

### 4.2 差分同期ロジック

```java
// 疑似コード
public SyncResult syncGroups(String repositoryId, boolean dryRun) {
    DirectorySyncConfig config = getConfig(repositoryId);
    
    // LDAPからグループを取得
    List<LdapGroup> ldapGroups = ldapConnector.searchGroups(
        config.getGroupSearchBase(),
        config.getGroupSearchFilter()
    );
    
    // NemakiWareの同期対象グループを取得
    List<GroupItem> nemakiGroups = contentService.getGroupItems(repositoryId)
        .stream()
        .filter(g -> g.getGroupId().startsWith(config.getGroupPrefix()))
        .collect(toList());
    
    // マッピングと比較
    Map<String, LdapGroup> ldapGroupMap = ldapGroups.stream()
        .collect(toMap(g -> config.getGroupPrefix() + g.getGroupId(), g -> g));
    
    Map<String, GroupItem> nemakiGroupMap = nemakiGroups.stream()
        .collect(toMap(GroupItem::getGroupId, g -> g));
    
    // 新規作成
    for (LdapGroup ldapGroup : ldapGroups) {
        String nemakiGroupId = config.getGroupPrefix() + ldapGroup.getGroupId();
        if (!nemakiGroupMap.containsKey(nemakiGroupId)) {
            if (!dryRun) {
                createGroup(repositoryId, ldapGroup, config);
            }
            result.incrementCreated();
        }
    }
    
    // 更新
    for (GroupItem nemakiGroup : nemakiGroups) {
        String ldapGroupId = nemakiGroup.getGroupId()
            .substring(config.getGroupPrefix().length());
        LdapGroup ldapGroup = ldapGroupMap.get(nemakiGroup.getGroupId());
        if (ldapGroup != null && hasChanges(nemakiGroup, ldapGroup)) {
            if (!dryRun) {
                updateGroup(repositoryId, nemakiGroup, ldapGroup, config);
            }
            result.incrementUpdated();
        }
    }
    
    // 削除（オプション）
    if (config.isDeleteOrphanGroups()) {
        for (GroupItem nemakiGroup : nemakiGroups) {
            if (!ldapGroupMap.containsKey(nemakiGroup.getGroupId())) {
                if (!dryRun) {
                    deleteGroup(repositoryId, nemakiGroup);
                }
                result.incrementDeleted();
            }
        }
    }
    
    return result;
}
```

## 5. 設定ファイル

### 5.1 nemakiware.properties への追加

```properties
### Directory Sync Configuration
# Enable directory sync feature
directory.sync.enabled=false

# Default LDAP connection settings (can be overridden per repository)
directory.sync.ldap.url=ldap://localhost:389
directory.sync.ldap.base.dn=dc=example,dc=com
directory.sync.ldap.bind.dn=cn=admin,dc=example,dc=com
directory.sync.ldap.bind.password=
directory.sync.ldap.use.tls=false
directory.sync.ldap.use.starttls=false
directory.sync.ldap.connection.timeout=5000
directory.sync.ldap.read.timeout=30000

# Search settings
directory.sync.group.search.base=ou=groups
directory.sync.group.search.filter=(objectClass=groupOfNames)
directory.sync.user.search.base=ou=users
directory.sync.user.search.filter=(objectClass=inetOrgPerson)

# Attribute mapping
directory.sync.group.id.attribute=cn
directory.sync.group.name.attribute=description
directory.sync.group.member.attribute=member
directory.sync.user.id.attribute=uid

# Sync options
directory.sync.nested.groups=true
directory.sync.create.missing.users=false
directory.sync.delete.orphan.groups=false
directory.sync.group.prefix=ldap_

# Schedule (cron expression)
directory.sync.schedule.enabled=false
directory.sync.schedule.cron=0 0 * * * *
```

### 5.2 Active Directory 用設定例

```properties
directory.sync.ldap.url=ldap://ad.example.com:389
directory.sync.ldap.base.dn=dc=example,dc=com
directory.sync.ldap.bind.dn=cn=ServiceAccount,ou=ServiceAccounts,dc=example,dc=com
directory.sync.ldap.use.starttls=true

directory.sync.group.search.base=ou=Groups
directory.sync.group.search.filter=(&(objectClass=group)(groupType:1.2.840.113556.1.4.803:=2147483648))
directory.sync.group.id.attribute=sAMAccountName
directory.sync.group.name.attribute=displayName
directory.sync.group.member.attribute=member

directory.sync.user.search.base=ou=Users
directory.sync.user.search.filter=(&(objectClass=user)(objectCategory=person))
directory.sync.user.id.attribute=sAMAccountName
```

### 5.3 OpenLDAP 用設定例

```properties
directory.sync.ldap.url=ldap://ldap.example.com:389
directory.sync.ldap.base.dn=dc=example,dc=com
directory.sync.ldap.bind.dn=cn=admin,dc=example,dc=com

directory.sync.group.search.base=ou=groups
directory.sync.group.search.filter=(objectClass=groupOfNames)
directory.sync.group.id.attribute=cn
directory.sync.group.name.attribute=description
directory.sync.group.member.attribute=member

directory.sync.user.search.base=ou=people
directory.sync.user.search.filter=(objectClass=inetOrgPerson)
directory.sync.user.id.attribute=uid
```

## 6. 開発時の検証環境

### 6.1 推奨環境: OpenLDAP (Docker)

開発・テスト用に最も軽量で簡単にセットアップできる環境として、osixia/openldap Docker イメージを使用する。

**選定理由:**
- イメージサイズが約50MB程度と軽量
- docker-compose 一発で起動可能
- phpLDAPAdmin（Web管理画面）が付属
- 標準的な LDAP スキーマをサポート
- テストデータの投入が容易（LDIF ファイル）

```yaml
# docker-compose.ldap.yml
version: '3.8'

services:
  openldap:
    image: osixia/openldap:1.5.0
    container_name: nemaki-openldap
    environment:
      LDAP_ORGANISATION: "NemakiWare Test"
      LDAP_DOMAIN: "nemakiware.local"
      LDAP_BASE_DN: "dc=nemakiware,dc=local"
      LDAP_ADMIN_PASSWORD: "admin"
      LDAP_CONFIG_PASSWORD: "config"
    ports:
      - "389:389"
      - "636:636"
    volumes:
      - ./ldap/data:/var/lib/ldap
      - ./ldap/config:/etc/ldap/slapd.d
      - ./ldap/bootstrap:/container/service/slapd/assets/config/bootstrap/ldif/custom
    networks:
      - nemaki-network

  phpldapadmin:
    image: osixia/phpldapadmin:0.9.0
    container_name: nemaki-phpldapadmin
    environment:
      PHPLDAPADMIN_LDAP_HOSTS: openldap
      PHPLDAPADMIN_HTTPS: "false"
    ports:
      - "8081:80"
    depends_on:
      - openldap
    networks:
      - nemaki-network

networks:
  nemaki-network:
    external: true
```

### 6.2 テストデータ (LDIF)

```ldif
# ldap/bootstrap/01-structure.ldif
dn: ou=groups,dc=nemakiware,dc=local
objectClass: organizationalUnit
ou: groups

dn: ou=people,dc=nemakiware,dc=local
objectClass: organizationalUnit
ou: people

# 部門グループ
dn: cn=engineering,ou=groups,dc=nemakiware,dc=local
objectClass: groupOfNames
cn: engineering
description: Engineering Department
member: uid=user1,ou=people,dc=nemakiware,dc=local
member: uid=user2,ou=people,dc=nemakiware,dc=local

dn: cn=sales,ou=groups,dc=nemakiware,dc=local
objectClass: groupOfNames
cn: sales
description: Sales Department
member: uid=user3,ou=people,dc=nemakiware,dc=local

dn: cn=managers,ou=groups,dc=nemakiware,dc=local
objectClass: groupOfNames
cn: managers
description: Managers Group
member: uid=user1,ou=people,dc=nemakiware,dc=local
member: cn=engineering,ou=groups,dc=nemakiware,dc=local

# ユーザー
dn: uid=user1,ou=people,dc=nemakiware,dc=local
objectClass: inetOrgPerson
uid: user1
cn: Test User 1
sn: User1
mail: user1@nemakiware.local

dn: uid=user2,ou=people,dc=nemakiware,dc=local
objectClass: inetOrgPerson
uid: user2
cn: Test User 2
sn: User2
mail: user2@nemakiware.local

dn: uid=user3,ou=people,dc=nemakiware,dc=local
objectClass: inetOrgPerson
uid: user3
cn: Test User 3
sn: User3
mail: user3@nemakiware.local
```

### 6.3 Active Directory テスト（オプション）

本番環境で Active Directory を使用する場合、基本的な機能は OpenLDAP でテスト可能だが、AD 固有の機能（ネストされたグループの展開など）をテストする必要がある場合は、以下のオプションを検討する。

- **dwimberger/ldap-ad-it**: Apache Directory Server ベースの軽量な AD 模擬環境
- **Samba AD DC**: より本格的な AD 互換環境（セットアップが複雑）

基本的な開発・テストは OpenLDAP で十分であり、AD 固有の機能は実環境での検証を推奨する。

## 7. 実装計画

### Phase 1: 基盤実装 (2週間)

1. **Maven依存関係の追加**
   - spring-ldap-core
   - spring-security-ldap (オプション)

2. **データモデルの実装**
   - DirectorySyncConfig
   - DirectorySyncResult
   - LdapGroup

3. **LdapDirectoryConnector の実装**
   - 接続管理
   - グループ検索
   - ユーザー検索

4. **設定ファイルの拡張**
   - nemakiware.properties への設定追加
   - PropertyKey の拡張

### Phase 2: 同期ロジック実装 (2週間)

1. **DirectorySyncService の実装**
   - 同期ロジック
   - 差分検出
   - マッピング処理

2. **既存 PrincipalService との統合**
   - グループ作成/更新/削除

3. **同期結果の記録**
   - CouchDB への保存

### Phase 3: REST API 実装 (1週間)

1. **DirectorySyncResource の実装**
   - 手動同期トリガー
   - 同期状態取得
   - 設定管理

2. **認証・認可**
   - 管理者のみアクセス可能

### Phase 4: スケジューラ実装 (1週間)

1. **DirectorySyncScheduler の実装**
   - Quartz または Spring Scheduler
   - Cron式によるスケジュール

2. **設定の動的更新**

### Phase 5: UI 実装 (2週間)

1. **管理画面の追加**
   - 同期設定画面
   - 同期状態表示
   - 手動同期ボタン
   - 同期履歴表示

2. **i18n 対応**
   - 日本語/英語

### Phase 6: テスト・ドキュメント (1週間)

1. **ユニットテスト**
2. **統合テスト（Docker環境）**
3. **ドキュメント作成**

## 8. セキュリティ考慮事項

### 8.1 認証情報の保護

- LDAP バインドパスワードは暗号化して保存
- 設定ファイルへの直接記載は避け、環境変数または暗号化ストレージを使用
- TLS/StartTLS の使用を推奨

### 8.2 アクセス制御

- 同期機能は管理者のみがアクセス可能
- 同期設定の変更は監査ログに記録

### 8.3 LDAP 接続のセキュリティ

- 接続タイムアウトの設定
- 読み取りタイムアウトの設定
- 証明書検証（本番環境）

## 9. 今後の拡張可能性

1. **LDAP 認証統合**: 既存の SAML/OIDC に加えて LDAP 認証をサポート
2. **双方向同期**: NemakiWare から LDAP への書き戻し
3. **リアルタイム同期**: LDAP 変更通知（Persistent Search）の活用
4. **複数ディレクトリ対応**: 複数の LDAP/AD サーバーからの同期
5. **属性マッピングの拡張**: カスタム属性のサポート

## 10. 参考資料

- [Spring LDAP Documentation](https://docs.spring.io/spring-ldap/docs/current/reference/)
- [Spring Security LDAP](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/ldap.html)
- [RFC 4511 - LDAP Protocol](https://tools.ietf.org/html/rfc4511)
- [Active Directory LDAP Schema](https://docs.microsoft.com/en-us/windows/win32/adschema/active-directory-schema)

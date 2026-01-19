# NemakiWare OpenAPI REST API & OData 設計書

## 1. 概要

本設計書は、NemakiWare 3.0.0 の次期リリースにおける OpenAPI 準拠 REST API の拡充と OData 4.0 対応の詳細設計を記述します。

### 1.1 背景

現在の NemakiWare は以下の API を提供しています：

1. **CMIS 1.1 バインディング** - AtomPub バインディング (`/atom/`) と Browser バインディング (`/browser/`)
2. **独自 REST API** (`/rest/`) - CMIS にない操作（ユーザー管理、グループ管理、タイプ管理など）

本設計では、CMIS の全機能を OpenAPI 準拠の REST API として公開し、さらに OData 4.0 プロトコルによるデータアクセスを可能にします。

### 1.2 設計目標

1. **OpenAPI 3.0 準拠**: 完全なドキュメント化された REST API の提供
2. **CMIS 全機能の REST 化**: CMIS バインディングでのみ利用可能だった操作を REST API として公開
3. **OData 4.0 対応**: 標準的なクエリ機能によるコンテンツ検索・ナビゲーション
4. **後方互換性**: 既存の REST エンドポイントの維持
5. **セキュリティ**: 適切な認証・認可の実装

### 1.3 対象バージョン

- ベースブランチ: `release/3.0.0-RC1-QA`
- ターゲットリリース: 3.1.0 または 4.0.0

---

## 2. 現状分析

### 2.1 既存 REST API エンドポイント

現在の `/rest/` パス配下で提供されている API：

| カテゴリ | パス | 機能 |
|---------|------|------|
| ユーザー管理 | `/rest/repo/{repositoryId}/user/` | CRUD、検索、パスワード変更 |
| グループ管理 | `/rest/repo/{repositoryId}/group/` | CRUD、メンバー管理 |
| タイプ管理 | `/rest/repo/{repositoryId}/type/` | CRUD、XML/JSON インポート |
| アーカイブ | `/rest/repo/{repositoryId}/archive/` | 一覧、復元、完全削除 |
| ACL | `/rest/repo/{repositoryId}/node/{objectId}/acl` | 取得、設定 |
| 認証 | `/rest/repo/{repositoryId}/authtoken/` | トークン発行、SAML/OIDC 連携 |
| キャッシュ | `/rest/repo/{repositoryId}/cache/` | キャッシュ無効化 |
| 検索エンジン | `/rest/repo/{repositoryId}/search-engine/` | Solr 管理、再インデックス |
| レンディション | `/rest/repo/{repositoryId}/renditions/` | PDF 変換、取得 |
| リポジトリ | `/rest/all/repositories` | リポジトリ一覧 |

### 2.2 CMIS サービス（REST 未公開）

現在 CMIS バインディングでのみ利用可能な操作：

#### ObjectService
- `create`, `createDocument`, `createFolder`, `createRelationship`, `createPolicy`, `createItem`
- `getObject`, `getObjectByPath`
- `updateProperties`, `bulkUpdateProperties`
- `deleteObject`, `deleteTree`
- `moveObject`
- `setContentStream`, `deleteContentStream`, `appendContentStream`
- `getContentStream`
- `getRenditions`
- `getAllowableActions`

#### NavigationService
- `getChildren`
- `getDescendants`
- `getFolderParent`
- `getObjectParents`
- `getCheckedOutDocs`

#### RepositoryService
- `getRepositoryInfo`, `getRepositoryInfos`
- `getTypeChildren`, `getTypeDescendants`
- `getTypeDefinition`
- `createType`, `updateType`, `deleteType`

#### AclService
- `getAcl`
- `applyAcl`

#### VersioningService
- `checkOut`, `cancelCheckOut`, `checkIn`
- `getAllVersions`
- `getObjectOfLatestVersion`

#### DiscoveryService
- `query`
- `getContentChanges`

#### RelationshipService
- `getObjectRelationships`

#### PolicyService
- `applyPolicy`, `removePolicy`
- `getAppliedPolicies`

---

## 3. OpenAPI REST API 設計

### 3.1 URL 構造

```
/api/v1/                           # 新規 OpenAPI 準拠 REST API
/rest/                             # 既存 REST API（後方互換性維持）
/odata/{repositoryId}/             # OData エンドポイント
/atom/{repositoryId}/              # CMIS AtomPub バインディング（既存）
/browser/{repositoryId}/           # CMIS Browser バインディング（既存）
```

### 3.2 リソース設計方針

#### 3.2.1 Object/Document/Folder の境界設計

CMIS では全てのコンテンツが「オブジェクト」として扱われますが、REST API では以下の方針で設計します：

**設計原則:**
- `/objects/{id}` を**正規（canonical）エンドポイント**として、任意のオブジェクトにアクセス可能
- `/documents` と `/folders` は**便宜的なエンドポイント**として、以下の用途に限定：
  - 作成操作（POST）: タイプ固有のバリデーションと簡略化されたリクエスト
  - タイプ固有操作: チェックアウト/チェックインは `/documents` のみ
  - 検索/一覧: タイプでフィルタされた結果の取得

**使い分けガイドライン:**

| 操作 | 推奨エンドポイント | 理由 |
|------|-------------------|------|
| 任意オブジェクト取得 | `/objects/{id}` | タイプに依存しない汎用アクセス |
| ドキュメント作成 | `/documents` | バージョニング状態の指定が必要 |
| フォルダ作成 | `/folders` | 親フォルダの指定が必須 |
| チェックアウト/チェックイン | `/documents/{id}/checkout` | ドキュメント固有操作 |
| プロパティ更新 | `/objects/{id}` | タイプに依存しない |
| コンテンツ取得/更新 | `/objects/{id}/content` | タイプに依存しない |

### 3.3 プロパティの型表現（動的プロパティ対応）

#### 3.3.1 課題

CMIS の型定義は動的であり、カスタムタイプごとにプロパティが異なります。OpenAPI のスキーマは静的であるため、`additionalProperties: true` を使用すると型情報が失われ、SDK 生成の価値が低下します。

#### 3.3.2 解決策：2層構造プロパティ表現

プロパティを `value` と `type` を含む構造化オブジェクトとして表現します：

```json
{
  "objectId": "OBJECT_ID",
  "objectTypeId": "custom:invoice",
  "baseTypeId": "cmis:document",
  "properties": {
    "cmis:name": {
      "value": "invoice-2026-001.pdf",
      "type": "string",
      "displayName": "Name"
    },
    "cmis:creationDate": {
      "value": "2026-01-19T10:00:00Z",
      "type": "datetime",
      "displayName": "Creation Date"
    },
    "cmis:contentStreamLength": {
      "value": 12345,
      "type": "integer",
      "displayName": "Content Stream Length"
    },
    "custom:invoiceNumber": {
      "value": "INV-2026-001",
      "type": "string",
      "displayName": "Invoice Number"
    },
    "custom:amount": {
      "value": 150000.00,
      "type": "decimal",
      "displayName": "Amount"
    },
    "custom:tags": {
      "value": ["finance", "2026", "Q1"],
      "type": "string",
      "cardinality": "multi",
      "displayName": "Tags"
    }
  }
}
```

#### 3.3.3 プロパティ型マッピング

| CMIS PropertyType | JSON 表現 | type 値 |
|-------------------|-----------|---------|
| string | string | "string" |
| boolean | boolean | "boolean" |
| integer | number (integer) | "integer" |
| decimal | number | "decimal" |
| datetime | string (ISO 8601) | "datetime" |
| uri | string | "uri" |
| id | string | "id" |
| html | string | "html" |

#### 3.3.4 動的スキーマ生成エンドポイント

タイプ定義から OpenAPI スキーマを動的に生成するエンドポイントを提供：

```
GET /api/v1/repositories/{repositoryId}/types/{typeId}/schema
```

**レスポンス例:**
```json
{
  "typeId": "custom:invoice",
  "openApiSchema": {
    "type": "object",
    "properties": {
      "objectId": {"type": "string"},
      "objectTypeId": {"type": "string", "const": "custom:invoice"},
      "properties": {
        "type": "object",
        "properties": {
          "custom:invoiceNumber": {
            "type": "object",
            "properties": {
              "value": {"type": "string"},
              "type": {"type": "string", "const": "string"}
            },
            "required": ["value"]
          },
          "custom:amount": {
            "type": "object",
            "properties": {
              "value": {"type": "number"},
              "type": {"type": "string", "const": "decimal"}
            },
            "required": ["value"]
          }
        },
        "required": ["custom:invoiceNumber", "custom:amount"]
      }
    }
  }
}
```

**SDK 生成ワークフロー:**
1. `/types` エンドポイントでタイプ一覧を取得
2. 各タイプの `/types/{typeId}/schema` でスキーマを取得
3. 取得したスキーマを使用してタイプ固有の DTO を生成

### 3.4 リソース設計

#### 3.4.1 リポジトリ (`/api/v1/repositories`)

| メソッド | パス | 説明 | CMIS 対応 |
|---------|------|------|-----------|
| GET | `/` | リポジトリ一覧取得 | getRepositoryInfos |
| GET | `/{repositoryId}` | リポジトリ情報取得 | getRepositoryInfo |
| GET | `/{repositoryId}/capabilities` | ケイパビリティ取得 | getRepositoryInfo |
| GET | `/{repositoryId}/rootFolder` | ルートフォルダ取得 | - |

#### 3.4.2 オブジェクト (`/api/v1/repositories/{repositoryId}/objects`)

**正規エンドポイント** - 任意のオブジェクトタイプにアクセス可能

| メソッド | パス | 説明 | CMIS 対応 |
|---------|------|------|-----------|
| GET | `/{objectId}` | オブジェクト取得 | getObject |
| GET | `/byPath` | パスでオブジェクト取得 | getObjectByPath |
| POST | `/` | オブジェクト作成 | create |
| PUT | `/{objectId}` | プロパティ更新 | updateProperties |
| PUT | `/bulk` | 一括プロパティ更新 | bulkUpdateProperties |
| DELETE | `/{objectId}` | オブジェクト削除 | deleteObject |
| POST | `/{objectId}/move` | オブジェクト移動 | moveObject |
| GET | `/{objectId}/allowableActions` | 許可アクション取得 | getAllowableActions |
| GET | `/{objectId}/content` | コンテンツストリーム取得 | getContentStream |
| PUT | `/{objectId}/content` | コンテンツストリーム設定 | setContentStream |
| DELETE | `/{objectId}/content` | コンテンツストリーム削除 | deleteContentStream |
| POST | `/{objectId}/content/append` | コンテンツ追記 | appendContentStream |
| GET | `/{objectId}/renditions` | レンディション取得 | getRenditions |
| GET | `/{objectId}/parents` | 親オブジェクト取得 | getObjectParents |
| GET | `/{objectId}/relationships` | リレーションシップ取得 | getObjectRelationships |

#### 3.4.3 コンテンツストリーム操作

##### PUT /objects/{objectId}/content - コンテンツ設定

**リクエスト形式:**

**方式1: Raw Binary（推奨）**
```
PUT /api/v1/repositories/{repoId}/objects/{objectId}/content
Content-Type: application/pdf
Content-Disposition: attachment; filename="document.pdf"
X-Overwrite-Flag: true
X-Change-Token: {changeToken}

[binary content]
```

**方式2: Multipart（メタデータ同時更新時）**
```
POST /api/v1/repositories/{repoId}/objects/{objectId}/content
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary

------WebKitFormBoundary
Content-Disposition: form-data; name="file"; filename="document.pdf"
Content-Type: application/pdf

[binary content]
------WebKitFormBoundary
Content-Disposition: form-data; name="overwriteFlag"

true
------WebKitFormBoundary--
```

**レスポンス:**
```json
{
  "objectId": "OBJECT_ID",
  "changeToken": "NEW_CHANGE_TOKEN",
  "contentStreamLength": 12345,
  "contentStreamMimeType": "application/pdf",
  "contentStreamFileName": "document.pdf"
}
```

##### GET /objects/{objectId}/content - コンテンツ取得

**レスポンスヘッダー:**
```
Content-Type: application/pdf
Content-Disposition: attachment; filename="document.pdf"
Content-Length: 12345
ETag: "CONTENT_HASH"
```

#### 3.4.4 フォルダ (`/api/v1/repositories/{repositoryId}/folders`)

**便宜的エンドポイント** - フォルダ固有の操作とナビゲーション

| メソッド | パス | 説明 | CMIS 対応 |
|---------|------|------|-----------|
| POST | `/` | フォルダ作成 | createFolder |
| GET | `/{folderId}` | フォルダ取得 | getObject |
| GET | `/{folderId}/children` | 子オブジェクト取得 | getChildren |
| GET | `/{folderId}/descendants` | 子孫オブジェクト取得 | getDescendants |
| GET | `/{folderId}/tree` | フォルダツリー取得 | getFolderTree |
| GET | `/{folderId}/parent` | 親フォルダ取得 | getFolderParent |
| DELETE | `/{folderId}/tree` | ツリー削除 | deleteTree |

#### 3.4.5 ドキュメント (`/api/v1/repositories/{repositoryId}/documents`)

**便宜的エンドポイント** - ドキュメント固有の操作（バージョニング）

| メソッド | パス | 説明 | CMIS 対応 |
|---------|------|------|-----------|
| POST | `/` | ドキュメント作成 | createDocument |
| POST | `/fromSource` | コピー作成 | createDocumentFromSource |
| GET | `/{documentId}/versions` | 全バージョン取得 | getAllVersions |
| GET | `/{documentId}/latestVersion` | 最新バージョン取得 | getObjectOfLatestVersion |
| POST | `/{documentId}/checkout` | チェックアウト | checkOut |
| POST | `/{documentId}/cancelCheckout` | チェックアウト取消 | cancelCheckOut |
| POST | `/{documentId}/checkin` | チェックイン | checkIn |
| GET | `/checkedout` | チェックアウト一覧 | getCheckedOutDocs |

##### バージョニング操作のレスポンス仕様

**POST /documents/{documentId}/checkout レスポンス:**

チェックアウト操作は PWC (Private Working Copy) を作成し、その情報を返します。

```json
{
  "pwcId": "PWC_OBJECT_ID",
  "originalObjectId": "ORIGINAL_OBJECT_ID",
  "versionSeriesId": "VERSION_SERIES_ID",
  "versionSeriesCheckedOutId": "PWC_OBJECT_ID",
  "versionSeriesCheckedOutBy": "admin",
  "isVersionSeriesCheckedOut": true,
  "_links": {
    "pwc": {"href": "/api/v1/repositories/bedroom/objects/PWC_OBJECT_ID"},
    "original": {"href": "/api/v1/repositories/bedroom/objects/ORIGINAL_OBJECT_ID"},
    "checkin": {"href": "/api/v1/repositories/bedroom/documents/PWC_OBJECT_ID/checkin"}
  }
}
```

**POST /documents/{documentId}/checkin リクエスト:**

```json
{
  "major": true,
  "checkinComment": "Updated version with corrections",
  "properties": {
    "cmis:description": {"value": "Updated description"}
  }
}
```

**POST /documents/{documentId}/checkin レスポンス:**

チェックイン操作は**新しいバージョンのオブジェクト情報**を返します。PWC ID は無効になります。

```json
{
  "objectId": "NEW_VERSION_OBJECT_ID",
  "versionSeriesId": "VERSION_SERIES_ID",
  "versionLabel": "2.0",
  "isMajorVersion": true,
  "isLatestVersion": true,
  "isLatestMajorVersion": true,
  "checkinComment": "Updated version with corrections",
  "previousVersionId": "PREVIOUS_VERSION_OBJECT_ID",
  "properties": {},
  "_links": {
    "self": {"href": "/api/v1/repositories/bedroom/objects/NEW_VERSION_OBJECT_ID"},
    "versionSeries": {"href": "/api/v1/repositories/bedroom/documents/VERSION_SERIES_ID/versions"},
    "previousVersion": {"href": "/api/v1/repositories/bedroom/objects/PREVIOUS_VERSION_OBJECT_ID"},
    "content": {"href": "/api/v1/repositories/bedroom/objects/NEW_VERSION_OBJECT_ID/content"}
  }
}
```

**POST /documents/{documentId}/cancelCheckout レスポンス:**

```json
{
  "success": true,
  "cancelledPwcId": "PWC_OBJECT_ID",
  "restoredObjectId": "ORIGINAL_OBJECT_ID",
  "versionSeriesId": "VERSION_SERIES_ID",
  "isVersionSeriesCheckedOut": false,
  "_links": {
    "restoredObject": {"href": "/api/v1/repositories/bedroom/objects/ORIGINAL_OBJECT_ID"}
  }
}
```

#### 3.4.6 タイプ (`/api/v1/repositories/{repositoryId}/types`)

| メソッド | パス | 説明 | CMIS 対応 |
|---------|------|------|-----------|
| GET | `/` | タイプ一覧取得 | getTypeChildren |
| GET | `/{typeId}` | タイプ定義取得 | getTypeDefinition |
| GET | `/{typeId}/children` | 子タイプ取得 | getTypeChildren |
| GET | `/{typeId}/descendants` | 子孫タイプ取得 | getTypeDescendants |
| GET | `/{typeId}/schema` | OpenAPI スキーマ取得 | - |
| POST | `/` | タイプ作成 | createType |
| PUT | `/{typeId}` | タイプ更新 | updateType |
| DELETE | `/{typeId}` | タイプ削除 | deleteType |

#### 3.4.7 ACL (`/api/v1/repositories/{repositoryId}/objects/{objectId}/acl`)

| メソッド | パス | 説明 | CMIS 対応 |
|---------|------|------|-----------|
| GET | `/` | ACL 取得 | getAcl |
| PUT | `/` | ACL 適用 | applyAcl |

#### 3.4.8 リレーションシップ (`/api/v1/repositories/{repositoryId}/relationships`)

| メソッド | パス | 説明 | CMIS 対応 |
|---------|------|------|-----------|
| POST | `/` | リレーションシップ作成 | createRelationship |
| GET | `/object/{objectId}` | オブジェクトのリレーションシップ取得 | getObjectRelationships |

#### 3.4.9 ポリシー (`/api/v1/repositories/{repositoryId}/policies`)

| メソッド | パス | 説明 | CMIS 対応 |
|---------|------|------|-----------|
| POST | `/` | ポリシー作成 | createPolicy |
| POST | `/apply` | ポリシー適用 | applyPolicy |
| POST | `/remove` | ポリシー解除 | removePolicy |
| GET | `/object/{objectId}` | 適用ポリシー取得 | getAppliedPolicies |

#### 3.4.10 クエリ (`/api/v1/repositories/{repositoryId}/query`)

| メソッド | パス | 説明 | CMIS 対応 |
|---------|------|------|-----------|
| POST | `/` | CMIS クエリ実行 | query |
| GET | `/changes` | 変更ログ取得 | getContentChanges |

#### 3.4.11 ユーザー (`/api/v1/repositories/{repositoryId}/users`)

既存の `/rest/repo/{repositoryId}/user/` と同等の機能を OpenAPI 準拠で再設計。

| メソッド | パス | 説明 |
|---------|------|------|
| GET | `/` | ユーザー一覧取得 |
| GET | `/{userId}` | ユーザー取得 |
| GET | `/search` | ユーザー検索 |
| POST | `/` | ユーザー作成 |
| PUT | `/{userId}` | ユーザー更新 |
| DELETE | `/{userId}` | ユーザー削除 |
| POST | `/{userId}/password` | パスワード変更 |

#### 3.4.12 グループ (`/api/v1/repositories/{repositoryId}/groups`)

既存の `/rest/repo/{repositoryId}/group/` と同等の機能を OpenAPI 準拠で再設計。

| メソッド | パス | 説明 |
|---------|------|------|
| GET | `/` | グループ一覧取得 |
| GET | `/{groupId}` | グループ取得 |
| GET | `/search` | グループ検索 |
| POST | `/` | グループ作成 |
| PUT | `/{groupId}` | グループ更新 |
| DELETE | `/{groupId}` | グループ削除 |
| POST | `/{groupId}/members` | メンバー追加 |
| DELETE | `/{groupId}/members` | メンバー削除 |

#### 3.4.13 認証 (`/api/v1/auth`)

| メソッド | パス | 説明 |
|---------|------|------|
| POST | `/login` | ログイン（トークン発行） |
| POST | `/logout` | ログアウト（トークン無効化） |
| POST | `/token/refresh` | トークンリフレッシュ |
| POST | `/saml` | SAML 認証 |
| POST | `/oidc` | OIDC 認証 |
| GET | `/me` | 現在のユーザー情報取得 |

### 3.5 共通仕様

#### 3.5.1 認証

```
Authorization: Bearer {token}
```

または

```
Authorization: Basic {base64(username:password)}
```

#### 3.5.2 エラーレスポンス

RFC 7807 (Problem Details for HTTP APIs) 準拠:

```json
{
  "type": "https://nemakiware.org/errors/object-not-found",
  "title": "Object Not Found",
  "status": 404,
  "detail": "The object with ID 'OBJECT_ID' was not found in repository 'bedroom'",
  "instance": "/api/v1/repositories/bedroom/objects/OBJECT_ID"
}
```

**エラーコード一覧:**

| HTTP Status | Type | 説明 |
|-------------|------|------|
| 400 | invalid-argument | 不正なリクエストパラメータ |
| 401 | unauthorized | 認証が必要 |
| 403 | permission-denied | 権限不足 |
| 404 | object-not-found | オブジェクトが見つからない |
| 404 | type-not-found | タイプが見つからない |
| 409 | conflict | 競合（名前重複、バージョン競合など） |
| 409 | content-already-exists | コンテンツが既に存在 |
| 409 | version-conflict | バージョン競合（changeToken 不一致） |
| 422 | constraint-violation | 制約違反 |
| 500 | internal-error | 内部エラー |
| 503 | service-unavailable | サービス利用不可 |

#### 3.5.3 ページネーション

```json
{
  "items": [],
  "hasMoreItems": true,
  "numItems": 1000,
  "pageInfo": {
    "maxItems": 50,
    "skipCount": 0
  },
  "_links": {
    "self": {"href": "...?maxItems=50&skipCount=0"},
    "first": {"href": "...?maxItems=50&skipCount=0"},
    "next": {"href": "...?maxItems=50&skipCount=50"},
    "last": {"href": "...?maxItems=50&skipCount=950"}
  }
}
```

#### 3.5.4 HATEOAS リンク

全てのリソースレスポンスに `_links` セクションを含める:

```json
{
  "_links": {
    "self": {"href": "/api/v1/repositories/bedroom/objects/OBJECT_ID"},
    "parent": {"href": "/api/v1/repositories/bedroom/folders/PARENT_ID"},
    "children": {"href": "/api/v1/repositories/bedroom/folders/OBJECT_ID/children"},
    "content": {"href": "/api/v1/repositories/bedroom/objects/OBJECT_ID/content"},
    "acl": {"href": "/api/v1/repositories/bedroom/objects/OBJECT_ID/acl"},
    "type": {"href": "/api/v1/repositories/bedroom/types/cmis:document"}
  }
}
```

---

## 4. OData 4.0 設計

### 4.1 概要

OData (Open Data Protocol) 4.0 は、RESTful API の上に構築された標準プロトコルで、データのクエリと操作を標準化します。NemakiWare では、コンテンツリポジトリへの柔軟なアクセスを提供するために OData をサポートします。

### 4.2 OData スコープ定義

#### 4.2.1 サポート範囲

| 機能カテゴリ | サポートレベル | 備考 |
|-------------|---------------|------|
| 読み取り操作 | 完全サポート | GET によるエンティティ/コレクション取得 |
| 作成操作 | 完全サポート | POST によるエンティティ作成 |
| 更新操作 | 完全サポート | PUT/PATCH によるプロパティ更新 |
| 削除操作 | 完全サポート | DELETE によるエンティティ削除 |
| アクション | 部分サポート | CheckOut, CheckIn, Move, Copy |
| ファンクション | 部分サポート | GetAllVersions, GetObjectByPath, Query |
| バッチ操作 | 初期リリースでは未サポート | 将来バージョンで検討 |

#### 4.2.2 制限事項

1. **バッチ操作**: `$batch` エンドポイントは初期リリースでは未サポート
2. **デルタクエリ**: `$deltatoken` は未サポート（代わりに CMIS の getContentChanges を使用）
3. **非同期操作**: `Prefer: respond-async` は未サポート

### 4.3 エンドポイント構造

```
/odata/{repositoryId}/              # サービスルート
/odata/{repositoryId}/$metadata     # メタデータドキュメント
/odata/{repositoryId}/{EntitySet}   # エンティティセット
```

### 4.4 動的メタデータ生成

#### 4.4.1 設計方針

OData の `$metadata` エンドポイントは、CMIS タイプ定義に基づいて**動的に生成**されます。これにより、カスタムタイプが OData クライアントから正しく認識されます。

**動的生成の仕組み:**
1. 基本タイプ（Document, Folder, Relationship, Policy, Item）は常に存在
2. カスタムタイプは派生 EntityType として動的に追加
3. タイプ定義の変更時にメタデータを再生成
4. ETag ヘッダーによるキャッシュ制御

#### 4.4.2 メタデータキャッシュ

```
GET /odata/bedroom/$metadata
If-None-Match: "TYPE_HASH_123"
```

**レスポンスヘッダー:**
```
ETag: "TYPE_HASH_456"
Cache-Control: private, max-age=3600
```

タイプ定義が変更されると ETag が更新され、クライアントは新しいメタデータを取得します。

### 4.5 エンティティセット

| エンティティセット | 説明 | 対応 CMIS タイプ |
|-------------------|------|-----------------|
| Objects | 全オブジェクト | cmis:object |
| Documents | ドキュメント | cmis:document |
| Folders | フォルダ | cmis:folder |
| Relationships | リレーションシップ | cmis:relationship |
| Policies | ポリシー | cmis:policy |
| Items | アイテム | cmis:item |
| Types | タイプ定義 | - |
| Users | ユーザー | nemaki:user |
| Groups | グループ | nemaki:group |

### 4.6 クエリオプション

| オプション | 説明 | 例 |
|-----------|------|-----|
| $filter | フィルタ条件 | `$filter=name eq 'test.pdf'` |
| $select | 取得プロパティ | `$select=objectId,name,creationDate` |
| $expand | 関連エンティティ展開 | `$expand=parent,children` |
| $orderby | ソート | `$orderby=creationDate desc` |
| $top | 取得件数制限 | `$top=10` |
| $skip | スキップ件数 | `$skip=20` |
| $count | 件数取得 | `$count=true` |
| $search | 全文検索 | `$search=invoice` |

### 4.7 検索仕様（$search と CMIS Query の関係）

#### 4.7.1 検索機能の整理

| 検索方法 | 用途 | 実装 | 対応 |
|---------|------|------|------|
| `$search` | 全文検索（キーワード） | Solr 全文検索 | OData 標準 |
| `$filter` | プロパティベースフィルタ | CMIS WHERE 句に変換 | OData 標準 |
| `Query()` ファンクション | CMIS SQL 完全サポート | CMIS Query 直接実行 | NemakiWare 拡張 |

#### 4.7.2 $search の動作

`$search` は Solr の全文検索を使用し、ドキュメントのコンテンツとプロパティを検索します。

```
GET /odata/bedroom/Documents?$search=contract
```

**内部処理:**
1. Solr に全文検索クエリを発行
2. マッチしたオブジェクト ID を取得
3. CMIS からオブジェクト情報を取得
4. OData 形式でレスポンスを返却

#### 4.7.3 $filter の動作

`$filter` は CMIS Query の WHERE 句に変換されます。

```
GET /odata/bedroom/Documents?$filter=contentStreamMimeType eq 'application/pdf' and creationDate gt 2026-01-01T00:00:00Z
```

**変換後の CMIS Query:**
```sql
SELECT * FROM cmis:document WHERE cmis:contentStreamMimeType = 'application/pdf' AND cmis:creationDate > TIMESTAMP '2026-01-01T00:00:00.000Z'
```

#### 4.7.4 Query() ファンクションの動作

CMIS SQL を直接実行する場合は `Query()` ファンクションを使用します。

```
GET /odata/bedroom/Query(statement='SELECT * FROM cmis:document WHERE CONTAINS("contract")',searchAllVersions=false,maxItems=100)
```

### 4.8 ACL/権限の表現

#### 4.8.1 権限チェックの動作

OData は標準的に ACL を持たないため、NemakiWare では以下の動作を定義します。

| シナリオ | HTTP Status | 動作 |
|---------|-------------|------|
| 特定オブジェクトへのアクセス権限なし | 403 Forbidden | エラーレスポンスを返却 |
| オブジェクトが存在しない | 404 Not Found | エラーレスポンスを返却 |
| コレクション取得時に権限のないオブジェクト | - | 結果から除外（エラーなし） |

#### 4.8.2 コレクション取得時の権限フィルタ

`$filter` や `$search` でコレクションを取得する場合、ユーザーが読み取り権限を持つオブジェクトのみが返されます。

**レスポンスヘッダー:**
```
X-Total-Count: 150
X-Accessible-Count: 120
```

- `X-Total-Count`: フィルタ条件にマッチした総件数（権限チェック前）
- `X-Accessible-Count`: ユーザーがアクセス可能な件数

**注意:** `X-Total-Count` は管理者のみに返却されます。一般ユーザーには `X-Accessible-Count` のみが返却されます。

#### 4.8.3 権限エラーレスポンス

```json
{
  "error": {
    "code": "PermissionDenied",
    "message": "You do not have permission to access this object",
    "target": "Documents('OBJECT_ID')",
    "details": [
      {
        "code": "InsufficientPermission",
        "message": "Required permission: cmis:read"
      }
    ]
  }
}
```

### 4.9 アクションとファンクション

#### 4.9.1 アクション（副作用あり）

| アクション | 説明 | バインド先 |
|-----------|------|-----------|
| CheckOut | チェックアウト | Document |
| CancelCheckOut | チェックアウト取消 | Document |
| CheckIn | チェックイン | Document |
| Move | 移動 | Object |
| Copy | コピー | Document |
| ApplyAcl | ACL 適用 | Object |

**例:**
```
POST /odata/bedroom/Documents('OBJECT_ID')/NemakiWare.CMIS.CheckOut
```

#### 4.9.2 ファンクション（副作用なし）

| ファンクション | 説明 | バインド先 |
|---------------|------|-----------|
| GetAllVersions | 全バージョン取得 | Document |
| GetObjectByPath | パスでオブジェクト取得 | EntitySet |
| Query | CMIS クエリ実行 | EntitySet |

**例:**
```
GET /odata/bedroom/Documents('OBJECT_ID')/NemakiWare.CMIS.GetAllVersions()
GET /odata/bedroom/GetObjectByPath(path='/folder/document.pdf')
GET /odata/bedroom/Query(statement='SELECT * FROM cmis:document',maxItems=100)
```

---

## 5. API 互換性ポリシー

### 5.1 バージョニング方針

#### 5.1.1 セマンティックバージョニング

API バージョンは URL パスに含まれ、セマンティックバージョニングに従います。

```
/api/v1/...  # メジャーバージョン 1
/api/v2/...  # メジャーバージョン 2（将来）
```

#### 5.1.2 バージョン間の互換性

| 変更タイプ | 互換性 | 例 |
|-----------|--------|-----|
| 新規エンドポイント追加 | 後方互換 | 新しいリソースの追加 |
| 新規オプションパラメータ追加 | 後方互換 | クエリパラメータの追加 |
| 新規レスポンスフィールド追加 | 後方互換 | JSON フィールドの追加 |
| 必須パラメータの追加 | 破壊的変更 | メジャーバージョンアップ必要 |
| エンドポイントの削除 | 破壊的変更 | メジャーバージョンアップ必要 |
| レスポンス形式の変更 | 破壊的変更 | メジャーバージョンアップ必要 |

### 5.2 非推奨ポリシー

#### 5.2.1 非推奨の通知

非推奨となった機能は、レスポンスヘッダーで通知されます。

```
Deprecation: true
Sunset: Sat, 01 Jan 2028 00:00:00 GMT
Link: </api/v2/...>; rel="successor-version"
```

#### 5.2.2 非推奨期間

- 非推奨から削除まで: 最低 2 メジャーリリース（約 12 ヶ月）
- 非推奨期間中は機能は維持される
- 削除予定日は `Sunset` ヘッダーで通知

### 5.3 既存 REST API との互換性

#### 5.3.1 互換性方針

**明確な方針: `/rest/` と `/api/v1/` は互換性なし**

| 項目 | `/rest/` (既存) | `/api/v1/` (新規) |
|------|----------------|-------------------|
| レスポンス形式 | 独自形式 | OpenAPI 準拠 |
| エラー形式 | 独自形式 | RFC 7807 準拠 |
| プロパティ表現 | フラット | 2層構造（value/type） |
| ページネーション | 独自形式 | 標準形式 |

#### 5.3.2 移行サポート

1. `/rest/` エンドポイントは維持（非推奨化なし）
2. 新規開発は `/api/v1/` を推奨
3. 移行ガイドを提供（セクション 8 参照）

---

## 6. OpenAPI スキーマ戦略

### 6.1 スキーマ設計方針

#### 6.1.1 基本タイプのスキーマ

CMIS 標準タイプ（cmis:document, cmis:folder 等）は静的スキーマとして定義します。

```yaml
components:
  schemas:
    CmisObject:
      type: object
      required:
        - objectId
        - objectTypeId
        - baseTypeId
      properties:
        objectId:
          type: string
        objectTypeId:
          type: string
        baseTypeId:
          type: string
        properties:
          $ref: '#/components/schemas/PropertyMap'
        allowableActions:
          $ref: '#/components/schemas/AllowableActions'
        _links:
          $ref: '#/components/schemas/Links'

    PropertyMap:
      type: object
      additionalProperties:
        $ref: '#/components/schemas/PropertyValue'

    PropertyValue:
      type: object
      required:
        - value
        - type
      properties:
        value:
          oneOf:
            - type: string
            - type: number
            - type: boolean
            - type: array
              items:
                oneOf:
                  - type: string
                  - type: number
                  - type: boolean
        type:
          type: string
          enum:
            - string
            - boolean
            - integer
            - decimal
            - datetime
            - uri
            - id
            - html
        displayName:
          type: string
        cardinality:
          type: string
          enum:
            - single
            - multi
```

#### 6.1.2 カスタムタイプのスキーマ

カスタムタイプは動的スキーマ生成エンドポイントで取得します。

```
GET /api/v1/repositories/{repositoryId}/types/{typeId}/schema
```

### 6.2 SDK 生成における不確実性の取り扱い

#### 6.2.1 推奨ワークフロー

1. **静的 SDK 生成**: 基本 OpenAPI 仕様から SDK を生成
2. **動的型情報取得**: 実行時にタイプ定義 API から型情報を取得
3. **型安全なアクセス**: 取得した型情報を使用してプロパティにアクセス

#### 6.2.2 SDK 生成時の注意事項

```yaml
# openapi.yaml での注釈
x-nemakiware-dynamic-properties:
  description: |
    The 'properties' field contains dynamic properties based on the object's type.
    Use the /types/{typeId}/schema endpoint to get the exact schema for a specific type.
    SDK generators should treat this as a generic map and provide helper methods
    for type-safe property access.
```

---

## 7. 実装計画

### 7.1 技術スタック

| コンポーネント | 技術 | 備考 |
|---------------|------|------|
| REST フレームワーク | Jersey (JAX-RS) | 既存と同じ |
| OpenAPI 生成 | Swagger/OpenAPI 3.0 | swagger-jaxrs2 |
| OData ライブラリ | Apache Olingo 4.x | OData 4.0 対応 |
| JSON 処理 | Jackson | 既存と同じ |
| ドキュメント UI | Swagger UI | API ドキュメント |
| REST テスト | REST Assured | 流暢な API テスト |
| OData テスト | Apache Olingo Client | OData クライアントテスト |
| API 仕様検証 | Swagger Request Validator | OpenAPI 仕様との整合性検証 |

### 7.2 フェーズ分け

#### フェーズ 1: OpenAPI 基盤整備（2-3週間）

1. OpenAPI 3.0 仕様ファイル（openapi.yaml）の作成
2. Swagger UI の統合
3. 共通エラーハンドリングの実装
4. 認証フィルタの整備
5. プロパティ 2 層構造の DTO 実装

#### フェーズ 2: CMIS 操作の REST 化（4-6週間）

1. ObjectService の REST 化
   - オブジェクト CRUD
   - コンテンツストリーム操作（Raw Binary / Multipart 対応）
2. NavigationService の REST 化
   - フォルダナビゲーション
3. VersioningService の REST 化
   - チェックアウト/チェックイン（レスポンス形式の明確化）
4. DiscoveryService の REST 化
   - クエリ実行
5. その他サービスの REST 化

#### フェーズ 3: OData 対応（3-4週間）

1. Apache Olingo の統合
2. 動的メタデータ生成の実装
3. OData サービスプロバイダの実装
4. クエリオプションの実装（$filter → CMIS Query 変換）
5. $search と Solr の統合
6. アクション/ファンクションの実装
7. 権限フィルタの実装

#### フェーズ 4: テストと文書化（2-3週間）

1. 単体テスト
2. 統合テスト
3. OData 準拠テスト（セクション 9 参照）
4. API ドキュメントの完成
5. マイグレーションガイドの作成

### 7.3 ディレクトリ構造

```
core/src/main/java/jp/aegif/nemaki/
├── api/                          # 新規 OpenAPI REST API
│   ├── v1/
│   │   ├── ApiApplication.java   # JAX-RS Application
│   │   ├── resource/
│   │   │   ├── RepositoryResource.java
│   │   │   ├── ObjectResource.java
│   │   │   ├── FolderResource.java
│   │   │   ├── DocumentResource.java
│   │   │   ├── TypeResource.java
│   │   │   ├── AclResource.java
│   │   │   ├── QueryResource.java
│   │   │   ├── UserResource.java
│   │   │   ├── GroupResource.java
│   │   │   └── AuthResource.java
│   │   ├── model/
│   │   │   ├── PropertyValue.java    # 2層構造プロパティ
│   │   │   ├── request/              # リクエスト DTO
│   │   │   └── response/             # レスポンス DTO
│   │   ├── filter/
│   │   │   ├── AuthenticationFilter.java
│   │   │   └── CorsFilter.java
│   │   └── exception/
│   │       ├── ApiExceptionMapper.java
│   │       └── ProblemDetail.java
│   └── odata/
│       ├── ODataServlet.java
│       ├── NemakiEdmProvider.java        # 動的メタデータ生成
│       ├── NemakiEntityCollectionProcessor.java
│       ├── NemakiEntityProcessor.java
│       ├── NemakiActionProcessor.java
│       ├── NemakiSearchHandler.java      # $search 処理
│       └── NemakiPermissionFilter.java   # 権限フィルタ
├── rest/                         # 既存 REST API（維持）
├── cmis/                         # 既存 CMIS 実装
└── businesslogic/                # ビジネスロジック
```

---

## 8. 移行ガイドライン

### 8.1 既存 REST API からの移行

#### 8.1.1 エンドポイントマッピング

| 既存パス | 新規パス | 変更点 |
|---------|---------|--------|
| `/rest/repo/{repoId}/user/list` | `/api/v1/repositories/{repoId}/users` | GET メソッド、レスポンス形式 |
| `/rest/repo/{repoId}/user/show/{id}` | `/api/v1/repositories/{repoId}/users/{id}` | パス構造 |
| `/rest/repo/{repoId}/user/create/{id}` | `/api/v1/repositories/{repoId}/users` | POST メソッド、ID は自動生成可 |
| `/rest/repo/{repoId}/group/list` | `/api/v1/repositories/{repoId}/groups` | GET メソッド、レスポンス形式 |
| `/rest/repo/{repoId}/type/list` | `/api/v1/repositories/{repoId}/types` | GET メソッド、レスポンス形式 |
| `/rest/repo/{repoId}/node/{id}/acl` | `/api/v1/repositories/{repoId}/objects/{id}/acl` | パス構造 |

#### 8.1.2 レスポンス形式の変更

**既存 REST API:**
```json
{
  "status": "success",
  "user": {
    "userId": "user1",
    "userName": "User One",
    "email": "user1@example.com"
  }
}
```

**新規 REST API:**
```json
{
  "userId": "user1",
  "userName": "User One",
  "email": "user1@example.com",
  "_links": {
    "self": {"href": "/api/v1/repositories/bedroom/users/user1"}
  }
}
```

### 8.2 CMIS バインディングからの移行

CMIS Browser Binding と新規 REST API の対応:

| CMIS Browser Binding | 新規 REST API |
|---------------------|---------------|
| `GET /browser/{repoId}/root?cmisselector=children` | `GET /api/v1/repositories/{repoId}/folders/{folderId}/children` |
| `POST /browser/{repoId}?cmisaction=createDocument` | `POST /api/v1/repositories/{repoId}/documents` |
| `GET /browser/{repoId}/root?cmisselector=object&objectId=xxx` | `GET /api/v1/repositories/{repoId}/objects/{objectId}` |
| `POST /browser/{repoId}?cmisaction=checkOut&objectId=xxx` | `POST /api/v1/repositories/{repoId}/documents/{documentId}/checkout` |
| `POST /browser/{repoId}?cmisaction=checkIn&objectId=xxx` | `POST /api/v1/repositories/{repoId}/documents/{documentId}/checkin` |
| `POST /browser/{repoId}?cmisaction=query` | `POST /api/v1/repositories/{repoId}/query` |

### 8.3 後方互換性

- 既存の `/rest/` エンドポイントは維持（非推奨化なし）
- 既存の CMIS バインディング（`/atom/`, `/browser/`）は維持
- 新規開発は `/api/v1/` を推奨

---

## 9. テスト計画

### 9.1 単体テスト

- 各 Resource クラスのテスト
- DTO 変換のテスト（特にプロパティ 2 層構造）
- バリデーションのテスト
- エラーハンドリングのテスト

### 9.2 統合テスト

- エンドツーエンドの API テスト
- 認証・認可のテスト
- OData クエリのテスト
- コンテンツストリーム操作のテスト

### 9.3 OData 準拠テスト

#### 9.3.1 テスト戦略

OData には CMIS TCK のような単一公式 TCK がないため、以下の戦略でテストを実施します。

#### 9.3.2 $metadata 検証

1. **スキーマ検証**: OData CSDL スキーマに対する検証
2. **動的生成検証**: カスタムタイプ追加後のメタデータ再生成確認
3. **ETag 検証**: キャッシュ制御の動作確認

```java
@Test
void testMetadataValidation() {
    // $metadata を取得
    String metadata = client.getMetadata();
    
    // OData CSDL スキーマに対して検証
    CsdlValidator.validate(metadata);
    
    // 必須要素の存在確認
    assertContains(metadata, "EntityType Name=\"Document\"");
    assertContains(metadata, "EntitySet Name=\"Documents\"");
}
```

#### 9.3.3 クエリオプションテスト

| テスト項目 | テスト内容 |
|-----------|-----------|
| $filter | 各演算子（eq, ne, gt, lt, contains 等）の動作確認 |
| $select | プロパティ選択の動作確認 |
| $expand | ナビゲーションプロパティ展開の動作確認 |
| $orderby | ソート（昇順/降順）の動作確認 |
| $top/$skip | ページネーションの動作確認 |
| $count | 件数取得の動作確認 |
| $search | 全文検索の動作確認 |

```java
@Test
void testFilterOperators() {
    // eq 演算子
    var result = client.get("/odata/bedroom/Documents?$filter=name eq 'test.pdf'");
    assertEquals(1, result.getValue().size());
    
    // contains 演算子
    result = client.get("/odata/bedroom/Documents?$filter=contains(name,'test')");
    assertTrue(result.getValue().size() > 0);
    
    // 複合条件
    result = client.get("/odata/bedroom/Documents?$filter=name eq 'test.pdf' and creationDate gt 2026-01-01T00:00:00Z");
    // ...
}
```

#### 9.3.4 結果整合性テスト

OData API と既存 CMIS/REST API の結果を比較し、整合性を確認します。

```java
@Test
void testResultConsistency() {
    // OData で取得
    var odataResult = odataClient.get("/odata/bedroom/Documents('DOC_ID')");
    
    // CMIS で取得
    var cmisResult = cmisSession.getObject("DOC_ID");
    
    // プロパティの整合性確認
    assertEquals(cmisResult.getName(), odataResult.getProperty("name"));
    assertEquals(cmisResult.getCreatedBy(), odataResult.getProperty("createdBy"));
}
```

#### 9.3.5 OData クライアントライブラリテスト

複数の OData クライアントライブラリでの動作確認:

1. **Simple.OData.Client** (.NET)
2. **Apache Olingo Client** (Java)
3. **datajs** (JavaScript)

### 9.4 互換性テスト

- 既存 REST API との互換性（並行稼働確認）
- CMIS TCK との互換性（既存機能の維持確認）

---

## 10. セキュリティ考慮事項

### 10.1 認証

1. **Bearer Token 認証**: JWT ベースのトークン認証
2. **Basic 認証**: 後方互換性のため維持
3. **SAML/OIDC**: エンタープライズ SSO 対応

### 10.2 認可

1. **CMIS ACL**: オブジェクトレベルのアクセス制御
2. **API レベル認可**: 管理者専用エンドポイントの保護
3. **リポジトリアクセス制御**: リポジトリ単位のアクセス制限

### 10.3 入力検証

1. **パラメータ検証**: OpenAPI スキーマに基づく検証
2. **CMIS 制約チェック**: タイプ定義に基づく検証
3. **サニタイズ**: XSS/インジェクション対策

### 10.4 レート制限

1. **API レート制限**: 過負荷防止
2. **クエリ制限**: 大量データ取得の制限

---

## 11. 変更履歴

| バージョン | 日付 | 変更内容 | 作成者 |
|-----------|------|---------|--------|
| 1.0 | 2026-01-19 | 初版作成 | Devin |
| 1.1 | 2026-01-19 | レビューフィードバック反映 | Devin |
|     |            | - プロパティ 2 層構造の明確化 |  |
|     |            | - Object/Document/Folder 境界の整理 |  |
|     |            | - バージョニングレスポンス形式の明確化 |  |
|     |            | - Content Stream 形式の明確化 |  |
|     |            | - OData 動的メタデータ生成の追加 |  |
|     |            | - OData 権限処理の明確化 |  |
|     |            | - $search と CMIS Query の関係整理 |  |
|     |            | - 移行互換性ポリシーの明確化 |  |
|     |            | - OData テスト戦略の具体化 |  |
|     |            | - API 互換性ポリシーセクション追加 |  |
|     |            | - OData スコープ定義セクション追加 |  |
|     |            | - OpenAPI スキーマ戦略セクション追加 |  |
|     |            | - テストツール（REST Assured, Olingo Client）追加 |  |

---

*本設計書は NemakiWare プロジェクトの一部として GNU AGPL v3 ライセンスの下で公開されます。*

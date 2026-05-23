# 手動検証手順書 — コネクタまわり (3.1.1-RC6.4 時点)

`release/3.1.1-RC6` (タグ `v3.1.1-RC6.4`) を対象に、コネクタ /
インポートプロファイル / ガバナンス / Simulate 関連の手動検証手順を
まとめたもの。各セクションは **目的 → API (curl) → UI → 期待結果**
の順で構成。複数 RC にまたがる累積機能を 1 パスで触れるように
組んである。

各ステップは原則として独立に実行できるが、§1 (環境準備) と
§2 (フィクスチャ作成) は最初に必ず実施すること。

---

## 0. 前提条件

| 項目 | 値 |
|---|---|
| サーバ | http://localhost:8080 |
| Admin 資格情報 | `admin:admin` |
| リポジトリ | `bedroom` |
| CSRF | state-changing REST には `X-Requested-With: XMLHttpRequest` ヘッダ必須 |
| 認証 | Basic auth (curl `-u`) は OK だが、CSRF バイパスはしない (CLAUDE.md の CSRF 節参照) |

> **注**: `/v1/admin/...` は Spring MVC dispatcher、`CsrfInterceptor`
> が POST/PUT/DELETE で起動する。`X-Requested-With` を忘れると 403。
> `/core/atom/...` (CMIS Browser Binding) は CSRF 検証なし。

### 共通 shell 設定

```bash
NW=http://localhost:8080
AUTH="-u admin:admin"
H="-H X-Requested-With:XMLHttpRequest"
JSON="-H Content-Type:application/json"
REPO=bedroom
```

以降のすべての curl で `$NW $AUTH $H $JSON` を組み合わせて使う。

---

## 1. 環境準備

### 1.1 サーバ ヘルスチェック

```bash
curl -s -o /dev/null -w "atom %{http_code} (%{time_total}s)\n" $AUTH $NW/core/atom/$REPO
# expect: atom 200
```

UI 確認: ブラウザで `http://localhost:8080/core/ui/` → `admin / admin`
でログイン → 「ドキュメント」一覧が表示される。

### 1.2 アダプタ登録状況の確認

```bash
curl -s $AUTH $NW/core/api/v1/admin/connectors/adapter-registry | jq 'keys | length'
# expect: 11 以上 (IMAP / Gmail / M365Mail / Notion / Salesforce /
# Slack / Teams / Mattermost / Chatwork / Box / Dropbox + 任意の
# レガシー file_share)
```

---

## 2. テストフィクスチャ作成

以降の検証で使うユーザー / グループ / フォルダ。

### 2.1 テストユーザー作成 (admin と委譲ユーザー)

API v1 (`/api/v1/cmis/repositories/{repositoryId}/users`) を使う。

```bash
# 委譲対象の folder owner
curl -s $AUTH $H $JSON -X POST \
  -d '{"userId":"folder-owner","userName":"Folder Owner","password":"changeme01"}' \
  $NW/core/api/v1/cmis/repositories/$REPO/users | jq .

# 一般ユーザー (グループ メンバーシップ用)
curl -s $AUTH $H $JSON -X POST \
  -d '{"userId":"team-member","userName":"Team Member","password":"changeme01"}' \
  $NW/core/api/v1/cmis/repositories/$REPO/users | jq .
```

UI 確認: 管理メニュー → ユーザー → 2 件が一覧に表示。

> パスワードは BCrypt ハッシュで保存される。確認: GET
> `/api/v1/cmis/repositories/{repo}/users/{userId}` で `password`
> が `$2a$...` 形式。

### 2.2 テストグループ作成 + メンバー追加

```bash
# グループ作成
curl -s $AUTH $H $JSON -X POST \
  -d '{"groupId":"team-alpha","groupName":"Team Alpha"}' \
  $NW/core/api/v1/cmis/repositories/$REPO/groups | jq .

# メンバー追加 (users = ユーザー ID 配列、groups = 子グループ ID 配列)
curl -s $AUTH $H $JSON -X POST \
  -d '{"users":["team-member","folder-owner"]}' \
  $NW/core/api/v1/cmis/repositories/$REPO/groups/team-alpha/members | jq .
```

UI 確認: 管理メニュー → グループ → `team-alpha` をクリックし、
メンバーに 2 ユーザーが入っている。

### 2.3 テストフォルダ作成 + `cmis:all` 付与

```bash
# Root folder ID を取得 (bedroom)
ROOT=$(curl -s $AUTH "$NW/core/browser/$REPO/root?cmisselector=object" | jq -r .properties.\"cmis:objectId\".value)
echo "ROOT=$ROOT"

# 委譲対象フォルダ
TARGET_FOLDER=$(curl -s $AUTH -X POST \
  -F "cmisaction=createFolder" \
  -F "propertyId[0]=cmis:objectTypeId" -F "propertyValue[0]=cmis:folder" \
  -F "propertyId[1]=cmis:name"         -F "propertyValue[1]=delegated-inbox" \
  "$NW/core/browser/$REPO/root?objectId=$ROOT" | jq -r .properties.\"cmis:objectId\".value)
echo "TARGET_FOLDER=$TARGET_FOLDER"

# folder-owner に cmis:all を付与
curl -s $AUTH -X POST \
  -F "cmisaction=applyACL" \
  -F "principalId[0]=folder-owner" -F "permission[0]=cmis:all" \
  -F "ACLPropagation=propagate" \
  "$NW/core/browser/$REPO/root?objectId=$TARGET_FOLDER" | jq .
```

UI 確認: ドキュメント → `delegated-inbox` フォルダが見える → ACL 画面
で `folder-owner` が `cmis:all` を持つことを確認。

---

## 3. Connector CRUD + Secret masking (admin)

### 3.1 connector 一覧 (空状態の確認)

```bash
curl -s $AUTH "$NW/core/api/v1/admin/connectors?repositoryId=$REPO" | jq '. | length'
```

UI 確認: 管理 → 統合設定 → 「コネクタ ベータ」タブ → 既存の
default cloud-drive 系コネクタが見える(初期パッチで投入されている)。

### 3.2 connector 作成

最小限の FILE_SHARE コネクタを作成。

```bash
curl -s $AUTH $H $JSON -X POST -d "$(cat <<EOF
{
  "connectorId": "verify-fs-1",
  "displayName": "Verify FS 1",
  "sourceArchetype": "FILE_SHARE",
  "sourceSystem": "google_drive",
  "adapterKind": "google_drive",
  "authType": "oauth2",
  "credentialRef": "secret://test/verify-fs-1",
  "endpoint": "https://www.googleapis.com/drive/v3",
  "rateLimitRpm": 60,
  "enabled": true,
  "delegated": false
}
EOF
)" "$NW/core/api/v1/admin/connectors?repositoryId=$REPO" | jq .
```

UI 確認: コネクタ ベータ タブで `verify-fs-1` が新規行として
表示される。

期待: HTTP 200 + 返却 JSON で `connectorId=verify-fs-1`、
`credentialRef` が **空または [configured]** にマスクされている
こと。

### 3.3 secret masking 確認 (GET)

```bash
curl -s $AUTH "$NW/core/api/v1/admin/connectors/verify-fs-1?repositoryId=$REPO" \
  | jq '{credentialRef, webhookSecret}'
# expect: {"credentialRef":"[configured]","webhookSecret":null}
# あるいは credentialRef が空文字。secret 本体は絶対に返らない。
```

UI 確認: コネクタ ベータ タブ → `verify-fs-1` を編集 → 「資格情報
参照 (credentialRef)」フィールドが `[configured]` 表示で、編集
しない限り元の値が保たれる。

### 3.4 PUT partial payload で scope clobber しないこと

`allowedFolderIds` を omit (null) して PUT した場合に既存値が
保持されることを確認 (RC4.x の review fix)。

```bash
# 一旦 allowedFolderIds に値を入れる
curl -s $AUTH $H $JSON -X PUT -d "$(cat <<EOF
{
  "connectorId": "verify-fs-1",
  "displayName": "Verify FS 1",
  "sourceArchetype": "FILE_SHARE",
  "sourceSystem": "google_drive",
  "adapterKind": "google_drive",
  "authType": "oauth2",
  "endpoint": "https://www.googleapis.com/drive/v3",
  "allowedFolderIds": ["$TARGET_FOLDER"],
  "allowedPrincipalIds": ["folder-owner"],
  "delegated": true
}
EOF
)" "$NW/core/api/v1/admin/connectors/verify-fs-1?repositoryId=$REPO" | jq '{allowedFolderIds, allowedPrincipalIds, delegated}'

# PUT で allowedFolderIds を omit (null) — 既存値が残るはず
curl -s $AUTH $H $JSON -X PUT -d "$(cat <<EOF
{
  "connectorId": "verify-fs-1",
  "displayName": "Verify FS 1 (renamed)",
  "sourceArchetype": "FILE_SHARE",
  "sourceSystem": "google_drive",
  "adapterKind": "google_drive",
  "authType": "oauth2",
  "endpoint": "https://www.googleapis.com/drive/v3",
  "delegated": true
}
EOF
)" "$NW/core/api/v1/admin/connectors/verify-fs-1?repositoryId=$REPO" | jq '{allowedFolderIds, allowedPrincipalIds, displayName}'
# expect: allowedFolderIds と allowedPrincipalIds は前回値を保持、
# displayName だけ更新される。

# 明示的に [] で clear したいときは [] を指定する
curl -s $AUTH $H $JSON -X PUT -d "$(cat <<EOF
{
  "connectorId": "verify-fs-1",
  "displayName": "Verify FS 1",
  "sourceArchetype": "FILE_SHARE",
  "sourceSystem": "google_drive",
  "adapterKind": "google_drive",
  "authType": "oauth2",
  "endpoint": "https://www.googleapis.com/drive/v3",
  "allowedFolderIds": [],
  "allowedPrincipalIds": [],
  "delegated": true
}
EOF
)" "$NW/core/api/v1/admin/connectors/verify-fs-1?repositoryId=$REPO" | jq '{allowedFolderIds, allowedPrincipalIds}'
# expect: 両方 [] にクリアされる
```

> ⚠ `allowedFolderIds=[]` かつ `delegated=true` は「委譲不可」
> として扱われる(誤設定での広域委譲を防ぐため)。後続 §4 で
> 確認する。

---

## 4. Connector 委譲フラグ

RC3 で導入された「フォルダオーナーへの委譲」を構成する 4 フラグ:

| フラグ | デフォルト | 効果 |
|---|---|---|
| `delegated` | false | true で「admin 以外も使える可能性のあるコネクタ」と宣言 |
| `allowedFolderIds` | null | 委譲先フォルダ ID の許可リスト(配下も含む) |
| `allowedPrincipalIds` | null | 委譲先プリンシパル(user / group)の許可リスト |
| `delegateAllFolders` | false | true でリポジトリ全体に委譲。`allowedFolderIds` 無視 |

### 4.1 適正委譲のセットアップ

```bash
curl -s $AUTH $H $JSON -X PUT -d "$(cat <<EOF
{
  "connectorId": "verify-fs-1",
  "displayName": "Verify FS 1",
  "sourceArchetype": "FILE_SHARE",
  "sourceSystem": "google_drive",
  "adapterKind": "google_drive",
  "authType": "oauth2",
  "endpoint": "https://www.googleapis.com/drive/v3",
  "delegated": true,
  "allowedFolderIds": ["$TARGET_FOLDER"],
  "allowedPrincipalIds": ["folder-owner", "team-alpha"]
}
EOF
)" "$NW/core/api/v1/admin/connectors/verify-fs-1?repositoryId=$REPO" \
  | jq '{delegated, allowedFolderIds, allowedPrincipalIds, delegateAllFolders}'
```

UI 確認: コネクタ ベータ タブ → `verify-fs-1` 行の「委譲」列に
バッジ表示。編集モーダルで `delegated` チェック ON、許可フォルダ /
プリンシパルが入っている。

### 4.2 委譲不可パターン: allowedFolderIds 空

```bash
curl -s $AUTH $H $JSON -X PUT -d "$(cat <<EOF
{
  "connectorId": "verify-fs-1",
  "displayName": "Verify FS 1",
  "sourceArchetype": "FILE_SHARE",
  "sourceSystem": "google_drive",
  "adapterKind": "google_drive",
  "authType": "oauth2",
  "endpoint": "https://www.googleapis.com/drive/v3",
  "delegated": true,
  "allowedFolderIds": [],
  "allowedPrincipalIds": ["folder-owner"]
}
EOF
)" "$NW/core/api/v1/admin/connectors/verify-fs-1?repositoryId=$REPO" > /dev/null

# summary で委譲フォルダ問い合わせ → このフォルダで使える connector
# として返ってこないはず
curl -s $AUTH "$NW/core/api/v1/admin/connectors/summary?repositoryId=$REPO&targetFolderId=$TARGET_FOLDER" \
  | jq '[.[] | select(.connectorId == "verify-fs-1")] | length'
# expect: 0
```

→ 適正委譲に戻す: §4.1 を再実行。

### 4.3 delegateAllFolders=true の警告

```bash
curl -s $AUTH $H $JSON -X PUT -d "$(cat <<EOF
{
  "connectorId": "verify-fs-1",
  "displayName": "Verify FS 1",
  "sourceArchetype": "FILE_SHARE",
  "sourceSystem": "google_drive",
  "adapterKind": "google_drive",
  "authType": "oauth2",
  "endpoint": "https://www.googleapis.com/drive/v3",
  "delegated": true,
  "delegateAllFolders": true,
  "allowedPrincipalIds": ["folder-owner"]
}
EOF
)" "$NW/core/api/v1/admin/connectors/verify-fs-1?repositoryId=$REPO" | jq .
```

UI 確認: 編集モーダルで `delegateAllFolders` ON にすると
「リポジトリ全体に委譲されます。本当によろしいですか」相当の
警告/Tooltip が表示される(credential reach が repo-wide に
広がる旨)。

→ 検証後は §4.1 の適正委譲に戻すこと。

### 4.4 summary endpoint で slim DTO 確認

`/summary` は secret / endpoint / scope を一切含まない安全な
public-facing DTO(folder-owner 等の非 admin からも触れる)。

```bash
curl -s $AUTH "$NW/core/api/v1/admin/connectors/summary?repositoryId=$REPO&targetFolderId=$TARGET_FOLDER" \
  | jq '.[] | {connectorId, displayName, sourceArchetype, sourceSystem}'
# expect: credentialRef / endpoint / webhookSecret は含まれない
```

---

## 5. Import Profile — admin

`/v1/admin/import-profiles` は admin と委譲ユーザー両方が触れる
が、許可される操作 / フィールドが異なる。本セクションは admin
視点。

### 5.1 admin プロファイル作成

```bash
curl -s $AUTH $H $JSON -X POST -d "$(cat <<EOF
{
  "profileId": "admin-fs-profile",
  "displayName": "Admin FS Profile",
  "repositoryId": "$REPO",
  "targetFolderId": "$TARGET_FOLDER",
  "allowedArchetypes": ["FILE_SHARE"],
  "allowedConnectorIds": ["verify-fs-1"],
  "defaultConnectorId": "verify-fs-1",
  "dedupePolicy": "skip_if_same_version",
  "updatePolicy": "version_up_on_content_change",
  "versioningPolicy": "major",
  "enabled": true,
  "schedulerEnabled": false,
  "defaultProfile": false
}
EOF
)" $NW/core/api/v1/admin/import-profiles | jq .
```

UI 確認: 統合設定 → 「インポートプロファイル」タブ →
`admin-fs-profile` が一覧。`createdByUserId=admin`、`delegated=false`。

### 5.2 schedulerEnabled トグル + defaultProfile 検証

```bash
# scheduler を ON
curl -s $AUTH $H $JSON -X PUT -d "$(cat <<EOF
{
  "profileId": "admin-fs-profile",
  "displayName": "Admin FS Profile",
  "repositoryId": "$REPO",
  "targetFolderId": "$TARGET_FOLDER",
  "allowedArchetypes": ["FILE_SHARE"],
  "allowedConnectorIds": ["verify-fs-1"],
  "defaultConnectorId": "verify-fs-1",
  "enabled": true,
  "schedulerEnabled": true,
  "schedulerParams": {"intervalSeconds": "300"}
}
EOF
)" "$NW/core/api/v1/admin/import-profiles/admin-fs-profile" \
  | jq '{schedulerEnabled, schedulerParams}'

# scheduler status を確認 (admin only)
curl -s $AUTH "$NW/core/api/v1/admin/ingest-scheduler/status" \
  | jq '.[] | select(.profileId == "admin-fs-profile")'
```

UI 確認: 「スケジューラ状態」タブで `admin-fs-profile` が
running として表示される。

### 5.3 defaultProfile 重複拒否

```bash
# admin-fs-profile を defaultProfile=true に
curl -s $AUTH $H $JSON -X PUT -d '{
  "profileId":"admin-fs-profile","displayName":"Admin FS Profile",
  "repositoryId":"'$REPO'","targetFolderId":"'$TARGET_FOLDER'",
  "allowedArchetypes":["FILE_SHARE"],"allowedConnectorIds":["verify-fs-1"],
  "defaultConnectorId":"verify-fs-1","enabled":true,"defaultProfile":true
}' "$NW/core/api/v1/admin/import-profiles/admin-fs-profile" | jq .defaultProfile
# expect: true

# 同 archetype + targetFolderId で別 profile を defaultProfile=true で作成 → 拒否
curl -s -o /dev/null -w "%{http_code}\n" $AUTH $H $JSON -X POST -d '{
  "profileId":"admin-fs-profile-2","displayName":"Conflict",
  "repositoryId":"'$REPO'","targetFolderId":"'$TARGET_FOLDER'",
  "allowedArchetypes":["FILE_SHARE"],"allowedConnectorIds":["verify-fs-1"],
  "defaultConnectorId":"verify-fs-1","enabled":true,"defaultProfile":true
}' $NW/core/api/v1/admin/import-profiles
# expect: 400 もしくは 409 (一意性違反)
```

### 5.4 自動無効化マーカー (RC5.1 V1)

scheduler が creator inactive を理由に auto-disable した profile は
`lastAutoDisabledAt` / `lastAutoDisabledReason` を持つ。再有効化
ハンドシェイクの確認:

```bash
# シミュレーション: マーカーが立った状態を admin が手動で書ける
# (data repair 用、非 admin は §6.5 で確認するように spoof 不可)
curl -s $AUTH $H $JSON -X PUT -d '{
  "profileId":"admin-fs-profile","displayName":"Admin FS Profile",
  "repositoryId":"'$REPO'","targetFolderId":"'$TARGET_FOLDER'",
  "allowedArchetypes":["FILE_SHARE"],"allowedConnectorIds":["verify-fs-1"],
  "defaultConnectorId":"verify-fs-1","enabled":false,
  "lastAutoDisabledAt":"2026-05-24T00:00:00Z",
  "lastAutoDisabledReason":"CREATOR_USER_INACTIVE: synthetic test"
}' "$NW/core/api/v1/admin/import-profiles/admin-fs-profile" \
  | jq '{enabled, lastAutoDisabledAt, lastAutoDisabledReason}'

# admin が enabled=true に戻す → marker クリア + audit に
# clearedAutoDisableMarker=true が記録される
curl -s $AUTH $H $JSON -X PUT -d '{
  "profileId":"admin-fs-profile","displayName":"Admin FS Profile",
  "repositoryId":"'$REPO'","targetFolderId":"'$TARGET_FOLDER'",
  "allowedArchetypes":["FILE_SHARE"],"allowedConnectorIds":["verify-fs-1"],
  "defaultConnectorId":"verify-fs-1","enabled":true
}' "$NW/core/api/v1/admin/import-profiles/admin-fs-profile" \
  | jq '{enabled, lastAutoDisabledAt, lastAutoDisabledReason}'
# expect: enabled=true、marker 両フィールドが null
```

UI 確認: 「インポートプロファイル」タブで marker が立っている行に
**橙色「自動無効化」Tag** が出る。Tooltip に reason 表示。
「自動無効化のみ表示」フィルタ Switch (RC5.1 V4) でその行のみに
絞れる。

### 5.5 admin プロファイル削除はテスト終盤に実施 (§11)

---

## 6. Import Profile — 委譲ユーザー

`folder-owner` でログインして、admin が委譲した範囲内で操作できる
ことを確認。

### 6.1 委譲ユーザーでログイン

```bash
AUTH_FO="-u folder-owner:changeme01"
# ヘルスチェック
curl -s -o /dev/null -w "%{http_code}\n" $AUTH_FO $NW/core/atom/$REPO
# expect: 200
```

UI 確認: ログアウト → `folder-owner / changeme01` でログイン →
統合設定タブには「インポートプロファイル」と「手動インポート」の
2 タブのみ表示(他は admin 専用)。「委譲ビュー」notice 表示。

### 6.2 委譲プロファイル作成 (folder-owner)

```bash
curl -s $AUTH_FO $H $JSON -X POST -d "$(cat <<EOF
{
  "profileId": "delegated-fs-profile",
  "displayName": "Delegated FS Profile",
  "repositoryId": "$REPO",
  "targetFolderId": "$TARGET_FOLDER",
  "allowedArchetypes": ["FILE_SHARE"],
  "allowedConnectorIds": ["verify-fs-1"],
  "defaultConnectorId": "verify-fs-1",
  "enabled": true,
  "delegated": true,
  "schedulerEnabled": false,
  "defaultProfile": false
}
EOF
)" $NW/core/api/v1/admin/import-profiles | jq '{profileId, createdByUserId, delegated, schedulerEnabled}'
# expect: createdByUserId="folder-owner", delegated=true,
# schedulerEnabled=false (強制)
```

UI 確認: インポートプロファイルタブで `delegated-fs-profile` が
表示される。`targetFolderPath` 入力欄は非表示(委譲モードは
folder ID 単一決定で運用するため)。

### 6.3 schedulerEnabled / defaultProfile は強制 false

```bash
# folder-owner が schedulerEnabled=true で送っても無視 or 拒否
curl -s -o /dev/null -w "%{http_code}\n" $AUTH_FO $H $JSON -X PUT -d '{
  "profileId":"delegated-fs-profile","displayName":"D",
  "repositoryId":"'$REPO'","targetFolderId":"'$TARGET_FOLDER'",
  "allowedArchetypes":["FILE_SHARE"],"allowedConnectorIds":["verify-fs-1"],
  "defaultConnectorId":"verify-fs-1","enabled":true,
  "delegated":true,
  "schedulerEnabled":true
}' "$NW/core/api/v1/admin/import-profiles/delegated-fs-profile"
# expect: nemakiware.ingest.delegated.schedulerEnabled プロパティに
# よる: OFF (default) → 4xx もしくは schedulerEnabled=false に正規化
# 確認:
curl -s $AUTH_FO "$NW/core/api/v1/admin/import-profiles/delegated-fs-profile" \
  | jq '{schedulerEnabled, defaultProfile}'
# expect: 両方 false (delegated profile では admin 専用フィールド)
```

UI 確認: 委譲ユーザーの編集モーダルで `schedulerEnabled` / 
`defaultProfile` トグルが **disabled (グレーアウト)** + Tooltip
「管理者のみ設定可能」。

### 6.4 委譲外 connector を選ぼうとして失敗

別の non-delegated connector を作って folder-owner から profile に
混ぜようとする検証。

```bash
# admin で別 connector 作成 (delegated=false)
curl -s $AUTH $H $JSON -X POST -d '{
  "connectorId":"admin-only-fs","displayName":"Admin Only",
  "sourceArchetype":"FILE_SHARE","sourceSystem":"dropbox",
  "adapterKind":"dropbox","authType":"oauth2",
  "endpoint":"https://api.dropbox.com/2","enabled":true,
  "delegated":false
}' "$NW/core/api/v1/admin/connectors?repositoryId=$REPO" > /dev/null

# folder-owner が allowedConnectorIds に admin-only-fs を入れる → 拒否
curl -s -o /dev/null -w "%{http_code}\n" $AUTH_FO $H $JSON -X PUT -d '{
  "profileId":"delegated-fs-profile","displayName":"D",
  "repositoryId":"'$REPO'","targetFolderId":"'$TARGET_FOLDER'",
  "allowedArchetypes":["FILE_SHARE"],
  "allowedConnectorIds":["verify-fs-1","admin-only-fs"],
  "defaultConnectorId":"verify-fs-1","enabled":true,"delegated":true
}' "$NW/core/api/v1/admin/import-profiles/delegated-fs-profile"
# expect: 403 (admin-only-fs は委譲外)
```

UI 確認: コネクタ picker で `admin-only-fs` は **候補に出てこない**
(folder-owner が UI から選ぼうとしても options に含まれない)。

### 6.5 マーカー spoof 防止 (RC5.1 F1)

folder-owner が `lastAutoDisabledAt` を仕込んでも無視されること。

```bash
curl -s $AUTH_FO $H $JSON -X PUT -d '{
  "profileId":"delegated-fs-profile","displayName":"D",
  "repositoryId":"'$REPO'","targetFolderId":"'$TARGET_FOLDER'",
  "allowedArchetypes":["FILE_SHARE"],"allowedConnectorIds":["verify-fs-1"],
  "defaultConnectorId":"verify-fs-1","enabled":true,"delegated":true,
  "lastAutoDisabledAt":"2099-12-31T23:59:59Z",
  "lastAutoDisabledReason":"FAKE"
}' "$NW/core/api/v1/admin/import-profiles/delegated-fs-profile" \
  | jq '{lastAutoDisabledAt, lastAutoDisabledReason}'
# expect: 両方 null (非 admin の書き込みは強制的に消される)
```

### 6.6 cmis:all 失効後の操作拒否 (実行時 ACL 再評価)

```bash
# admin が folder-owner の cmis:all を剥奪
curl -s $AUTH -X POST \
  -F "cmisaction=applyACL" \
  -F "removeACEPrincipalId[0]=folder-owner" -F "removeACEPermission[0]=cmis:all" \
  -F "ACLPropagation=propagate" \
  "$NW/core/browser/$REPO/root?objectId=$TARGET_FOLDER" > /dev/null

# folder-owner が委譲 profile を update しようとする → 403
curl -s -o /dev/null -w "%{http_code}\n" $AUTH_FO $H $JSON -X PUT -d '{
  "profileId":"delegated-fs-profile","displayName":"D-renamed",
  "repositoryId":"'$REPO'","targetFolderId":"'$TARGET_FOLDER'",
  "allowedArchetypes":["FILE_SHARE"],"allowedConnectorIds":["verify-fs-1"],
  "defaultConnectorId":"verify-fs-1","enabled":true,"delegated":true
}' "$NW/core/api/v1/admin/import-profiles/delegated-fs-profile"
# expect: 403 (TARGET_FOLDER_PERMISSION_DENIED)

# admin が cmis:all を戻す
curl -s $AUTH -X POST \
  -F "cmisaction=applyACL" \
  -F "principalId[0]=folder-owner" -F "permission[0]=cmis:all" \
  -F "ACLPropagation=propagate" \
  "$NW/core/browser/$REPO/root?objectId=$TARGET_FOLDER" > /dev/null
```

---

## 7. Manual Ingest — admin

### 7.1 JSON ingest (FILE_SHARE archetype、payload-only テスト)

実コネクタを呼ばずに canonical pipeline をテストするには、本物の
source ファイルを multipart で送るのが一番簡単。FILE_SHARE では
content stream + メタデータの両方が必要。

```bash
echo "manual verification content $(date)" > /tmp/manual-verify.txt

curl -s $AUTH $H -X POST \
  -F "profileId=admin-fs-profile" \
  -F "connectorId=verify-fs-1" \
  -F "sourceArchetype=FILE_SHARE" \
  -F "sourceObjectId=verify-001" \
  -F "displayName=manual-verify.txt" \
  -F "content=@/tmp/manual-verify.txt;type=text/plain" \
  "$NW/core/api/v1/repo/$REPO/ingest" | jq .
# expect: HTTP 200/201、返却 JSON に objectId / version など
```

UI 確認: ドキュメント → `delegated-inbox` フォルダ → 
`manual-verify.txt` が表示される。プロパティを開くと
**Secondary Type に `nemaki:externalIntegration`** が付与され、
`sourceArchetype=FILE_SHARE` / `sourceSystem=googledrive` /
`sourceObjectId=verify-001` がセットされている。

### 7.2 同一 sourceObjectId で再 ingest → dedupe

```bash
echo "modified content $(date)" > /tmp/manual-verify.txt

curl -s $AUTH $H -X POST \
  -F "profileId=admin-fs-profile" \
  -F "connectorId=verify-fs-1" \
  -F "sourceArchetype=FILE_SHARE" \
  -F "sourceObjectId=verify-001" \
  -F "displayName=manual-verify.txt" \
  -F "content=@/tmp/manual-verify.txt;type=text/plain" \
  "$NW/core/api/v1/repo/$REPO/ingest" | jq '{operation, objectId, versionLabel}'
# expect: updatePolicy="version_up_on_content_change" のため、
# 内容ハッシュが変わっていれば新バージョン (major up) が作成される
```

UI 確認: 同名ドキュメントを開く → バージョン履歴に 2 件。

### 7.3 dry-run (副作用なしの validation)

```bash
curl -s $AUTH $H $JSON -X POST -d '{
  "profileId": "admin-fs-profile",
  "connectorId": "verify-fs-1",
  "sourceArchetype": "FILE_SHARE",
  "sourceObjectId": "dryrun-only",
  "displayName": "dryrun.txt",
  "dryRun": true
}' "$NW/core/api/v1/repo/$REPO/ingest" | jq .
# expect: validation 結果のみ、CouchDB / Solr には反映なし
```

確認: ドキュメント一覧に `dryrun.txt` は **存在しない**。

UI 確認: 管理 → 統合設定 → 「手動インポート」タブ → admin は
**connector → profile** の順で選択。実行ボタンに「ドライラン」
チェックボックス。

---

## 8. Manual Ingest — 委譲ユーザー

### 8.1 委譲ユーザーで ingest 実行

```bash
echo "delegated ingest $(date)" > /tmp/delegated-verify.txt

curl -s $AUTH_FO $H -X POST \
  -F "profileId=delegated-fs-profile" \
  -F "connectorId=verify-fs-1" \
  -F "sourceArchetype=FILE_SHARE" \
  -F "sourceObjectId=delegated-001" \
  -F "displayName=delegated-verify.txt" \
  -F "content=@/tmp/delegated-verify.txt;type=text/plain" \
  "$NW/core/api/v1/repo/$REPO/ingest" | jq .
# expect: HTTP 200/201、ドキュメントが TARGET_FOLDER 配下に作成
```

UI 確認: 「手動インポート」タブ → folder-owner は
**profile → connector** の **逆順** で選択(connector は
profile.allowedConnectorIds から絞り込み)。

### 8.2 委譲外 connector で 403

```bash
curl -s -o /dev/null -w "%{http_code}\n" $AUTH_FO $H -X POST \
  -F "profileId=delegated-fs-profile" \
  -F "connectorId=admin-only-fs" \
  -F "sourceArchetype=FILE_SHARE" \
  -F "sourceObjectId=should-fail" \
  -F "displayName=should-fail.txt" \
  -F "content=@/tmp/delegated-verify.txt;type=text/plain" \
  "$NW/core/api/v1/repo/$REPO/ingest"
# expect: 403 (connector が profile.allowedConnectorIds に含まれない)
```

### 8.3 targetFolderOverride 禁止

```bash
# admin なら override 可能、folder-owner は禁止
OTHER_FOLDER=$ROOT  # ルート(folder-owner は cmis:all なし)

curl -s -o /dev/null -w "%{http_code}\n" $AUTH_FO $H -X POST \
  -F "profileId=delegated-fs-profile" \
  -F "connectorId=verify-fs-1" \
  -F "sourceArchetype=FILE_SHARE" \
  -F "sourceObjectId=override-test" \
  -F "displayName=override-test.txt" \
  -F "targetFolderOverride=$OTHER_FOLDER" \
  -F "content=@/tmp/delegated-verify.txt;type=text/plain" \
  "$NW/core/api/v1/repo/$REPO/ingest"
# expect: 403 (TARGET_FOLDER_OVERRIDE_FORBIDDEN)
```

### 8.4 profileId 必須 (admin と挙動を分ける)

```bash
# folder-owner が profileId 省略 → 400
curl -s -o /dev/null -w "%{http_code}\n" $AUTH_FO $H -X POST \
  -F "connectorId=verify-fs-1" \
  -F "sourceArchetype=FILE_SHARE" \
  -F "sourceObjectId=no-profile" \
  -F "displayName=no-profile.txt" \
  -F "content=@/tmp/delegated-verify.txt;type=text/plain" \
  "$NW/core/api/v1/repo/$REPO/ingest"
# expect: 400 もしくは 403 (PROFILE_ID_REQUIRED_FOR_DELEGATED)
```

---

## 9. Governance view — by-principal (RC5)

「principal X が使える connector」 / 「X を connector 設定から外したら
何が壊れるか」を答える endpoint。

### 9.1 user 単独で問い合わせ

```bash
curl -s $AUTH "$NW/core/api/v1/admin/connectors/by-principal/folder-owner?repositoryId=$REPO&expand=true" \
  | jq '{principalId, principalType, expandedPrincipals, matches: (.matches | map({connectorId, matchType, matchedPrincipalIds}))}'
# expect:
#   principalType: "USER"
#   expandedPrincipals: ["folder-owner", "team-alpha", "GROUP_EVERYONE"] 等
#   matches に verify-fs-1 が含まれ、matchType は "direct" もしくは
#   "direct+group" (folder-owner と team-alpha 両方 allowedPrincipalIds に居る)
```

### 9.2 group 単独で問い合わせ

```bash
curl -s $AUTH "$NW/core/api/v1/admin/connectors/by-principal/team-alpha?repositoryId=$REPO&expand=true" \
  | jq '{principalType, matches: (.matches | map(.connectorId))}'
# expect: principalType="GROUP", matches に verify-fs-1
```

### 9.3 expand=false で direct match のみ

```bash
curl -s $AUTH "$NW/core/api/v1/admin/connectors/by-principal/team-member?repositoryId=$REPO&expand=false" \
  | jq '{matches: (.matches | map({connectorId, matchType}))}'
# team-member は allowedPrincipalIds に直接書かれていないので、
# expand=false なら matches は空 [] になるはず

curl -s $AUTH "$NW/core/api/v1/admin/connectors/by-principal/team-member?repositoryId=$REPO&expand=true" \
  | jq '{matches: (.matches | map({connectorId, matchType, matchedPrincipalIds}))}'
# expand=true なら team-alpha 経由で verify-fs-1 が見える
# matchType="group"
```

### 9.4 UNKNOWN principal

```bash
curl -s $AUTH "$NW/core/api/v1/admin/connectors/by-principal/nonexistent-user?repositoryId=$REPO&expand=true" \
  | jq '{principalType, matches}'
# expect: principalType="UNKNOWN", matches=[] (graceful)
```

### 9.5 UI: Connector Governance タブ

UI 確認:
1. admin で統合設定 → 「コネクタ ガバナンス」タブを開く
2. Radio で **Principal モード** を選択
3. AutoComplete (F3) で `folder-owner` を入力(suggest に
   `folder-owner · Folder Owner (USER)` 形式で表示される)
4. expand チェック ON で実行 → 結果表に `verify-fs-1` 行
5. principalType badge: USER=geekblue
6. matchType badge: direct=green / via group=blue / direct+group=orange
7. ヘッダーカードに expandedPrincipals 一覧

---

## 10. Governance view — by-group (RC6 B3-2)

「group X を削除したら誰が何を失うか」を per-member sole-route 検出で
返す endpoint。

### 10.1 group 削除影響の問い合わせ

```bash
curl -s $AUTH "$NW/core/api/v1/admin/connectors/by-group/team-alpha?repositoryId=$REPO&includeMembers=true&memberLimit=200" \
  | jq '{
       groupId, principalType, memberCount, memberUserIds, memberUserIdsTruncated,
       directGrants: (.directGrants | map(.connectorId)),
       perMemberImpact: (.perMemberImpact | map({
         memberUserId,
         lostCount,
         lostIfGroupRemovedTruncated,
         lostIfGroupRemoved: (.lostIfGroupRemoved | map(.connectorId))
       })),
       perMemberImpactTruncated
     }'
# expect:
#   memberCount: 2 (folder-owner + team-member)
#   directGrants: 空 [] もしくは verify-fs-1 (team-alpha が直接書かれている場合)
#   perMemberImpact:
#     - folder-owner: lostCount=0 (folder-owner は直接 allowedPrincipalIds に居るので group 削除しても残る)
#     - team-member:  lostCount=1 (group 経由でしか到達できない)
#                     lostIfGroupRemoved に verify-fs-1
```

### 10.2 includeMembers=false で高速パス

```bash
curl -s $AUTH "$NW/core/api/v1/admin/connectors/by-group/team-alpha?repositoryId=$REPO&includeMembers=false" \
  | jq '{groupId, directGrants: (.directGrants | map(.connectorId)), perMemberImpact}'
# expect: perMemberImpact は null もしくは [] (member 展開を skip)
```

### 10.3 memberLimit truncation

```bash
curl -s $AUTH "$NW/core/api/v1/admin/connectors/by-group/team-alpha?repositoryId=$REPO&includeMembers=true&memberLimit=1" \
  | jq '{memberCount, memberUserIds, memberUserIdsTruncated, perMemberImpactTruncated}'
# expect: memberCount=2, memberUserIds に 1 件のみ
#         memberUserIdsTruncated=true (RC6 P2-1 +RC6 で
#         alphabetical first-N へ正規化)
```

### 10.4 server cap (MAX_MEMBER_LIMIT=1000)

```bash
curl -s $AUTH "$NW/core/api/v1/admin/connectors/by-group/team-alpha?repositoryId=$REPO&memberLimit=99999" \
  | jq '{memberCount, memberUserIds_length: (.memberUserIds | length)}'
# expect: 内部で 1000 に clamp される(2 件しかいないので顕在化しないが、
# 大規模グループだと memberUserIds は最大 1000)
```

### 10.5 unknown group

```bash
curl -s $AUTH "$NW/core/api/v1/admin/connectors/by-group/no-such-group?repositoryId=$REPO" \
  | jq '{principalType, directGrants, perMemberImpact}'
# expect: principalType="UNKNOWN", stable shape (空配列)、200 OK
```

### 10.6 missing param → 400

```bash
curl -s -o /dev/null -w "%{http_code}\n" $AUTH "$NW/core/api/v1/admin/connectors/by-group/team-alpha"
# expect: 400 (missing repositoryId)

curl -s -o /dev/null -w "%{http_code}\n" $AUTH "$NW/core/api/v1/admin/connectors/by-group/?repositoryId=$REPO"
# expect: 400 or 404 (missing groupId)
```

### 10.7 UI: Group モード

UI 確認:
1. コネクタ ガバナンスタブ → Radio で **Group モード** に切替
2. AutoComplete で `team-alpha` を選択
3. includeMembers トグル ON で実行
4. 結果カード: 「メンバー (2)」 + 「直接付与」 + 「メンバー別影響」
5. memberLimit が小さいときは「truncated」警告 Alert
6. Tag 色分け: USER=geekblue / GROUP=purple

---

## 11. Simulate-remove endpoint (RC5.3 W2 + RC5.4 R3 button + RC6.1 P2 cap)

「principal X を allowedPrincipalIds から外したら、どの connector match が
失われるか?」を 1 リクエストで返す endpoint。

### 11.1 単純な simulate-remove (1 principal)

```bash
curl -s $AUTH $H $JSON -X POST -d '{
  "repositoryId": "'$REPO'",
  "expand": true,
  "removePrincipalIds": ["folder-owner"]
}' "$NW/core/api/v1/admin/connectors/by-principal/folder-owner/simulate-remove" \
  | jq '{
       lost: (.lost | map({connectorId, matchedPrincipalIds})),
       kept: (.kept | map({connectorId, matchedPrincipalIds}))
     }'
# expect:
#   lost: folder-owner を外した結果 すべて失われる match
#   kept: alternate route (team-alpha 経由など) で残る match
#
# verify-fs-1 は folder-owner と team-alpha 両方に許可されている
# ので、folder-owner だけ外しても kept に出る。
```

### 11.2 multi-removal シミュレーション (RC5.1 V7)

```bash
curl -s $AUTH $H $JSON -X POST -d '{
  "repositoryId": "'$REPO'",
  "expand": true,
  "removePrincipalIds": ["folder-owner", "team-alpha"]
}' "$NW/core/api/v1/admin/connectors/by-principal/folder-owner/simulate-remove" \
  | jq '{lost: (.lost | map(.connectorId)), kept: (.kept | map(.connectorId))}'
# expect: folder-owner と team-alpha を同時に外すと、
#         verify-fs-1 は両ルート失うため lost に。
```

### 11.3 count cap (RC6.1 M2 = MAX_REMOVE_PRINCIPAL_IDS=500)

```bash
# 501 件の removePrincipalIds を作って投げる → 400
python3 -c "import json; print(json.dumps({'repositoryId':'$REPO','expand':False,'removePrincipalIds':['p'+str(i) for i in range(501)]}))" \
  | curl -s -o /dev/null -w "%{http_code}\n" $AUTH $H $JSON -X POST -d @- \
    "$NW/core/api/v1/admin/connectors/by-principal/folder-owner/simulate-remove"
# expect: 400 (REMOVE_PRINCIPAL_IDS_LIMIT_EXCEEDED)

# 500 件以内は受理される (内容は何でも OK)
python3 -c "import json; print(json.dumps({'repositoryId':'$REPO','expand':False,'removePrincipalIds':['p'+str(i) for i in range(500)]}))" \
  | curl -s -o /dev/null -w "%{http_code}\n" $AUTH $H $JSON -X POST -d @- \
    "$NW/core/api/v1/admin/connectors/by-principal/folder-owner/simulate-remove"
# expect: 200
```

### 11.4 per-entry length cap (MAX_PRINCIPAL_ID_LENGTH=512)

```bash
LONG=$(python3 -c "print('x'*600)")
curl -s -o /dev/null -w "%{http_code}\n" $AUTH $H $JSON -X POST -d "$(cat <<EOF
{"repositoryId":"$REPO","expand":false,"removePrincipalIds":["$LONG"]}
EOF
)" "$NW/core/api/v1/admin/connectors/by-principal/folder-owner/simulate-remove"
# expect: 400
```

### 11.5 UI: 「Simulate (audit)」ボタン (RC5.4 R3)

V7 で導入された debounce 自動発火を廃止し、明示的ボタンに変更
(audit 1:1 mapping のため)。

UI 確認:
1. コネクタ ガバナンス → Principal モード → `folder-owner` で実行
2. ヘッダーカード下に **「ガバナンス削除シミュレーション」** ドロップダウン
3. expandedPrincipals から removePrincipalIds を Multi-select
4. 隣の **「Simulate (audit)」ボタン** をクリックして初めて
   `simulate-remove` API が叩かれる
5. 結果ペインに lost / kept が分かれて表示
6. Click 毎に 1 件 audit エントリ `EXTERNAL_GOVERNANCE_SIMULATE` が
   記録される(本セクションは audit 確認スコープ外、§4 で言及済)

---

## 12. クリーンアップ

検証で作成したリソースを削除する。順序: profile → connector →
ドキュメント → folder → group → user。

```bash
# 1. インポートプロファイル
curl -s -o /dev/null -w "delete admin-fs-profile %{http_code}\n" \
  $AUTH $H -X DELETE \
  "$NW/core/api/v1/admin/import-profiles/admin-fs-profile?repositoryId=$REPO"

curl -s -o /dev/null -w "delete delegated-fs-profile %{http_code}\n" \
  $AUTH $H -X DELETE \
  "$NW/core/api/v1/admin/import-profiles/delegated-fs-profile?repositoryId=$REPO"

# 2. コネクタ
curl -s -o /dev/null -w "delete verify-fs-1 %{http_code}\n" \
  $AUTH $H -X DELETE \
  "$NW/core/api/v1/admin/connectors/verify-fs-1?repositoryId=$REPO"

curl -s -o /dev/null -w "delete admin-only-fs %{http_code}\n" \
  $AUTH $H -X DELETE \
  "$NW/core/api/v1/admin/connectors/admin-only-fs?repositoryId=$REPO"

# 3. ingest で作成したドキュメントを含むテストフォルダごと削除
curl -s -o /dev/null -w "deleteTree %{http_code}\n" $AUTH -X POST \
  -F "cmisaction=deleteTree" \
  -F "allVersions=true" -F "unfileObjects=delete" -F "continueOnFailure=true" \
  "$NW/core/browser/$REPO/root?objectId=$TARGET_FOLDER"

# 4. グループ削除
curl -s -o /dev/null -w "delete team-alpha %{http_code}\n" $AUTH $H -X DELETE \
  "$NW/core/api/v1/cmis/repositories/$REPO/groups/team-alpha"

# 5. ユーザー削除
curl -s -o /dev/null -w "delete folder-owner %{http_code}\n" $AUTH $H -X DELETE \
  "$NW/core/api/v1/cmis/repositories/$REPO/users/folder-owner"
curl -s -o /dev/null -w "delete team-member %{http_code}\n" $AUTH $H -X DELETE \
  "$NW/core/api/v1/cmis/repositories/$REPO/users/team-member"
```

UI 確認: 統合設定 → コネクタ ベータ / インポートプロファイル
タブで該当行が消えている。ユーザー / グループ管理タブも同様。

---

## 13. チェックリスト (検証完了の最終確認)

| # | 確認項目 | OK |
|---|---|---|
| 3.2 | admin が connector 作成できる | ☐ |
| 3.3 | GET で credentialRef / webhookSecret が `[configured]` でマスクされる | ☐ |
| 3.4 | PUT で list field を omit (null) すると既存値が保持される | ☐ |
| 3.4 | PUT で `[]` を明示すると clear される | ☐ |
| 4.1 | `delegated=true` + `allowedFolderIds=[F]` + `allowedPrincipalIds=[P]` で適正委譲できる | ☐ |
| 4.2 | `delegated=true` + `allowedFolderIds=[]` は委譲不可扱い (summary で出ない) | ☐ |
| 4.3 | `delegateAllFolders=true` で警告 UI が出る | ☐ |
| 4.4 | `/summary` が secret / endpoint / scope を含まない | ☐ |
| 5.1 | admin で profile CRUD ができる | ☐ |
| 5.2 | schedulerEnabled トグル後に scheduler status に表示される | ☐ |
| 5.3 | 同 archetype × targetFolderId で defaultProfile=true 重複は拒否される | ☐ |
| 5.4 | admin re-enable で marker (lastAutoDisabledAt) がクリアされる | ☐ |
| 6.2 | folder-owner で委譲 profile を作成できる (`createdByUserId=folder-owner`) | ☐ |
| 6.3 | 委譲 profile は schedulerEnabled / defaultProfile が強制 false | ☐ |
| 6.4 | folder-owner が委譲外 connector を allowedConnectorIds に入れて PUT すると 403 | ☐ |
| 6.5 | folder-owner の lastAutoDisabledAt 書き込みは silent drop される (spoof 防止) | ☐ |
| 6.6 | folder-owner の cmis:all 失効後は委譲 profile 操作が 403 | ☐ |
| 7.1 | admin で manual ingest 成功 (nemaki:externalIntegration secondary type 付与) | ☐ |
| 7.2 | 同 sourceObjectId 再 ingest で hash 変化があれば新バージョン作成 | ☐ |
| 7.3 | dryRun=true で実体作成されない | ☐ |
| 8.1 | folder-owner で manual ingest 成功 | ☐ |
| 8.2 | folder-owner が委譲外 connectorId 指定で 403 | ☐ |
| 8.3 | folder-owner が targetFolderOverride 指定で 403 | ☐ |
| 8.4 | folder-owner が profileId 省略で 400 | ☐ |
| 9.1 | by-principal が USER 単独で正しく matches を返す | ☐ |
| 9.2 | by-principal が GROUP 単独で principalType=GROUP を返す | ☐ |
| 9.3 | expand=false で group 経由 match が出ない | ☐ |
| 9.4 | 不存在 principal で principalType=UNKNOWN + 200 (graceful) | ☐ |
| 9.5 | UI Governance タブで matchType badge 色分けが表示される | ☐ |
| 10.1 | by-group が perMemberImpact を返す (sole-route 検出が正しい) | ☐ |
| 10.2 | includeMembers=false で perMemberImpact 省略される (高速パス) | ☐ |
| 10.3 | memberLimit truncation で memberUserIdsTruncated=true | ☐ |
| 10.5 | unknown group で principalType=UNKNOWN + 200 + stable shape | ☐ |
| 10.6 | missing repositoryId で 400 | ☐ |
| 10.7 | UI Group モード で perMemberImpact カードが表示される | ☐ |
| 11.1 | simulate-remove が lost / kept を分けて返す | ☐ |
| 11.2 | multi-removal で両ルート失う connector が lost に入る | ☐ |
| 11.3 | removePrincipalIds > 500 で 400 (RC6.1 M2 cap) | ☐ |
| 11.4 | 1 entry > 512 字で 400 | ☐ |
| 11.5 | UI で Simulate ボタンクリック毎に audit エントリ 1 件 | ☐ |

---

## 14. 既知の注意点

- **ConnectorDefinition の `webhookSecret` は HMAC 検証用**
  (受信 webhook の署名検証)。secret masking 対象。Webhook 受信
  endpoint の挙動検証は本書スコープ外。
- **`/v1/admin/connectors/by-principal/...` を folder-owner が呼ぶと
  403**。governance view は admin only(RC5 §12.3)。
- **`nemakiware.ingest.delegated.schedulerEnabled` プロパティ**
  は default OFF。委譲 profile の `schedulerEnabled=true` が
  受理されるかどうかはこのプロパティに従う。本書 §6.3 は default
  OFF を前提。
- **テストフォルダの `cmis:all` 付与時に `ACLPropagation=propagate`
  を忘れない**。配下にも継承される(folder ancestor walk が
  cap 128 hop で打ち切られる仕様、§§4.x の `nemakiware.ingest.
  ancestorWalk.maxHops` 参照)。
- **`createdByUserId` は SyntheticCallContext 用 (RC5 §12.1)**。
  scheduler が delegated profile を走らせるとき、この user の
  active 状態と cmis:all を per-tick で再評価する。本書では
  scheduled delegated は触れていない(operator opt-in property
  が必要なため)。

---

## 15. 関連ドキュメント

| ドキュメント | 内容 |
|---|---|
| [`docs/design/connector-delegation.md`](design/connector-delegation.md) | 委譲モデルの設計詳細 (§12.1 - §12.20)、DenialReason リファレンス |
| [`docs/SOC-AUDIT-INTEGRATION.md`](SOC-AUDIT-INTEGRATION.md) | audit ログ schema と SIEM 連携 playbook (`EXTERNAL_*` 系イベントの解釈) |
| [`CLAUDE.md`](../CLAUDE.md) | RC ごとの累積機能リスト (RC1〜RC28) |
| [`RELEASE_NOTES.md`](../RELEASE_NOTES.md) | 12 セクション (RC5 → RC6.4) |

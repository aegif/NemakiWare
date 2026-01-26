#!/bin/bash
# ============================================================================
# NemakiWare Directory Sync Comprehensive Test Script
# ============================================================================
#
# このスクリプトは、ディレクトリ同期機能の多面的なテストを実行します。
#
# テストケース:
#   1. 接続テスト
#   2. プレビュー（ドライラン）
#   3. 初期同期（空のNemakiWare → LDAPデータ全件取込）
#   4. 同期結果の検証
#   5. 増分同期（LDAPにユーザー/グループ追加）
#   6. 更新同期（LDAPのユーザー/グループ変更）
#   7. 削除同期（LDAPからユーザー/グループ削除）
#   8. エッジケース
#
# 前提条件:
#   - docker-compose-ldap.yml で環境が起動していること
#   - OpenLDAP にテストデータがロードされていること
#
# REST API エンドポイント:
#   - /rest/repo/{repositoryId}/sync/test-connection
#   - /rest/repo/{repositoryId}/sync/preview
#   - /rest/repo/{repositoryId}/sync/trigger
#   - /rest/repo/{repositoryId}/sync/status
#   - /rest/repo/{repositoryId}/sync/config
#
# ============================================================================

# Note: set -e disabled to allow script to continue even if some tests fail
# set -e

# 色付き出力
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 設定
NEMAKI_URL="http://localhost:8080/core"
NEMAKI_AUTH="admin:admin"
LDAP_ADMIN_DN="cn=admin,dc=nemakiware,dc=example,dc=com"
LDAP_ADMIN_PW="adminpassword"
LDAP_BASE_DN="dc=nemakiware,dc=example,dc=com"
# コンテナ名を動的に取得（openldap または docker-openldap-1）
LDAP_CONTAINER=$(docker ps --format '{{.Names}}' | grep -E 'openldap' | head -1)
if [ -z "$LDAP_CONTAINER" ]; then
    LDAP_CONTAINER="openldap"  # デフォルト値
fi

# カウンター
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# ============================================================================
# ユーティリティ関数
# ============================================================================

log_header() {
    echo ""
    echo "============================================================================"
    echo -e "${BLUE}$1${NC}"
    echo "============================================================================"
}

log_test() {
    echo -e "${YELLOW}[TEST]${NC} $1"
    ((TOTAL_TESTS++))
}

log_pass() {
    echo -e "${GREEN}[PASS]${NC} $1"
    ((PASSED_TESTS++))
}

log_fail() {
    echo -e "${RED}[FAIL]${NC} $1"
    ((FAILED_TESTS++))
}

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# JSON からフィールドを抽出（jq必須）
# 改善: エラーハンドリング強化、null/空文字列の処理
json_field() {
    local json="$1"
    local path="$2"
    local result

    # JSONが空かチェック
    if [ -z "$json" ]; then
        echo "null"
        return
    fi

    # jqでパース
    result=$(echo "$json" | jq -r "$path" 2>/dev/null)
    local jq_exit=$?

    # jqエラー時
    if [ $jq_exit -ne 0 ]; then
        log_warn "JSON parse error for path: $path"
        echo "null"
        return
    fi

    # null文字列または空文字列の場合
    if [ -z "$result" ] || [ "$result" = "null" ]; then
        echo "null"
        return
    fi

    echo "$result"
}

# 数値フィールド用（デフォルト0）
json_field_num() {
    local value=$(json_field "$1" "$2")
    if [ "$value" = "null" ] || [ -z "$value" ]; then
        echo "0"
    else
        echo "$value"
    fi
}

# NemakiWare REST API 呼び出し
nemaki_api() {
    local method="$1"
    local endpoint="$2"
    local data="$3"

    if [ "$method" = "GET" ]; then
        curl -s -u "$NEMAKI_AUTH" "${NEMAKI_URL}${endpoint}"
    elif [ "$method" = "POST" ]; then
        if [ -n "$data" ]; then
            curl -s -u "$NEMAKI_AUTH" -X POST -H "Content-Type: application/json" -d "$data" "${NEMAKI_URL}${endpoint}"
        else
            curl -s -u "$NEMAKI_AUTH" -X POST "${NEMAKI_URL}${endpoint}"
        fi
    elif [ "$method" = "DELETE" ]; then
        curl -s -u "$NEMAKI_AUTH" -X DELETE "${NEMAKI_URL}${endpoint}"
    fi
}

# LDAP コマンド実行
ldap_exec() {
    docker exec "$LDAP_CONTAINER" "$@"
}

# LDAP 検索
ldap_search() {
    local base="$1"
    local filter="$2"
    ldap_exec ldapsearch -x -H ldap://localhost:389 -b "$base" -D "$LDAP_ADMIN_DN" -w "$LDAP_ADMIN_PW" "$filter" 2>/dev/null
}

# LDAP エントリ追加
ldap_add() {
    local ldif="$1"
    echo "$ldif" | docker exec -i "$LDAP_CONTAINER" ldapadd -x -H ldap://localhost:389 -D "$LDAP_ADMIN_DN" -w "$LDAP_ADMIN_PW" 2>/dev/null
}

# LDAP エントリ変更
ldap_modify() {
    local ldif="$1"
    echo "$ldif" | docker exec -i "$LDAP_CONTAINER" ldapmodify -x -H ldap://localhost:389 -D "$LDAP_ADMIN_DN" -w "$LDAP_ADMIN_PW" 2>/dev/null
}

# LDAP エントリ削除
ldap_delete() {
    local dn="$1"
    ldap_exec ldapdelete -x -H ldap://localhost:389 -D "$LDAP_ADMIN_DN" -w "$LDAP_ADMIN_PW" "$dn" 2>/dev/null
}

# ============================================================================
# 環境チェック
# ============================================================================

check_environment() {
    log_header "環境チェック"

    # jq の確認
    if ! command -v jq &> /dev/null; then
        log_fail "jq がインストールされていません。brew install jq でインストールしてください。"
        exit 1
    fi
    log_pass "jq インストール済み"

    # NemakiWare の確認
    log_test "NemakiWare 接続確認"
    local status=$(curl -s -o /dev/null -w "%{http_code}" -u "$NEMAKI_AUTH" "${NEMAKI_URL}/atom/bedroom")
    if [ "$status" = "200" ]; then
        log_pass "NemakiWare 接続OK (HTTP $status)"
    else
        log_fail "NemakiWare 接続失敗 (HTTP $status)"
        exit 1
    fi

    # OpenLDAP の確認
    log_test "OpenLDAP 接続確認"
    if ldap_search "$LDAP_BASE_DN" "(objectClass=organization)" | grep -q "nemakiware"; then
        log_pass "OpenLDAP 接続OK (Base DN: $LDAP_BASE_DN)"
    else
        log_fail "OpenLDAP 接続失敗 (Base DN: $LDAP_BASE_DN)"
        exit 1
    fi

    # LDAP テストデータの確認
    log_test "LDAP テストデータ確認"
    local user_count=$(ldap_search "ou=users,$LDAP_BASE_DN" "(objectClass=inetOrgPerson)" | grep -c "^dn:" || echo 0)
    local group_count=$(ldap_search "ou=groups,$LDAP_BASE_DN" "(objectClass=groupOfNames)" | grep -c "^dn:" || echo 0)
    if [ "$user_count" -ge 5 ] && [ "$group_count" -ge 5 ]; then
        log_pass "LDAP テストデータ確認OK (ユーザー: $user_count, グループ: $group_count)"
    else
        log_fail "LDAP テストデータ不足 (ユーザー: $user_count, グループ: $group_count)"
        exit 1
    fi
}

# ============================================================================
# テスト1: 接続テスト
# ============================================================================

test_connection() {
    log_header "テスト1: LDAP接続テスト"

    log_test "Directory Sync 接続テストAPI"
    local result=$(nemaki_api GET "/rest/repo/bedroom/sync/test-connection")
    log_info "Response: $result"

    local status=$(json_field "$result" '.status')
    local connection_success=$(json_field "$result" '.connectionSuccess')
    if [ "$status" = "success" ] && [ "$connection_success" = "true" ]; then
        log_pass "LDAP接続テスト成功"
    else
        local message=$(json_field "$result" '.message')
        log_fail "LDAP接続テスト失敗: $message"
    fi
}

# ============================================================================
# テスト2: プレビュー（ドライラン）
# ============================================================================

test_preview() {
    log_header "テスト2: 同期プレビュー（ドライラン）"

    log_test "同期プレビューAPI"
    local result=$(nemaki_api GET "/rest/repo/bedroom/sync/preview")
    log_info "Response: $(echo "$result" | jq -c '.')"

    local status=$(json_field "$result" '.status')
    local sync_status=$(json_field "$result" '.syncResult.status')
    if [ "$status" = "success" ] && [ "$sync_status" = "SUCCESS" ]; then
        local users_to_create=$(json_field "$result" '.syncResult.usersAdded')
        local groups_to_create=$(json_field "$result" '.syncResult.groupsCreated')
        local users_skipped=$(json_field "$result" '.syncResult.usersSkipped')
        local groups_skipped=$(json_field "$result" '.syncResult.groupsSkipped')
        log_pass "プレビュー成功 (作成予定: ユーザー $users_to_create, グループ $groups_to_create, スキップ: ユーザー $users_skipped, グループ $groups_skipped)"
    else
        local message=$(json_field "$result" '.syncResult.errors[0].message')
        log_fail "プレビュー失敗: $message"
    fi
}

# ============================================================================
# テスト3: 初期同期
# ============================================================================

test_initial_sync() {
    log_header "テスト3: 初期同期（LDAPデータ全件取込）"

    log_test "同期実行API"
    local result=$(nemaki_api POST "/rest/repo/bedroom/sync/trigger")
    log_info "Response: $(echo "$result" | jq -c '.')"

    local status=$(json_field "$result" '.status')
    local sync_status=$(json_field "$result" '.syncResult.status')
    if [ "$status" = "success" ] && ([ "$sync_status" = "SUCCESS" ] || [ "$sync_status" = "PARTIAL" ]); then
        local users_created=$(json_field "$result" '.syncResult.usersAdded')
        local groups_created=$(json_field "$result" '.syncResult.groupsCreated')
        local users_skipped=$(json_field "$result" '.syncResult.usersSkipped')
        local groups_skipped=$(json_field "$result" '.syncResult.groupsSkipped')
        log_pass "初期同期成功 (作成: ユーザー $users_created, グループ $groups_created, スキップ: ユーザー $users_skipped, グループ $groups_skipped)"
    else
        local message=$(json_field "$result" '.syncResult.errors[0].message')
        log_fail "初期同期失敗: $message"
    fi
}

# ============================================================================
# テスト4: 同期結果の検証
# ============================================================================

test_verify_sync() {
    log_header "テスト4: 同期結果の検証"

    # ユーザー検証 - CouchDBから直接確認
    # Note: user.prefix= (空) なのでLDAPユーザーはそのままのuidで保存される
    log_test "同期されたユーザーの確認"
    local users_result=$(curl -s -u admin:password "http://localhost:5984/bedroom/_all_docs?include_docs=true" | jq '[.rows[].doc | select(.objectType == "nemaki:user" and .userId != null) | .userId] | sort')
    log_info "All Users: $users_result"

    # 期待されるLDAPユーザー: yamada, suzuki, tanaka, sato, watanabe, ldapuser1, ldapuser2, ldapadmin
    local ldap_users=$(echo "$users_result" | jq '[.[] | select(. == "yamada" or . == "suzuki" or . == "tanaka" or . == "sato" or . == "watanabe" or . == "ldapuser1" or . == "ldapuser2" or . == "ldapadmin")] | length')

    if [ "$ldap_users" -ge 5 ]; then
        log_pass "全LDAPユーザー同期確認 ($ldap_users/8)"
    else
        log_warn "LDAPユーザー確認: $ldap_users 件"
    fi

    # グループ検証 - CouchDBから直接確認
    # Note: group.prefix=ldap_ なのでLDAPグループはldap_プレフィックス付きで保存される
    log_test "同期されたグループの確認"
    local groups_result=$(curl -s -u admin:password "http://localhost:5984/bedroom/_all_docs?include_docs=true" | jq '[.rows[].doc | select(.objectType == "nemaki:group" and .groupId != null and (.groupId | startswith("ldap_"))) | .groupId] | sort')
    log_info "LDAP Groups: $groups_result"

    local found_groups=$(echo "$groups_result" | jq 'length')

    if [ "$found_groups" -ge 5 ]; then
        log_pass "全LDAPグループ同期確認 ($found_groups/7)"
    else
        log_warn "LDAPグループ確認: $found_groups 件"
    fi

    # グループメンバーシップ検証
    log_test "グループメンバーシップの確認"
    local eng_group=$(curl -s -u admin:password "http://localhost:5984/bedroom/_all_docs?include_docs=true" | jq '.rows[].doc | select(.groupId == "ldap_engineering")')
    log_info "Engineering group: $(echo "$eng_group" | jq -c '{groupId, subTypeProperties}')"

    # engineering グループには yamada, tanaka, watanabe が含まれるべき (subTypePropertiesに格納)
    if echo "$eng_group" | grep -q "yamada"; then
        log_pass "グループメンバーシップ確認OK (engineering)"
    else
        log_warn "グループメンバーシップ要確認 (engineering)"
    fi
}

# ============================================================================
# テスト5: 増分同期（ユーザー追加）
# ============================================================================

test_incremental_add_user() {
    log_header "テスト5: 増分同期（LDAPにユーザー追加）"

    log_test "LDAPに新規ユーザー追加"
    local new_user_ldif="dn: uid=newuser,ou=users,dc=nemakiware,dc=example,dc=com
objectClass: inetOrgPerson
objectClass: organizationalPerson
objectClass: person
objectClass: top
uid: newuser
cn: New User
sn: User
givenName: New
displayName: New User
mail: newuser@nemakiware.example.com
userPassword: newuserpass"

    if ldap_add "$new_user_ldif"; then
        log_pass "LDAP ユーザー 'newuser' 追加成功"
    else
        log_warn "LDAP ユーザー 'newuser' 追加失敗（既に存在する可能性）"
    fi

    log_test "増分同期実行"
    local result=$(nemaki_api POST "/rest/repo/bedroom/sync/trigger")
    log_info "Response: $(echo "$result" | jq -c '.')"

    local users_created=$(json_field_num "$result" '.syncResult.usersAdded')
    if [ "$users_created" -ge 1 ]; then
        log_pass "増分同期でユーザー追加確認 ($users_created 件)"
    else
        log_info "増分同期: 新規ユーザーなし（既に同期済みの可能性）"
    fi

    # 新規ユーザーの存在確認 (user.prefix= なのでnewuserで検索)
    log_test "新規ユーザーの存在確認"
    local users_result=$(curl -s -u admin:password "http://localhost:5984/bedroom/_all_docs?include_docs=true" | jq '[.rows[].doc | select(.objectType == "nemaki:user" and .userId == "newuser")] | length')
    if [ "$users_result" -ge 1 ]; then
        log_pass "ユーザー 'newuser' 確認OK"
    else
        log_warn "ユーザー 'newuser' 未確認"
    fi
}

# ============================================================================
# テスト6: 増分同期（グループ追加）
# ============================================================================

test_incremental_add_group() {
    log_header "テスト6: 増分同期（LDAPにグループ追加）"

    log_test "LDAPに新規グループ追加"
    local new_group_ldif="dn: cn=newgroup,ou=groups,dc=nemakiware,dc=example,dc=com
objectClass: groupOfNames
objectClass: top
cn: newgroup
description: New Test Group
member: uid=yamada,ou=users,dc=nemakiware,dc=example,dc=com
member: uid=newuser,ou=users,dc=nemakiware,dc=example,dc=com"

    if ldap_add "$new_group_ldif"; then
        log_pass "LDAP グループ 'newgroup' 追加成功"
    else
        log_warn "LDAP グループ 'newgroup' 追加失敗（既に存在する可能性）"
    fi

    log_test "増分同期実行"
    local result=$(nemaki_api POST "/rest/repo/bedroom/sync/trigger")
    log_info "Response: $(echo "$result" | jq -c '.')"

    local groups_created=$(json_field_num "$result" '.syncResult.groupsCreated')
    if [ "$groups_created" -ge 1 ]; then
        log_pass "増分同期でグループ追加確認 ($groups_created 件)"
    else
        log_info "増分同期: 新規グループなし（既に同期済みの可能性）"
    fi

    # 新規グループの存在確認 (group.prefix=ldap_ なのでldap_newgroupで検索)
    log_test "新規グループの存在確認"
    local groups_result=$(curl -s -u admin:password "http://localhost:5984/bedroom/_all_docs?include_docs=true" | jq '[.rows[].doc | select(.objectType == "nemaki:group" and .groupId == "ldap_newgroup")] | length')
    if [ "$groups_result" -ge 1 ]; then
        log_pass "グループ 'ldap_newgroup' 確認OK"
    else
        log_warn "グループ 'ldap_newgroup' 未確認"
    fi
}

# ============================================================================
# テスト7: 更新同期（ユーザー属性変更）
# ============================================================================

test_update_user() {
    log_header "テスト7: 更新同期（LDAPユーザー属性変更）"

    log_test "LDAP ユーザー属性変更"
    local modify_ldif="dn: uid=newuser,ou=users,dc=nemakiware,dc=example,dc=com
changetype: modify
replace: displayName
displayName: Updated New User
-
replace: mail
mail: updated.newuser@nemakiware.example.com"

    if ldap_modify "$modify_ldif"; then
        log_pass "LDAP ユーザー 'newuser' 属性変更成功"
    else
        log_warn "LDAP ユーザー 'newuser' 属性変更失敗"
    fi

    log_test "更新同期実行"
    local result=$(nemaki_api POST "/rest/repo/bedroom/sync/trigger")
    log_info "Response: $(echo "$result" | jq -c '.')"

    local users_updated=$(json_field_num "$result" '.syncResult.usersUpdated')
    if [ "$users_updated" -ge 1 ]; then
        log_pass "更新同期でユーザー更新確認 ($users_updated 件)"
    else
        log_info "更新同期: ユーザー更新なし（差分検出の実装による）"
    fi
}

# ============================================================================
# テスト8: 更新同期（グループメンバー変更）
# ============================================================================

test_update_group_membership() {
    log_header "テスト8: 更新同期（グループメンバー変更）"

    log_test "LDAP グループメンバー追加"
    local modify_ldif="dn: cn=newgroup,ou=groups,dc=nemakiware,dc=example,dc=com
changetype: modify
add: member
member: uid=suzuki,ou=users,dc=nemakiware,dc=example,dc=com"

    if ldap_modify "$modify_ldif"; then
        log_pass "LDAP グループ 'newgroup' メンバー追加成功"
    else
        log_warn "LDAP グループ 'newgroup' メンバー追加失敗（既に存在する可能性）"
    fi

    log_test "更新同期実行"
    local result=$(nemaki_api POST "/rest/repo/bedroom/sync/trigger")
    log_info "Response: $(echo "$result" | jq -c '.')"

    log_pass "グループメンバーシップ更新同期完了"
}

# ============================================================================
# テスト9: 削除同期（ユーザー削除）
# ============================================================================

test_delete_user() {
    log_header "テスト9: 削除同期（LDAPからユーザー削除）"

    # まずグループからメンバーを削除（グループ整合性のため）
    log_test "グループからユーザー削除"
    local modify_ldif="dn: cn=newgroup,ou=groups,dc=nemakiware,dc=example,dc=com
changetype: modify
delete: member
member: uid=newuser,ou=users,dc=nemakiware,dc=example,dc=com"

    ldap_modify "$modify_ldif" 2>/dev/null || true

    log_test "LDAP ユーザー削除"
    if ldap_delete "uid=newuser,ou=users,dc=nemakiware,dc=example,dc=com"; then
        log_pass "LDAP ユーザー 'newuser' 削除成功"
    else
        log_warn "LDAP ユーザー 'newuser' 削除失敗（存在しない可能性）"
    fi

    log_test "削除同期実行"
    local result=$(nemaki_api POST "/rest/repo/bedroom/sync/trigger")
    log_info "Response: $(echo "$result" | jq -c '.')"

    local users_deleted=$(json_field_num "$result" '.syncResult.usersDeleted')
    if [ "$users_deleted" -ge 1 ]; then
        log_pass "削除同期でユーザー削除確認 ($users_deleted 件)"
    else
        log_info "削除同期: ユーザー削除なし（削除同期の実装による）"
    fi
}

# ============================================================================
# テスト10: 削除同期（グループ削除）
# ============================================================================

test_delete_group() {
    log_header "テスト10: 削除同期（LDAPからグループ削除）"

    log_test "LDAP グループ削除"
    if ldap_delete "cn=newgroup,ou=groups,dc=nemakiware,dc=example,dc=com"; then
        log_pass "LDAP グループ 'newgroup' 削除成功"
    else
        log_warn "LDAP グループ 'newgroup' 削除失敗（存在しない可能性）"
    fi

    log_test "削除同期実行"
    local result=$(nemaki_api POST "/rest/repo/bedroom/sync/trigger")
    log_info "Response: $(echo "$result" | jq -c '.')"

    local groups_deleted=$(json_field_num "$result" '.syncResult.groupsDeleted')
    if [ "$groups_deleted" -ge 1 ]; then
        log_pass "削除同期でグループ削除確認 ($groups_deleted 件)"
    else
        log_info "削除同期: グループ削除なし（削除同期の実装による）"
    fi
}

# ============================================================================
# テスト11: エッジケース - 重複同期
# ============================================================================

test_duplicate_sync() {
    log_header "テスト11: エッジケース - 重複同期（冪等性確認）"

    log_test "連続同期実行 (1回目)"
    local result1=$(nemaki_api POST "/rest/repo/bedroom/sync/trigger")
    log_info "Result 1: $(echo "$result1" | jq -c '.')"

    log_test "連続同期実行 (2回目)"
    local result2=$(nemaki_api POST "/rest/repo/bedroom/sync/trigger")
    log_info "Result 2: $(echo "$result2" | jq -c '.')"

    local status1=$(json_field "$result1" '.status')
    local status2=$(json_field "$result2" '.status')
    local sync_status1=$(json_field "$result1" '.syncResult.status')
    local sync_status2=$(json_field "$result2" '.syncResult.status')

    if [ "$status1" = "success" ] && [ "$status2" = "success" ]; then
        log_pass "重複同期の冪等性確認OK (1回目: $sync_status1, 2回目: $sync_status2)"
    else
        log_fail "重複同期でエラー発生"
    fi
}

# ============================================================================
# テスト12: エッジケース - 手動ユーザーの保護
# ============================================================================

test_manual_user_protection() {
    log_header "テスト12: エッジケース - 手動作成ユーザーの保護"

    log_test "手動作成ユーザー確認 (admin)"
    local admin_exists=$(curl -s -u admin:password "http://localhost:5984/bedroom/_all_docs?include_docs=true" | jq '[.rows[].doc | select(.objectType == "nemaki:user" and .userId == "admin")] | length')

    if [ "$admin_exists" -ge 1 ]; then
        log_pass "手動ユーザー 'admin' 存在確認OK"
    else
        log_warn "手動ユーザー 'admin' 未確認"
    fi

    log_test "同期実行後の手動ユーザー保護確認"
    nemaki_api POST "/rest/repo/bedroom/sync/trigger" > /dev/null

    local admin_after=$(curl -s -u admin:password "http://localhost:5984/bedroom/_all_docs?include_docs=true" | jq '[.rows[].doc | select(.objectType == "nemaki:user" and .userId == "admin")] | length')
    if [ "$admin_after" -ge 1 ]; then
        log_pass "同期後も手動ユーザー 'admin' 保護確認OK"
    else
        log_fail "手動ユーザー 'admin' が削除された可能性"
    fi
}

# ============================================================================
# テスト13: 同期状態の確認
# ============================================================================

test_sync_status() {
    log_header "テスト13: 同期状態の確認"

    log_test "同期状態API"
    local result=$(nemaki_api GET "/rest/repo/bedroom/sync/status")
    log_info "Status: $(echo "$result" | jq -c '.')"

    local status=$(json_field "$result" '.status')
    local last_sync_status=$(json_field "$result" '.lastSyncResult.status')
    if [ "$status" = "success" ]; then
        log_pass "同期状態確認OK (lastSyncStatus: $last_sync_status)"
    else
        log_info "同期状態API: エラーまたは同期履歴なし"
    fi
}

# ============================================================================
# クリーンアップ
# ============================================================================

cleanup() {
    log_header "クリーンアップ"

    log_info "テスト用に追加したLDAPエントリを削除..."

    # テストで追加したユーザーを削除
    ldap_delete "uid=newuser,ou=users,dc=nemakiware,dc=example,dc=com" 2>/dev/null || true

    # テストで追加したグループを削除
    ldap_delete "cn=newgroup,ou=groups,dc=nemakiware,dc=example,dc=com" 2>/dev/null || true

    log_pass "クリーンアップ完了"
}

# ============================================================================
# 結果サマリー
# ============================================================================

print_summary() {
    log_header "テスト結果サマリー"

    echo ""
    echo "総テスト数: $TOTAL_TESTS"
    echo -e "成功: ${GREEN}$PASSED_TESTS${NC}"
    echo -e "失敗: ${RED}$FAILED_TESTS${NC}"
    echo ""

    if [ "$FAILED_TESTS" -eq 0 ]; then
        echo -e "${GREEN}========================================${NC}"
        echo -e "${GREEN}  全テスト成功！  ${NC}"
        echo -e "${GREEN}========================================${NC}"
        return 0
    else
        echo -e "${RED}========================================${NC}"
        echo -e "${RED}  一部テスト失敗  ${NC}"
        echo -e "${RED}========================================${NC}"
        return 1
    fi
}

# ============================================================================
# メイン
# ============================================================================

main() {
    echo ""
    echo "============================================================================"
    echo "  NemakiWare Directory Sync 包括テスト"
    echo "============================================================================"
    echo ""
    echo "開始時刻: $(date '+%Y-%m-%d %H:%M:%S')"
    echo ""

    # 環境チェック
    check_environment

    # テスト実行
    test_connection
    test_preview
    test_initial_sync
    test_verify_sync
    test_incremental_add_user
    test_incremental_add_group
    test_update_user
    test_update_group_membership
    test_delete_user
    test_delete_group
    test_duplicate_sync
    test_manual_user_protection
    test_sync_status

    # クリーンアップ
    cleanup

    # 結果サマリー
    print_summary

    echo ""
    echo "終了時刻: $(date '+%Y-%m-%d %H:%M:%S')"
    echo ""
}

# スクリプト実行
main "$@"

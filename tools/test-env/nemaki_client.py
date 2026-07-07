"""NemakiWare API クライアント (テスト環境セットアップツール用)。

3 つの API 面を薄くラップする:
  - api/v1 (Spring MVC / Jersey): ユーザ・グループ CRUD
  - CMIS Browser Binding: フォルダ・文書作成、ツリー削除
  - legacy Jersey REST: ACL 設定 (breakInheritance 対応)、RAG ヘルス
  - MCP JSON-RPC: login / rag_search / search / get_document_content

state-changing な REST 呼び出しには CSRF バイパス用の
X-Requested-With: XMLHttpRequest を常時付与する (CLAUDE.md 記載の標準パターン)。
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from typing import Any, Optional

import requests

CSRF_HEADERS = {"X-Requested-With": "XMLHttpRequest"}


class NemakiApiError(RuntimeError):
    """API 呼び出しが期待ステータス以外を返したときの例外。"""

    def __init__(self, message: str, status: Optional[int] = None, body: str = ""):
        super().__init__(message)
        self.status = status
        self.body = body[:2000]


@dataclass
class Ace:
    principal_id: str
    permissions: list[str]

    def to_json(self) -> dict[str, Any]:
        return {
            "principalId": self.principal_id,
            "permissions": self.permissions,
            "direct": True,
        }


@dataclass
class NemakiClient:
    base_url: str = "http://localhost:8080"
    repository_id: str = "bedroom"
    username: str = "admin"
    password: str = "admin"
    timeout: float = 60.0
    session: requests.Session = field(default_factory=requests.Session)

    def __post_init__(self) -> None:
        self.session.auth = (self.username, self.password)
        self.base_url = self.base_url.rstrip("/")

    # ---------------------------------------------------------------- URLs
    @property
    def browser_url(self) -> str:
        return f"{self.base_url}/core/browser/{self.repository_id}"

    @property
    def api_v1_url(self) -> str:
        return f"{self.base_url}/core/api/v1/cmis/repositories/{self.repository_id}"

    @property
    def rest_repo_url(self) -> str:
        return f"{self.base_url}/core/rest/repo/{self.repository_id}"

    @property
    def mcp_url(self) -> str:
        return f"{self.base_url}/core/mcp/message"

    # ------------------------------------------------------------- helpers
    def _check(self, resp: requests.Response, what: str, ok: tuple[int, ...] = (200, 201, 204)) -> requests.Response:
        if resp.status_code not in ok:
            raise NemakiApiError(
                f"{what} failed: HTTP {resp.status_code}", resp.status_code, resp.text
            )
        return resp

    @staticmethod
    def _extract_object_id(payload: dict[str, Any]) -> str:
        sp = payload.get("succinctProperties")
        if sp and "cmis:objectId" in sp:
            return sp["cmis:objectId"]
        props = payload.get("properties")
        if props and "cmis:objectId" in props:
            return props["cmis:objectId"]["value"]
        raise NemakiApiError(f"objectId not found in response: {json.dumps(payload)[:400]}")

    # -------------------------------------------------------- health checks
    def check_core(self) -> bool:
        try:
            r = self.session.get(
                f"{self.base_url}/core/atom/{self.repository_id}", timeout=self.timeout
            )
            return r.status_code == 200
        except requests.RequestException:
            return False

    def rag_health(self) -> dict[str, Any]:
        """RAG 検索の有効状態 (RAGSearchResource: {enabled, status}) を返す。"""
        r = self.session.get(
            f"{self.api_v1_url}/rag/health", headers=CSRF_HEADERS, timeout=self.timeout
        )
        self._check(r, "rag/health")
        return r.json()

    def rag_index_health(self) -> dict[str, Any]:
        """RAG インデックスの統計 (admin 用)。

        SearchEngineResource /search-engine/rag/health:
          {enabled, ragDocumentCount, ragChunkCount, eligibleDocuments, ...}
        """
        r = self.session.get(
            f"{self.api_v1_url}/search-engine/rag/health",
            headers=CSRF_HEADERS,
            timeout=self.timeout * 2,
        )
        self._check(r, "search-engine/rag/health")
        return r.json()

    def rag_reindex(self) -> dict[str, Any]:
        """RAG フル再インデックスをバックグラウンド起動する (admin)。"""
        r = self.session.post(
            f"{self.api_v1_url}/search-engine/rag/reindex",
            json={},
            headers=CSRF_HEADERS,
            timeout=self.timeout,
        )
        self._check(r, "search-engine/rag/reindex", ok=(200, 202))
        return r.json()

    # -------------------------------------------------------- users/groups
    def create_user(self, user_id: str, user_name: str, password: str, email: str = "") -> str:
        """ユーザ作成。既存なら 'exists' を返す。"""
        r = self.session.post(
            f"{self.api_v1_url}/users",
            json={
                "userId": user_id,
                "userName": user_name,
                "password": password,
                "email": email,
            },
            headers=CSRF_HEADERS,
            timeout=self.timeout,
        )
        if r.status_code == 201:
            return "created"
        if r.status_code == 409:
            return "exists"
        # 一部バージョンは重複を 400/500 で返すため、既存確認でフォロー
        if self.get_user(user_id) is not None:
            return "exists"
        raise NemakiApiError(f"create_user({user_id}) failed: HTTP {r.status_code}", r.status_code, r.text)

    def get_user(self, user_id: str) -> Optional[dict[str, Any]]:
        r = self.session.get(f"{self.api_v1_url}/users/{user_id}", timeout=self.timeout)
        if r.status_code == 200:
            return r.json()
        return None

    def delete_user(self, user_id: str) -> bool:
        r = self.session.delete(
            f"{self.api_v1_url}/users/{user_id}", headers=CSRF_HEADERS, timeout=self.timeout
        )
        return r.status_code in (200, 204)

    def create_group(
        self, group_id: str, group_name: str, users: list[str], groups: list[str]
    ) -> str:
        r = self.session.post(
            f"{self.api_v1_url}/groups",
            json={
                "groupId": group_id,
                "groupName": group_name,
                "users": users,
                "groups": groups,
            },
            headers=CSRF_HEADERS,
            timeout=self.timeout,
        )
        if r.status_code == 201:
            return "created"
        if r.status_code == 409:
            return "exists"
        if self.get_group(group_id) is not None:
            return "exists"
        raise NemakiApiError(f"create_group({group_id}) failed: HTTP {r.status_code}", r.status_code, r.text)

    def update_group(
        self, group_id: str, group_name: str, users: list[str], groups: list[str]
    ) -> str:
        r = self.session.put(
            f"{self.api_v1_url}/groups/{group_id}",
            json={
                "groupId": group_id,
                "groupName": group_name,
                "users": users,
                "groups": groups,
            },
            headers=CSRF_HEADERS,
            timeout=self.timeout,
        )
        self._check(r, f"update_group({group_id})", ok=(200,))
        return "updated"

    def get_group(self, group_id: str) -> Optional[dict[str, Any]]:
        r = self.session.get(f"{self.api_v1_url}/groups/{group_id}", timeout=self.timeout)
        if r.status_code == 200:
            return r.json()
        return None

    def delete_group(self, group_id: str) -> bool:
        r = self.session.delete(
            f"{self.api_v1_url}/groups/{group_id}", headers=CSRF_HEADERS, timeout=self.timeout
        )
        return r.status_code in (200, 204)

    # ------------------------------------------------------ CMIS browser
    def get_root_folder_id(self) -> str:
        r = self.session.get(
            f"{self.browser_url}/root?cmisselector=object&succinct=true", timeout=self.timeout
        )
        self._check(r, "get root folder")
        return self._extract_object_id(r.json())

    @staticmethod
    def _read_prop(obj: dict[str, Any], prop_id: str) -> Optional[Any]:
        """succinct / フル properties の両形式からプロパティ値を読む。

        注意: この browser binding は children で succinct=true を無視して
        フル properties 形式で返すため、両対応が必須。
        """
        sp = obj.get("succinctProperties")
        if sp is not None:
            return sp.get(prop_id)
        v = obj.get("properties", {}).get(prop_id)
        return v.get("value") if isinstance(v, dict) else None

    def get_child_by_name(self, parent_id: str, name: str) -> Optional[str]:
        """親フォルダ直下から名前一致の子の objectId を返す (ページング考慮)。

        パスアドレッシング (/root/{path}) は未解決パスでルートを返す挙動が
        あるため使わない。/root への objectId クエリで対象フォルダの
        children を取得する。
        """
        skip = 0
        while True:
            r = self.session.get(
                f"{self.browser_url}/root",
                params={
                    "cmisselector": "children",
                    "objectId": parent_id,
                    "succinct": "true",
                    "maxItems": "200",
                    "skipCount": str(skip),
                },
                timeout=self.timeout,
            )
            self._check(r, "get children")
            data = r.json()
            objects = data.get("objects", [])
            for entry in objects:
                obj = entry.get("object", entry)
                if self._read_prop(obj, "cmis:name") == name:
                    return self._read_prop(obj, "cmis:objectId")
            if not data.get("hasMoreItems") or not objects:
                return None
            skip += len(objects)

    def create_folder(self, parent_id: str, name: str) -> str:
        r = self.session.post(
            self.browser_url,
            data={
                "cmisaction": "createFolder",
                "objectId": parent_id,
                "propertyId[0]": "cmis:objectTypeId",
                "propertyValue[0]": "cmis:folder",
                "propertyId[1]": "cmis:name",
                "propertyValue[1]": name,
                "succinct": "true",
            },
            timeout=self.timeout,
        )
        self._check(r, f"createFolder({name})")
        return self._extract_object_id(r.json())

    def create_document(
        self, parent_id: str, name: str, content: bytes, mimetype: str
    ) -> str:
        r = self.session.post(
            self.browser_url,
            data={
                "cmisaction": "createDocument",
                "objectId": parent_id,
                "propertyId[0]": "cmis:objectTypeId",
                "propertyValue[0]": "cmis:document",
                "propertyId[1]": "cmis:name",
                "propertyValue[1]": name,
                "succinct": "true",
            },
            files={"content": (name, content, mimetype)},
            timeout=self.timeout,
        )
        self._check(r, f"createDocument({name})")
        return self._extract_object_id(r.json())

    _root_id_cache: Optional[str] = None

    def _assert_not_root(self, object_id: str, what: str) -> None:
        """リポジトリルートに対する破壊的操作を拒否する安全ガード。"""
        if self._root_id_cache is None:
            self._root_id_cache = self.get_root_folder_id()
        if object_id == self._root_id_cache:
            raise NemakiApiError(
                f"safety guard: {what} をリポジトリルート ({object_id}) に対して実行しようとしました"
            )

    def delete_tree(self, folder_id: str) -> bool:
        self._assert_not_root(folder_id, "deleteTree")
        r = self.session.post(
            self.browser_url,
            data={
                "cmisaction": "deleteTree",
                "objectId": folder_id,
                "allVersions": "true",
                "continueOnFailure": "true",
            },
            timeout=self.timeout * 5,
        )
        return r.status_code in (200, 201, 204)

    # --------------------------------------------------------------- ACL
    def set_acl(self, object_id: str, aces: list[Ace], break_inheritance: bool) -> None:
        """継承遮断つき ACL 設定。

        NemakiWare の仕様 (AclServiceImpl):
          - breakInheritance=true の呼び出しは「現在の実効 ACL をローカルにコピーして
            継承フラグを落とす」だけで、body の ACE は無視される。
          - 継承が切れた状態での通常呼び出しは、ローカル ACE を body の内容で置換する。
        よって「遮断 → 置換」の 2 段階で呼ぶ。
        """
        self._assert_not_root(object_id, "set_acl")
        url = f"{self.rest_repo_url}/node/{object_id}/acl"
        if break_inheritance:
            r = self.session.post(
                url,
                json={"breakInheritance": True, "permissions": []},
                headers=CSRF_HEADERS,
                timeout=self.timeout,
            )
            self._check_rest_status(r, f"break inheritance on {object_id}")
        r = self.session.post(
            url,
            json={"permissions": [a.to_json() for a in aces]},
            headers=CSRF_HEADERS,
            timeout=self.timeout,
        )
        self._check_rest_status(r, f"set ACL on {object_id}")

    def get_acl(self, object_id: str) -> dict[str, Any]:
        r = self.session.get(
            f"{self.rest_repo_url}/node/{object_id}/acl", timeout=self.timeout
        )
        self._check(r, "get ACL")
        return r.json()

    def _check_rest_status(self, resp: requests.Response, what: str) -> None:
        """legacy Jersey は失敗も HTTP 200 + {status:failure} で返すため body を検査する。"""
        self._check(resp, what)
        try:
            body = resp.json()
        except ValueError:
            raise NemakiApiError(f"{what}: non-JSON response", resp.status_code, resp.text)
        status = body.get("status")
        if status not in (True, "success"):
            raise NemakiApiError(f"{what}: server reported failure: {body}", resp.status_code, resp.text)

    # --------------------------------------------------------------- RAG REST
    def rag_search(
        self, query: str, top_k: int = 10, min_score: float = 0.0,
        auth: Optional[tuple[str, str]] = None,
    ) -> dict[str, Any]:
        """REST の RAG 検索 (検証用。auth を渡すと別ユーザとして実行)。"""
        r = requests.post(
            f"{self.api_v1_url}/rag/search",
            json={"query": query, "topK": top_k, "minScore": min_score},
            headers=CSRF_HEADERS,
            auth=auth or (self.username, self.password),
            timeout=self.timeout * 2,
        )
        self._check(r, "rag/search")
        return r.json()

    # --------------------------------------------------------------- MCP
    _mcp_id = 0

    def mcp_call(
        self, method: str, params: Optional[dict[str, Any]] = None,
        auth: Optional[tuple[str, str]] = None, retries: int = 3,
    ) -> dict[str, Any]:
        NemakiClient._mcp_id += 1
        payload: dict[str, Any] = {"jsonrpc": "2.0", "id": NemakiClient._mcp_id, "method": method}
        if params is not None:
            payload["params"] = params
        last_err: Optional[Exception] = None
        for attempt in range(retries):
            r = requests.post(
                self.mcp_url,
                json=payload,
                auth=auth,
                timeout=self.timeout * 2,
            )
            if r.status_code == 429:
                time.sleep(1.5 * (attempt + 1))
                continue
            if 400 <= r.status_code < 500:
                # 認証・バリデーション系はリトライしても変わらない
                raise NemakiApiError(f"MCP {method} failed: HTTP {r.status_code}", r.status_code, r.text)
            if r.status_code != 200:
                last_err = NemakiApiError(f"MCP {method} failed: HTTP {r.status_code}", r.status_code, r.text)
                time.sleep(0.5)
                continue
            body = r.json()
            if "error" in body:
                raise NemakiApiError(f"MCP {method} error: {body['error']}")
            return body.get("result", {})
        raise last_err or NemakiApiError(f"MCP {method}: retries exhausted")

    def mcp_tool_call(
        self, tool: str, arguments: dict[str, Any],
        auth: Optional[tuple[str, str]] = None,
    ) -> str:
        """tools/call を実行し、content[0].text を返す。isError 時は例外。"""
        result = self.mcp_call(
            "tools/call", {"name": tool, "arguments": arguments}, auth=auth
        )
        texts = [c.get("text", "") for c in result.get("content", []) if c.get("type") == "text"]
        text = "\n".join(texts)
        if result.get("isError"):
            raise NemakiApiError(f"MCP tool {tool} returned error: {text[:500]}")
        return text

    def mcp_login(self, username: str, password: str) -> str:
        """nemakiware_login でセッショントークンを取得。"""
        text = self.mcp_tool_call(
            "nemakiware_login",
            {"username": username, "password": password, "repositoryId": self.repository_id},
        )
        data = json.loads(text)
        token = data.get("session_token")
        if not token:
            raise NemakiApiError(f"MCP login for {username} did not return session_token: {text[:300]}")
        return token

    def mcp_rag_search(self, session_token: str, query: str, top_k: int = 8) -> str:
        return self.mcp_tool_call(
            "nemakiware_rag_search",
            {"query": query, "sessionToken": session_token, "topK": top_k},
        )

    def mcp_fulltext_search(self, session_token: str, cmis_query: str, max_items: int = 20) -> str:
        return self.mcp_tool_call(
            "nemakiware_search",
            {"query": cmis_query, "sessionToken": session_token, "maxItems": max_items},
        )

    def mcp_logout(self, session_token: str) -> None:
        try:
            self.mcp_tool_call("nemakiware_logout", {"sessionToken": session_token})
        except NemakiApiError:
            pass

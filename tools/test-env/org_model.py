"""テスト組織の宣言的定義。

階層組織 (3本部・7課 + 部門横断プロジェクト) のユーザ 15 名、
ネストグループ、文書種類毎のフォルダツリーと ACL パターンを定義する。

ID は既存の setup-test-data.sh が作るデータ (tanaka 等 8 ユーザ、
sales/engineering/hr/accounting/managers グループ、営業部/技術部/... フォルダ)
と衝突しないよう te- プレフィックス / 別フォルダ名を使う。
"""

from __future__ import annotations

from dataclasses import dataclass, field

DEFAULT_PASSWORD = "Pass1234"
TOP_FOLDER_NAME = "組織共有文書"


@dataclass(frozen=True)
class UserDef:
    user_id: str
    name: str          # 表示名 (日本語)
    title: str         # 役職 (README / シナリオ表示用)


@dataclass(frozen=True)
class GroupDef:
    group_id: str
    name: str
    users: tuple[str, ...] = ()
    groups: tuple[str, ...] = ()   # ネストグループ (子グループID)


@dataclass(frozen=True)
class FolderDef:
    """フォルダ定義。path は TOP_FOLDER_NAME からの相対パス要素。

    acl が None なら親の ACL を継承。指定時は継承を遮断して
    (principal, permission) のリストで置換する (admin は自動付与)。
    doc_category は doc_factory の文書カテゴリキー (リーフのみ)。
    """
    path: tuple[str, ...]
    acl: tuple[tuple[str, str], ...] | None = None
    doc_category: str | None = None


# ---------------------------------------------------------------------------
# ユーザ 15 名
# ---------------------------------------------------------------------------
USERS: tuple[UserDef, ...] = (
    # 経営
    UserDef("otsuka",  "大塚 剛",   "代表取締役社長"),
    UserDef("kudo",    "工藤 誠",   "営業本部長"),
    UserDef("hirata",  "平田 淳",   "技術本部長"),
    UserDef("nagai",   "永井 恵子", "管理本部長"),
    # 営業本部
    UserDef("mori",    "森 大輔",   "東日本営業課長"),
    UserDef("asada",   "浅田 未来", "東日本営業課 (兼 新製品Xプロジェクト)"),
    UserDef("ueda",    "上田 修",   "西日本営業課長"),
    UserDef("hoshino", "星野 佳奈", "西日本営業課 (兼 新製品Xプロジェクト)"),
    # 技術本部
    UserDef("fukuda",  "福田 亮",   "開発課長"),
    UserDef("ogawa",   "小川 蓮",   "開発課 (兼 新製品Xプロジェクト)"),
    UserDef("nishida", "西田 樹",   "開発課 (兼 新製品Xプロジェクト)"),
    UserDef("miyata",  "宮田 聡",   "インフラ課"),
    # 管理本部
    UserDef("shimizu", "清水 綾",   "人事課"),
    UserDef("okamoto", "岡本 健",   "経理課"),
    UserDef("baba",    "馬場 遼",   "法務課 (兼 新製品Xプロジェクト)"),
)


# ---------------------------------------------------------------------------
# グループ階層 (作成順: 子 → 親。ネストは groups で表現)
# ---------------------------------------------------------------------------
GROUPS: tuple[GroupDef, ...] = (
    # 課 (リーフ)
    GroupDef("te-sec-sales-east", "東日本営業課", users=("mori", "asada")),
    GroupDef("te-sec-sales-west", "西日本営業課", users=("ueda", "hoshino")),
    GroupDef("te-sec-dev",        "開発課",       users=("fukuda", "ogawa", "nishida")),
    GroupDef("te-sec-infra",      "インフラ課",   users=("miyata",)),
    GroupDef("te-sec-hr",         "人事課",       users=("shimizu",)),
    GroupDef("te-sec-finance",    "経理課",       users=("okamoto",)),
    GroupDef("te-sec-legal",      "法務課",       users=("baba",)),
    # 本部 (課をネスト + 本部長を直接所属)
    GroupDef("te-div-sales", "営業本部", users=("kudo",),
             groups=("te-sec-sales-east", "te-sec-sales-west")),
    GroupDef("te-div-eng",   "技術本部", users=("hirata",),
             groups=("te-sec-dev", "te-sec-infra")),
    GroupDef("te-div-corp",  "管理本部", users=("nagai",),
             groups=("te-sec-hr", "te-sec-finance", "te-sec-legal")),
    # 経営会議 / 部門横断プロジェクト
    GroupDef("te-mgmt",   "経営会議", users=("otsuka", "kudo", "hirata", "nagai")),
    GroupDef("te-proj-x", "新製品Xプロジェクト",
             users=("asada", "hoshino", "ogawa", "nishida", "baba")),
    # 全社 (本部 + 経営をネスト)
    GroupDef("te-all", "全社員",
             groups=("te-mgmt", "te-div-sales", "te-div-eng", "te-div-corp")),
)


# ---------------------------------------------------------------------------
# フォルダツリー + ACL
#
# ACL は「エリア」フォルダ (階層 1 段目) にのみ設定し、配下は継承させる。
# 例外: 人事配下の「給与」は人事課しか見えない強い制限を掛ける。
# ---------------------------------------------------------------------------
ALL, READ = "cmis:all", "cmis:read"

FOLDERS: tuple[FolderDef, ...] = (
    # --- 全社共有: 全社員 read / 人事課が管理 -----------------------------
    FolderDef(("全社共有",), acl=(("te-all", READ), ("te-sec-hr", ALL))),
    FolderDef(("全社共有", "社内規程"), doc_category="rules"),
    FolderDef(("全社共有", "お知らせ"), doc_category="announcements"),

    # --- 経営企画: 経営会議のみ -------------------------------------------
    FolderDef(("経営企画",), acl=(("te-mgmt", ALL),)),
    FolderDef(("経営企画", "取締役会議事録"), doc_category="board_minutes"),
    FolderDef(("経営企画", "中期経営計画"), doc_category="mid_term_plan"),

    # --- 営業本部: 営業 all / 経営 read / 他部門アクセス不可 ---------------
    FolderDef(("営業本部",), acl=(("te-div-sales", ALL), ("te-mgmt", READ))),
    FolderDef(("営業本部", "提案書"), doc_category="proposals"),
    FolderDef(("営業本部", "見積書"), doc_category="quotations"),
    FolderDef(("営業本部", "顧客訪問報告"), doc_category="visit_reports"),
    FolderDef(("営業本部", "売上レポート"), doc_category="sales_reports"),
    FolderDef(("営業本部", "契約書"), doc_category="sales_contracts"),

    # --- 技術本部: 技術 all / 経営 read ------------------------------------
    FolderDef(("技術本部",), acl=(("te-div-eng", ALL), ("te-mgmt", READ))),
    FolderDef(("技術本部", "設計書"), doc_category="design_docs"),
    FolderDef(("技術本部", "障害報告"), doc_category="incident_reports"),
    FolderDef(("技術本部", "リリースノート"), doc_category="release_notes"),
    FolderDef(("技術本部", "運用手順"), doc_category="runbooks"),
    FolderDef(("技術本部", "技術調査"), doc_category="tech_research"),

    # --- 管理本部: 課単位で分離 --------------------------------------------
    FolderDef(("管理本部",), acl=(("te-div-corp", READ), ("te-mgmt", READ))),
    FolderDef(("管理本部", "人事"), acl=(("te-sec-hr", ALL), ("te-mgmt", READ))),
    FolderDef(("管理本部", "人事", "評価"), doc_category="hr_evaluations"),
    # 給与: 人事課のみ (経営会議・本部長も不可)
    FolderDef(("管理本部", "人事", "給与"), acl=(("te-sec-hr", ALL),),
              doc_category="hr_salary"),
    FolderDef(("管理本部", "経理"), acl=(("te-sec-finance", ALL), ("te-mgmt", READ))),
    FolderDef(("管理本部", "経理", "請求書"), doc_category="invoices"),
    FolderDef(("管理本部", "経理", "予算"), doc_category="budgets"),
    FolderDef(("管理本部", "法務"), acl=(("te-sec-legal", ALL), ("te-mgmt", READ))),
    FolderDef(("管理本部", "法務", "契約審査"), doc_category="legal_reviews"),

    # --- 機密プロジェクトX: プロジェクトメンバーのみ (部門横断) ------------
    FolderDef(("機密プロジェクトX",), acl=(("te-proj-x", ALL),)),
    FolderDef(("機密プロジェクトX", "製品仕様"), doc_category="projx_specs"),
    FolderDef(("機密プロジェクトX", "価格戦略"), doc_category="projx_pricing"),
    FolderDef(("機密プロジェクトX", "市場調査"), doc_category="projx_market"),
)


def transitive_users(group_id: str) -> set[str]:
    """ネストグループを展開した推移的メンバー (ユーザID集合) を返す。

    NemakiWare の実効 ACL 評価 (PermissionService → getJoinedGroupByUserId の
    CouchDB ビュー) は「直接メンバー」しか見ないため、セットアップ時には
    各グループにこの推移的閉包を users として投入する (groups のネスト構造は
    組織構造の表現として保持する)。
    """
    g = next(gd for gd in GROUPS if gd.group_id == group_id)
    users = set(g.users)
    for child in g.groups:
        users |= transitive_users(child)
    return users


def folder_visibility() -> dict[str, set[str]]:
    """エリア毎の「読めるユーザ集合」を計算する (シナリオのアサーション用)。

    グループのネストを展開してユーザ ID の集合にする。
    """
    membership: dict[str, set[str]] = {}

    def expand(group_id: str) -> set[str]:
        if group_id in membership:
            return membership[group_id]
        g = next(gd for gd in GROUPS if gd.group_id == group_id)
        users = set(g.users)
        for child in g.groups:
            users |= expand(child)
        membership[group_id] = users
        return users

    result: dict[str, set[str]] = {}
    # ACL 継承をパスの前方一致で解決
    acl_by_path: dict[tuple[str, ...], tuple[tuple[str, str], ...]] = {
        f.path: f.acl for f in FOLDERS if f.acl is not None
    }
    for f in FOLDERS:
        # 最も深い ACL 指定祖先 (自分含む) を探す
        effective = None
        for depth in range(len(f.path), 0, -1):
            prefix = f.path[:depth]
            if prefix in acl_by_path:
                effective = acl_by_path[prefix]
                break
        readers: set[str] = set()
        if effective:
            for principal, _perm in effective:
                readers |= expand(principal)
        result["/".join(f.path)] = readers
    return result

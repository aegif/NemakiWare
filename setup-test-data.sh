#!/bin/bash
# =============================================================================
# NemakiWare テストデータ投入スクリプト
#
# Docker環境再構築後に実行して、動作確認用テストデータを投入する。
# 社内規程・インボイス・設計書など多様なオフィスドキュメント形式を使用。
#
# 投入内容:
#   - ユーザー・グループ（部署別グループ + 部署メンバー）
#   - フォルダ構造（営業部/技術部/人事部/経理部 + サブフォルダ）
#   - フォルダACL（部署グループにcmis:write、他部署はcmis:read）
#   - ドキュメント（.docx, .xlsx, .pptx, .pdf, .csv, .txt 混在）
#   - バージョン管理テスト用ドキュメント（チェックアウト→チェックイン）
#   - アーカイブ確認用（ドキュメント作成→削除でアーカイブに送る）
#
# 前提:
#   pip3 install python-docx openpyxl python-pptx reportlab
#
# 使い方:
#   ./setup-test-data.sh
# =============================================================================

set -euo pipefail

BASE_URL="http://localhost:8080/core/browser/bedroom"
AUTH="admin:admin"
WORK_DIR=$(mktemp -d)
trap "rm -rf $WORK_DIR" EXIT

# 色付き出力
green() { echo -e "\033[0;32m$1\033[0m"; }
red()   { echo -e "\033[0;31m$1\033[0m"; }
blue()  { echo -e "\033[0;34m$1\033[0m"; }

# JSON からオブジェクトIDを取得（succinctProperties / properties 両対応）
extract_id() {
  python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    sp = d.get('succinctProperties')
    if sp:
        print(sp['cmis:objectId'])
    elif 'properties' in d:
        print(d['properties']['cmis:objectId']['value'])
    else:
        print('ERROR:' + d.get('message', d.get('exception', str(d))), file=sys.stderr)
        sys.exit(1)
except Exception as e:
    print('PARSE_ERROR:' + str(e), file=sys.stderr)
    sys.exit(1)
"
}

# ヘルスチェック
echo "=== ヘルスチェック ==="
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" "$BASE_URL/root?cmisselector=object")
if [ "$HTTP_CODE" != "200" ]; then
  red "サーバーに接続できません (HTTP $HTTP_CODE)"
  exit 1
fi
green "サーバー接続OK"

# ルートフォルダIDを取得
ROOT_ID=$(curl -s -u "$AUTH" "$BASE_URL/root?cmisselector=object" | extract_id)
echo "ルートフォルダID: $ROOT_ID"

# ----------------------------------------------------------------------------
# ユーティリティ関数
# ----------------------------------------------------------------------------

create_folder() {
  local parent_id="$1"
  local name="$2"
  curl -s -u "$AUTH" -X POST \
    -F "cmisaction=createFolder" \
    -F "propertyId[0]=cmis:objectTypeId" \
    -F "propertyValue[0]=cmis:folder" \
    -F "propertyId[1]=cmis:name" \
    -F "propertyValue[1]=$name" \
    "$BASE_URL?objectId=$parent_id" | extract_id
}

create_text_document() {
  local parent_id="$1"
  local name="$2"
  local content="$3"
  echo "$content" | curl -s -u "$AUTH" -X POST \
    -F "cmisaction=createDocument" \
    -F "propertyId[0]=cmis:objectTypeId" \
    -F "propertyValue[0]=cmis:document" \
    -F "propertyId[1]=cmis:name" \
    -F "propertyValue[1]=$name" \
    -F "content=@-;filename=$name;type=text/plain" \
    "$BASE_URL?objectId=$parent_id" | extract_id
}

upload_file() {
  local parent_id="$1"
  local filepath="$2"
  local name="$3"
  local mimetype="$4"
  curl -s -u "$AUTH" -X POST \
    -F "cmisaction=createDocument" \
    -F "propertyId[0]=cmis:objectTypeId" \
    -F "propertyValue[0]=cmis:document" \
    -F "propertyId[1]=cmis:name" \
    -F "propertyValue[1]=$name" \
    -F "content=@$filepath;filename=$name;type=$mimetype" \
    "$BASE_URL?objectId=$parent_id" | extract_id
}

checkout_document() {
  local doc_id="$1"
  curl -s -u "$AUTH" -X POST \
    -F "cmisaction=checkOut" \
    -F "objectId=$doc_id" \
    "$BASE_URL" | extract_id
}

checkin_document() {
  local pwc_id="$1"
  local comment="$2"
  local filepath="$3"
  local name="$4"
  local mimetype="$5"
  curl -s -u "$AUTH" -X POST \
    -F "cmisaction=checkIn" \
    -F "objectId=$pwc_id" \
    -F "major=true" \
    -F "propertyId[0]=cmis:name" \
    -F "propertyValue[0]=$name" \
    -F "checkinComment=$comment" \
    -F "content=@$filepath;filename=$name;type=$mimetype" \
    "$BASE_URL" | extract_id
}

API_V1_URL="http://localhost:8080/core/api/v1/cmis/repositories/bedroom"

create_user() {
  local user_id="$1"
  local display_name="$2"
  local password="$3"
  local email="${4:-}"
  local http_code
  http_code=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" -X POST \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"$user_id\",\"userName\":\"$display_name\",\"password\":\"$password\",\"email\":\"$email\"}" \
    "$API_V1_URL/users")
  if [ "$http_code" = "201" ]; then
    echo "ok"
  else
    echo "error(HTTP $http_code)"
  fi
}

create_group() {
  local group_id="$1"
  local group_name="$2"
  shift 2
  local users_json="$1"
  local http_code
  http_code=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" -X POST \
    -H "Content-Type: application/json" \
    -d "{\"groupId\":\"$group_id\",\"groupName\":\"$group_name\",\"users\":$users_json,\"groups\":[]}" \
    "$API_V1_URL/groups")
  if [ "$http_code" = "201" ]; then
    echo "ok"
  else
    echo "error(HTTP $http_code)"
  fi
}

# ACL設定: 部署フォルダに書き込み権限を設定
# $1=objectId, $2=owner_group(cmis:all), $3...=reader_groups(cmis:read)
set_department_acl() {
  local object_id="$1"
  local owner_group="$2"
  shift 2
  local reader_groups=("$@")

  # curlコマンドを配列で構築（evalを避ける）
  local cmd=(curl -s -u "$AUTH" -X POST
    -F "cmisaction=applyACL"
    -F "objectId=$object_id"
    -F "addACEPrincipal[0]=$owner_group"
    -F "addACEPermission[0][0]=cmis:all"
  )

  local idx=1
  for rg in "${reader_groups[@]}"; do
    cmd+=(-F "addACEPrincipal[$idx]=$rg")
    cmd+=(-F "addACEPermission[$idx][0]=cmis:read")
    idx=$((idx + 1))
  done

  cmd+=("$BASE_URL")
  "${cmd[@]}" > /dev/null 2>&1
}

# ----------------------------------------------------------------------------
# 0. ユーザー・グループの作成
# ----------------------------------------------------------------------------
echo ""
blue "=== 0. ユーザー・グループの作成 ==="

# --- ユーザー作成 ---
echo "  ユーザー作成中..."
# 営業部メンバー
S1=$(create_user "tanaka" "田中太郎" "Pass1234" "tanaka@nemakiware.example")
echo "    tanaka (田中太郎/営業部): $S1"
S2=$(create_user "suzuki" "鈴木花子" "Pass1234" "suzuki@nemakiware.example")
echo "    suzuki (鈴木花子/営業部): $S2"

# 技術部メンバー
S3=$(create_user "sato" "佐藤一郎" "Pass1234" "sato@nemakiware.example")
echo "    sato (佐藤一郎/技術部): $S3"
S4=$(create_user "takahashi" "高橋健太" "Pass1234" "takahashi@nemakiware.example")
echo "    takahashi (高橋健太/技術部): $S4"
S5=$(create_user "watanabe" "渡辺美咲" "Pass1234" "watanabe@nemakiware.example")
echo "    watanabe (渡辺美咲/技術部): $S5"

# 人事部メンバー
S6=$(create_user "yamamoto" "山本直子" "Pass1234" "yamamoto@nemakiware.example")
echo "    yamamoto (山本直子/人事部): $S6"

# 経理部メンバー
S7=$(create_user "kobayashi" "小林由美" "Pass1234" "kobayashi@nemakiware.example")
echo "    kobayashi (小林由美/経理部): $S7"

# マネージャー（複数部署横断）
S8=$(create_user "yamada" "山田部長" "Pass1234" "yamada@nemakiware.example")
echo "    yamada (山田部長/管理職): $S8"

green "ユーザー作成完了（8名）"

# --- グループ作成 ---
echo ""
echo "  グループ作成中..."
G1=$(create_group "sales" "営業部" '["tanaka","suzuki","yamada"]')
echo "    sales (営業部): $G1"

G2=$(create_group "engineering" "技術部" '["sato","takahashi","watanabe","yamada"]')
echo "    engineering (技術部): $G2"

G3=$(create_group "hr" "人事部" '["yamamoto","yamada"]')
echo "    hr (人事部): $G3"

G4=$(create_group "accounting" "経理部" '["kobayashi","yamada"]')
echo "    accounting (経理部): $G4"

G5=$(create_group "managers" "管理職" '["yamada"]')
echo "    managers (管理職): $G5"

green "グループ作成完了（5グループ）"

# ----------------------------------------------------------------------------
# オフィスドキュメント生成
# ----------------------------------------------------------------------------
echo ""
blue "=== オフィスドキュメント生成 ==="

WORK_DIR_EXPORT="$WORK_DIR" python3 << 'PYTHON_EOF'
import os, sys
tmpdir = os.environ.get("WORK_DIR_EXPORT", "/tmp")

# --- 1. 就業規則 (Word .docx) ---
from docx import Document
from docx.shared import Pt, Inches
from docx.enum.text import WD_ALIGN_PARAGRAPH

doc = Document()
style = doc.styles['Normal']
style.font.name = 'Arial'
style.font.size = Pt(10.5)

doc.add_heading('就業規則', level=0)
doc.add_paragraph('NemakiWare株式会社', style='Subtitle')
doc.add_paragraph('施行日: 2026年1月1日')
doc.add_paragraph('')

chapters = [
    ('第1章 総則', [
        '第1条（目的）この規則は、NemakiWare株式会社の就業に関する事項を定めるものである。',
        '第2条（適用範囲）この規則は、全従業員に適用する。',
        '第3条（服務の原則）従業員は、この規則を遵守し、誠実に職務を遂行しなければならない。',
    ]),
    ('第2章 勤務時間・休日', [
        '第4条（勤務時間）所定勤務時間は1日8時間、週40時間とする。',
        '第5条（始業・終業時刻）始業: 9:00、終業: 18:00（休憩1時間）',
        '第6条（フレックスタイム）コアタイムは10:00〜15:00とする。',
        '第7条（休日）土曜日、日曜日、国民の祝日、年末年始（12/29〜1/3）',
    ]),
    ('第3章 休暇', [
        '第8条（年次有給休暇）勤続年数に応じて年10〜20日を付与する。',
        '第9条（特別休暇）慶弔休暇、育児休暇、介護休暇を別途定める。',
    ]),
    ('第4章 給与', [
        '第10条（給与）給与は月末締め翌月25日払いとする。',
        '第11条（諸手当）通勤手当、住宅手当、資格手当を支給する。',
    ]),
]
for title, articles in chapters:
    doc.add_heading(title, level=1)
    for article in articles:
        doc.add_paragraph(article)

doc.save(os.path.join(tmpdir, '就業規則.docx'))

# --- 2. 情報セキュリティポリシー (Word .docx) ---
doc2 = Document()
doc2.add_heading('情報セキュリティポリシー', level=0)
doc2.add_paragraph('NemakiWare株式会社')
doc2.add_paragraph('制定日: 2025年4月1日 / 改訂日: 2026年1月15日')

sections = [
    ('1. 基本方針', '当社は情報資産を適切に保護し、情報セキュリティの維持・向上に努める。'),
    ('2. アクセス制御', 'CMIS ACLに基づく権限管理を実施。最小権限の原則を適用し、全リポジトリオブジェクトへのアクセスをロールベースで制御する。'),
    ('3. 認証・認可', 'OIDC認証（Google / Microsoft Entra ID）を推奨。パスワードはBCryptハッシュ化必須。多要素認証の導入を段階的に推進する。'),
    ('4. 監査ログ', '全CMIS操作（作成・更新・削除・ACL変更・チェックイン/アウト）のログを記録し、90日間保持する。'),
    ('5. 暗号化', '通信: TLS 1.2以上必須。保存データ: CouchDBのAES-256暗号化を推奨。'),
    ('6. インシデント対応', 'セキュリティインシデント発生時は24時間以内に情報セキュリティ委員会に報告する。'),
]
for title, body in sections:
    doc2.add_heading(title, level=1)
    doc2.add_paragraph(body)

doc2.save(os.path.join(tmpdir, '情報セキュリティポリシー.docx'))

# --- 3. 旅費規程 (Word .docx) ---
doc3 = Document()
doc3.add_heading('旅費規程', level=0)
doc3.add_paragraph('NemakiWare株式会社')

travel_articles = [
    '第1条（目的）業務出張に伴う旅費の支給基準を定める。',
    '第2条（日当）国内出張: 3,000円/日、海外出張: 5,000円/日',
    '第3条（交通費）原則として公共交通機関の実費を支給。新幹線はのぞみ指定席まで可。',
    '第4条（宿泊費）国内: 上限12,000円/泊、海外: 上限20,000円/泊',
    '第5条（出張報告）出張終了後5営業日以内に出張報告書を提出すること。',
]
for article in travel_articles:
    doc3.add_paragraph(article)

doc3.save(os.path.join(tmpdir, '旅費規程.docx'))

# --- 4. インボイス (Excel .xlsx) ---
from openpyxl import Workbook
from openpyxl.styles import Font, Alignment, Border, Side, PatternFill

wb = Workbook()
ws = wb.active
ws.title = "請求書"

header_font = Font(bold=True, size=14)
label_font = Font(bold=True, size=10)
thin_border = Border(
    left=Side(style='thin'), right=Side(style='thin'),
    top=Side(style='thin'), bottom=Side(style='thin')
)
header_fill = PatternFill(start_color="4472C4", end_color="4472C4", fill_type="solid")
header_text = Font(bold=True, color="FFFFFF", size=10)

ws.merge_cells('A1:F1')
ws['A1'] = '請 求 書'
ws['A1'].font = Font(bold=True, size=18)
ws['A1'].alignment = Alignment(horizontal='center')

ws['A3'] = '請求番号:'
ws['B3'] = 'INV-2026-0042'
ws['A4'] = '請求日:'
ws['B4'] = '2026年2月1日'
ws['A5'] = '支払期限:'
ws['B5'] = '2026年2月28日'
ws['D3'] = '請求元: NemakiWare株式会社'
ws['D4'] = '〒100-0001 東京都千代田区千代田1-1-1'
ws['D5'] = 'TEL: 03-1234-5678'

ws['A7'] = '請求先: 株式会社サンプルコーポレーション 御中'

# 明細ヘッダー
cols = ['No.', '品目', '数量', '単位', '単価（円）', '金額（円）']
for i, col in enumerate(cols, 1):
    cell = ws.cell(row=9, column=i, value=col)
    cell.font = header_text
    cell.fill = header_fill
    cell.border = thin_border
    cell.alignment = Alignment(horizontal='center')

# 明細
items = [
    (1, 'NemakiWare Enterprise ライセンス（年間）', 5, 'ユーザー', 120000, 600000),
    (2, '導入コンサルティング', 3, '人日', 150000, 450000),
    (3, 'カスタマイズ開発（タイプ定義追加）', 10, '人日', 130000, 1300000),
    (4, '運用保守サポート（年間）', 1, '式', 360000, 360000),
    (5, 'ユーザートレーニング', 2, '回', 80000, 160000),
]
for row_idx, (no, desc, qty, unit, price, amount) in enumerate(items, 10):
    ws.cell(row=row_idx, column=1, value=no).border = thin_border
    ws.cell(row=row_idx, column=2, value=desc).border = thin_border
    ws.cell(row=row_idx, column=3, value=qty).border = thin_border
    ws.cell(row=row_idx, column=4, value=unit).border = thin_border
    c5 = ws.cell(row=row_idx, column=5, value=price)
    c5.border = thin_border
    c5.number_format = '#,##0'
    c6 = ws.cell(row=row_idx, column=6, value=amount)
    c6.border = thin_border
    c6.number_format = '#,##0'

# 合計
total_row = 10 + len(items)
ws.cell(row=total_row, column=4, value='小計').font = label_font
ws.cell(row=total_row, column=6, value=2870000).number_format = '#,##0'
ws.cell(row=total_row+1, column=4, value='消費税（10%）')
ws.cell(row=total_row+1, column=6, value=287000).number_format = '#,##0'
ws.cell(row=total_row+2, column=4, value='合計金額').font = Font(bold=True, size=12)
ws.cell(row=total_row+2, column=6, value=3157000).font = Font(bold=True, size=12)
ws.cell(row=total_row+2, column=6).number_format = '#,##0'

ws.column_dimensions['A'].width = 6
ws.column_dimensions['B'].width = 40
ws.column_dimensions['C'].width = 8
ws.column_dimensions['D'].width = 8
ws.column_dimensions['E'].width = 14
ws.column_dimensions['F'].width = 14

wb.save(os.path.join(tmpdir, '請求書_INV-2026-0042.xlsx'))

# --- 5. 月次売上レポート (Excel .xlsx) ---
wb2 = Workbook()
ws2 = wb2.active
ws2.title = "2026年1月売上"

ws2.merge_cells('A1:G1')
ws2['A1'] = '2026年1月 営業部月次売上レポート'
ws2['A1'].font = Font(bold=True, size=14)

headers = ['案件名', '顧客名', '受注額（円）', '粗利率', '担当者', 'ステータス', '備考']
for i, h in enumerate(headers, 1):
    cell = ws2.cell(row=3, column=i, value=h)
    cell.font = header_text
    cell.fill = header_fill
    cell.border = thin_border

deals = [
    ('NemakiWare導入', 'A社', 3500000, '35%', '田中', '受注', '年間ライセンス'),
    ('CMIS連携開発', 'B社', 2800000, '40%', '鈴木', '受注', 'カスタム開発含む'),
    ('文書管理コンサル', 'C社', 1500000, '50%', '佐藤', '進行中', '3月納品予定'),
    ('クラウド移行支援', 'D社', 4200000, '30%', '田中', '提案中', 'AWS環境'),
    ('セキュリティ監査', 'E社', 800000, '60%', '高橋', '受注', '2月実施'),
    ('運用保守更新', 'F社', 1200000, '70%', '鈴木', '受注', '自動更新'),
    ('トレーニング', 'G社', 600000, '80%', '佐藤', '完了', '2日間研修'),
]
for idx, row_data in enumerate(deals, 4):
    for col, val in enumerate(row_data, 1):
        cell = ws2.cell(row=idx, column=col, value=val)
        cell.border = thin_border
        if col == 3 and isinstance(val, int):
            cell.number_format = '#,##0'

ws2.cell(row=4+len(deals), column=2, value='合計').font = label_font
ws2.cell(row=4+len(deals), column=3, value=sum(d[2] for d in deals)).number_format = '#,##0'
ws2.cell(row=4+len(deals), column=3).font = label_font

for col in ['A','B','C','D','E','F','G']:
    ws2.column_dimensions[col].width = 18

wb2.save(os.path.join(tmpdir, '2026年1月_売上レポート.xlsx'))

# --- 6. 見積書 (Excel .xlsx) ---
wb3 = Workbook()
ws3 = wb3.active
ws3.title = "見積書"
ws3.merge_cells('A1:F1')
ws3['A1'] = '御 見 積 書'
ws3['A1'].font = Font(bold=True, size=16)
ws3['A1'].alignment = Alignment(horizontal='center')
ws3['A3'] = '見積番号: EST-2026-0018'
ws3['A4'] = '見積日: 2026年2月5日'
ws3['A5'] = '有効期限: 2026年3月5日'
ws3['D3'] = 'NemakiWare株式会社'
ws3['A7'] = '株式会社テクノソリューション 御中'
ws3['A8'] = '件名: NemakiWare ECM 導入プロジェクト'

est_headers = ['No.', '項目', '数量', '単価（円）', '金額（円）', '備考']
for i, h in enumerate(est_headers, 1):
    cell = ws3.cell(row=10, column=i, value=h)
    cell.font = header_text
    cell.fill = header_fill
    cell.border = thin_border

est_items = [
    (1, 'NemakiWare Enterprise ライセンス（50ユーザー）', 1, 2400000, 2400000, '年間サブスクリプション'),
    (2, '要件定義・基本設計', 15, 150000, 2250000, ''),
    (3, '詳細設計・開発', 30, 130000, 3900000, 'カスタムタイプ/ワークフロー'),
    (4, 'テスト・品質保証', 10, 120000, 1200000, ''),
    (5, 'データ移行', 5, 140000, 700000, '既存ファイルサーバーから'),
    (6, '導入・トレーニング', 5, 100000, 500000, '管理者/一般ユーザー各1回'),
    (7, '運用保守（年間）', 1, 480000, 480000, 'SLA 99.5%'),
]
for row_idx, (no, desc, qty, price, amount, note) in enumerate(est_items, 11):
    ws3.cell(row=row_idx, column=1, value=no).border = thin_border
    ws3.cell(row=row_idx, column=2, value=desc).border = thin_border
    ws3.cell(row=row_idx, column=3, value=qty).border = thin_border
    ws3.cell(row=row_idx, column=4, value=price).border = thin_border
    ws3.cell(row=row_idx, column=4).number_format = '#,##0'
    ws3.cell(row=row_idx, column=5, value=amount).border = thin_border
    ws3.cell(row=row_idx, column=5).number_format = '#,##0'
    ws3.cell(row=row_idx, column=6, value=note).border = thin_border

for col in ['A','B','C','D','E','F']:
    ws3.column_dimensions[col].width = 20
ws3.column_dimensions['B'].width = 42

wb3.save(os.path.join(tmpdir, '見積書_EST-2026-0018.xlsx'))

# --- 7. システム設計書 (PDF) ---
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib import colors
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
import reportlab.lib.enums as enums

# フォント登録（日本語対応）
font_registered = False
for font_path in [
    '/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc',
    '/System/Library/Fonts/Hiragino Sans GB.ttc',
    '/Library/Fonts/Arial Unicode.ttf',
    '/System/Library/Fonts/Supplemental/Arial Unicode.ttf',
]:
    try:
        pdfmetrics.registerFont(TTFont('JapaneseFont', font_path, subfontIndex=0))
        font_registered = True
        break
    except:
        continue

if not font_registered:
    try:
        pdfmetrics.registerFont(TTFont('JapaneseFont', '/System/Library/Fonts/ヒラギノ明朝 ProN.ttc', subfontIndex=0))
        font_registered = True
    except:
        pass

styles = getSampleStyleSheet()
if font_registered:
    styles.add(ParagraphStyle(name='JP', fontName='JapaneseFont', fontSize=10, leading=14))
    styles.add(ParagraphStyle(name='JPTitle', fontName='JapaneseFont', fontSize=18, leading=22,
                               alignment=enums.TA_CENTER, spaceAfter=12))
    styles.add(ParagraphStyle(name='JPH1', fontName='JapaneseFont', fontSize=14, leading=18,
                               spaceAfter=8, spaceBefore=12))
    styles.add(ParagraphStyle(name='JPH2', fontName='JapaneseFont', fontSize=12, leading=16,
                               spaceAfter=6, spaceBefore=8))
else:
    styles.add(ParagraphStyle(name='JP', fontSize=10, leading=14))
    styles.add(ParagraphStyle(name='JPTitle', fontSize=18, leading=22, alignment=enums.TA_CENTER, spaceAfter=12))
    styles.add(ParagraphStyle(name='JPH1', fontSize=14, leading=18, spaceAfter=8, spaceBefore=12))
    styles.add(ParagraphStyle(name='JPH2', fontSize=12, leading=16, spaceAfter=6, spaceBefore=8))

pdf_path = os.path.join(tmpdir, 'NemakiWare_システム設計書_v2.pdf')
pdf_doc = SimpleDocTemplate(pdf_path, pagesize=A4,
                             topMargin=25*mm, bottomMargin=25*mm,
                             leftMargin=20*mm, rightMargin=20*mm)

story = []
story.append(Paragraph('NemakiWare システム設計書', styles['JPTitle']))
story.append(Paragraph('Version 2.0', styles['JP']))
story.append(Paragraph('2026-02-01', styles['JP']))
story.append(Spacer(1, 20*mm))

story.append(Paragraph('1. システム概要', styles['JPH1']))
story.append(Paragraph(
    'NemakiWare は CMIS 1.1 準拠のオープンソースエンタープライズコンテンツ管理システムである。'
    'Spring Framework をベースに、Apache Chemistry OpenCMIS ライブラリを活用して'
    'CMIS Browser Binding / AtomPub Binding の両方をサポートする。', styles['JP']))

story.append(Paragraph('2. アーキテクチャ', styles['JPH1']))
arch_data = [
    ['レイヤー', 'テクノロジー', 'バージョン'],
    ['Backend', 'Spring Framework + Jakarta EE 11', '7.x'],
    ['CMIS Engine', 'Apache Chemistry OpenCMIS', '1.1.0-nemakiware'],
    ['Database', 'CouchDB', '3.4'],
    ['Search Engine', 'Apache Solr', '9.10'],
    ['UI Framework', 'React + TypeScript + Vite', '19 / 5.x / 7.x'],
    ['UI Library', 'Ant Design', '5.x'],
    ['App Server', 'Tomcat', '11.0'],
    ['Java', 'OpenJDK', '21'],
]
t = Table(arch_data, colWidths=[35*mm, 65*mm, 30*mm])
t.setStyle(TableStyle([
    ('BACKGROUND', (0,0), (-1,0), colors.HexColor('#4472C4')),
    ('TEXTCOLOR', (0,0), (-1,0), colors.white),
    ('FONTNAME', (0,0), (-1,-1), 'JapaneseFont' if font_registered else 'Helvetica'),
    ('FONTSIZE', (0,0), (-1,-1), 9),
    ('GRID', (0,0), (-1,-1), 0.5, colors.grey),
    ('ALIGN', (0,0), (-1,0), 'CENTER'),
    ('ROWBACKGROUNDS', (0,1), (-1,-1), [colors.white, colors.HexColor('#F2F2F2')]),
]))
story.append(t)

story.append(Paragraph('3. Docker 構成', styles['JPH1']))
docker_data = [
    ['コンテナ', 'イメージ', 'ポート', '役割'],
    ['core', 'tomcat:11.0-jre21', '8080', 'CMIS / REST / UI'],
    ['couchdb', 'couchdb:3.4', '5984', 'ドキュメントDB'],
    ['solr', 'solr:9.10-slim', '8983', '全文検索'],
]
t2 = Table(docker_data, colWidths=[25*mm, 40*mm, 20*mm, 40*mm])
t2.setStyle(TableStyle([
    ('BACKGROUND', (0,0), (-1,0), colors.HexColor('#4472C4')),
    ('TEXTCOLOR', (0,0), (-1,0), colors.white),
    ('FONTNAME', (0,0), (-1,-1), 'JapaneseFont' if font_registered else 'Helvetica'),
    ('FONTSIZE', (0,0), (-1,-1), 9),
    ('GRID', (0,0), (-1,-1), 0.5, colors.grey),
]))
story.append(t2)

story.append(Paragraph('4. CMIS API エンドポイント', styles['JPH1']))
api_text = (
    'Browser Binding: /core/browser/{repositoryId}<br/>'
    'AtomPub Binding: /core/atom/{repositoryId}<br/>'
    'REST API: /core/rest/repo/{repositoryId}<br/>'
    'WebSocket: /core/ws/changes'
)
story.append(Paragraph(api_text, styles['JP']))

story.append(Paragraph('5. セキュリティ設計', styles['JPH1']))
story.append(Paragraph(
    '認証: OIDC (Google / Microsoft Entra ID) + ローカル認証<br/>'
    '認可: CMIS ACL (cmis:read / cmis:write / cmis:all)<br/>'
    'パスワード: BCrypt ハッシュ<br/>'
    '通信: TLS 1.2+', styles['JP']))

pdf_doc.build(story)

# --- 8. API仕様書 (PDF) ---
api_pdf_path = os.path.join(tmpdir, 'NemakiWare_API仕様書.pdf')
api_doc = SimpleDocTemplate(api_pdf_path, pagesize=A4,
                             topMargin=25*mm, bottomMargin=25*mm,
                             leftMargin=20*mm, rightMargin=20*mm)
api_story = []
api_story.append(Paragraph('NemakiWare REST API 仕様書', styles['JPTitle']))
api_story.append(Spacer(1, 10*mm))

endpoints = [
    ('ドキュメント管理', [
        ('GET', '/browser/{repo}/root', 'ルートフォルダ取得'),
        ('POST', '/browser/{repo}', 'ドキュメント作成 (cmisaction=createDocument)'),
        ('POST', '/browser/{repo}', 'フォルダ作成 (cmisaction=createFolder)'),
        ('POST', '/browser/{repo}', 'チェックアウト (cmisaction=checkOut)'),
        ('POST', '/browser/{repo}', 'チェックイン (cmisaction=checkIn)'),
    ]),
    ('アーカイブ管理', [
        ('GET', '/rest/repo/{repo}/archive/index', 'アーカイブ一覧取得'),
        ('PUT', '/rest/repo/{repo}/archive/{id}/restore', 'ドキュメント復元'),
        ('DELETE', '/rest/repo/{repo}/archive/{id}', 'アーカイブ完全削除'),
    ]),
    ('タイプ管理', [
        ('GET', '/rest/repo/{repo}/type/list', 'タイプ定義一覧'),
        ('POST', '/rest/repo/{repo}/type/create', 'タイプ定義作成'),
        ('PUT', '/rest/repo/{repo}/type/update/{typeId}', 'タイプ定義更新'),
        ('DELETE', '/rest/repo/{repo}/type/delete/{typeId}', 'タイプ定義削除'),
    ]),
]

for section_title, eps in endpoints:
    api_story.append(Paragraph(section_title, styles['JPH1']))
    ep_data = [['Method', 'Endpoint', 'Description']]
    for method, path, desc in eps:
        ep_data.append([method, path, desc])
    t = Table(ep_data, colWidths=[20*mm, 75*mm, 50*mm])
    t.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), colors.HexColor('#4472C4')),
        ('TEXTCOLOR', (0,0), (-1,0), colors.white),
        ('FONTNAME', (0,0), (-1,-1), 'JapaneseFont' if font_registered else 'Helvetica'),
        ('FONTSIZE', (0,0), (-1,-1), 8),
        ('GRID', (0,0), (-1,-1), 0.5, colors.grey),
    ]))
    api_story.append(t)
    api_story.append(Spacer(1, 5*mm))

api_doc.build(api_story)

# --- 9. プロジェクト計画書 (PowerPoint .pptx) ---
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN

prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)

# タイトルスライド
slide = prs.slides.add_slide(prs.slide_layouts[0])
slide.shapes.title.text = "NemakiWare ECM 導入プロジェクト計画書"
slide.placeholders[1].text = "NemakiWare株式会社\n2026年2月"

# 概要スライド
slide2 = prs.slides.add_slide(prs.slide_layouts[1])
slide2.shapes.title.text = "プロジェクト概要"
body = slide2.placeholders[1]
tf = body.text_frame
tf.text = "目的: エンタープライズコンテンツ管理基盤の刷新"
p = tf.add_paragraph()
p.text = "期間: 2026年3月〜2026年8月（6ヶ月）"
p = tf.add_paragraph()
p.text = "予算: 11,430,000円（税込）"
p = tf.add_paragraph()
p.text = "スコープ: NemakiWare Enterprise導入 + データ移行 + カスタマイズ"

# フェーズスライド
slide3 = prs.slides.add_slide(prs.slide_layouts[1])
slide3.shapes.title.text = "導入フェーズ"
body3 = slide3.placeholders[1]
tf3 = body3.text_frame
phases = [
    "Phase 1: 要件定義・基本設計（3月〜4月）",
    "Phase 2: 詳細設計・開発（4月〜6月）",
    "Phase 3: テスト・品質保証（6月〜7月）",
    "Phase 4: データ移行・導入（7月）",
    "Phase 5: トレーニング・運用開始（8月）",
]
tf3.text = phases[0]
for phase in phases[1:]:
    p = tf3.add_paragraph()
    p.text = phase

# 体制図スライド
slide4 = prs.slides.add_slide(prs.slide_layouts[1])
slide4.shapes.title.text = "プロジェクト体制"
body4 = slide4.placeholders[1]
tf4 = body4.text_frame
tf4.text = "プロジェクトマネージャー: 山田太郎"
roles = [
    "リードエンジニア: 鈴木一郎（CMIS/Backend）",
    "フロントエンド: 佐藤花子（React UI）",
    "インフラ: 高橋健太（Docker/AWS）",
    "QA: 渡辺美咲（テスト/品質保証）",
]
for role in roles:
    p = tf4.add_paragraph()
    p.text = role

prs.save(os.path.join(tmpdir, 'プロジェクト計画書_NemakiWare導入.pptx'))

# --- 10. 議事録 (Word .docx) ---
doc_minutes = Document()
doc_minutes.add_heading('第12回 NemakiWare導入プロジェクト 定例会議 議事録', level=1)
doc_minutes.add_paragraph('日時: 2026年2月7日（金）14:00-15:30')
doc_minutes.add_paragraph('場所: 本社3F会議室A / Microsoft Teams（ハイブリッド）')
doc_minutes.add_paragraph('出席者: 山田PM、鈴木、佐藤、高橋、渡辺、顧客側: 中村部長、小林課長')

doc_minutes.add_heading('議題', level=2)
agenda = [
    '1. 前回アクションアイテムの確認',
    '2. Phase 2 開発進捗報告',
    '3. カスタムタイプ定義の仕様確認',
    '4. データ移行テスト結果報告',
    '5. 次回予定・アクションアイテム',
]
for item in agenda:
    doc_minutes.add_paragraph(item)

doc_minutes.add_heading('決定事項', level=2)
decisions = [
    'カスタムタイプ「contract」の追加を承認。プロパティ: 契約番号、契約先、契約期間、金額。',
    'データ移行は段階的に実施（Phase 1: 営業部、Phase 2: 技術部、Phase 3: 人事部・経理部）。',
    'ユーザートレーニングは部署別に2回ずつ実施。マニュアルは日英両方を準備。',
]
for d in decisions:
    doc_minutes.add_paragraph(d, style='List Bullet')

doc_minutes.add_heading('アクションアイテム', level=2)
actions = [
    '【鈴木】contract タイプ定義の実装 → 2/14',
    '【佐藤】タイプ管理UIのバリデーション強化 → 2/14',
    '【高橋】ステージング環境のデータ移行テスト → 2/21',
    '【渡辺】移行データの整合性チェックスクリプト作成 → 2/21',
    '【山田】次回定例: 2/14 14:00-15:30',
]
for a in actions:
    doc_minutes.add_paragraph(a, style='List Bullet')

doc_minutes.save(os.path.join(tmpdir, '議事録_第12回定例会議_20260207.docx'))

# --- 11. 経費精算書 (Excel .xlsx) ---
wb_exp = Workbook()
ws_exp = wb_exp.active
ws_exp.title = "経費精算"
ws_exp.merge_cells('A1:G1')
ws_exp['A1'] = '経費精算書'
ws_exp['A1'].font = Font(bold=True, size=14)
ws_exp['A3'] = '申請者: 鈴木一郎'
ws_exp['A4'] = '所属: 技術部'
ws_exp['A5'] = '申請日: 2026年2月3日'
ws_exp['D3'] = '精算番号: EXP-2026-0089'

exp_headers = ['日付', '内容', '勘定科目', '支払先', '金額（円）', '領収書', '備考']
for i, h in enumerate(exp_headers, 1):
    cell = ws_exp.cell(row=7, column=i, value=h)
    cell.font = header_text
    cell.fill = header_fill
    cell.border = thin_border

expenses = [
    ('2026/1/15', '顧客訪問 交通費', '旅費交通費', 'JR東日本', 2640, 'あり', 'A社定例'),
    ('2026/1/15', '顧客訪問 昼食', '会議費', 'レストランXX', 1500, 'あり', 'A社担当者と'),
    ('2026/1/22', '技術書購入', '図書費', 'Amazon', 3980, 'あり', 'CMIS解説書'),
    ('2026/1/28', '出張 新幹線', '旅費交通費', 'JR東海', 11200, 'あり', '大阪出張'),
    ('2026/1/28', '出張 宿泊', '旅費交通費', 'ホテルYY', 9800, 'あり', '大阪出張'),
    ('2026/1/29', '出張 日当', '旅費交通費', '-', 3000, '-', '大阪出張'),
    ('2026/2/1', 'クラウドサービス', '通信費', 'AWS', 15000, 'あり', '開発環境'),
]
for idx, row_data in enumerate(expenses, 8):
    for col, val in enumerate(row_data, 1):
        cell = ws_exp.cell(row=idx, column=col, value=val)
        cell.border = thin_border
        if col == 5 and isinstance(val, int):
            cell.number_format = '#,##0'

total_exp_row = 8 + len(expenses)
ws_exp.cell(row=total_exp_row, column=4, value='合計').font = label_font
ws_exp.cell(row=total_exp_row, column=5, value=sum(e[4] for e in expenses)).number_format = '#,##0'
ws_exp.cell(row=total_exp_row, column=5).font = label_font

for col in ['A','B','C','D','E','F','G']:
    ws_exp.column_dimensions[col].width = 16
ws_exp.column_dimensions['B'].width = 24

wb_exp.save(os.path.join(tmpdir, '経費精算書_鈴木一郎_202601.xlsx'))

# --- 12. 運用手順書 CSV ---
import csv
csv_path = os.path.join(tmpdir, '障害対応手順一覧.csv')
with open(csv_path, 'w', newline='', encoding='utf-8-sig') as f:
    writer = csv.writer(f)
    writer.writerow(['No.', '障害分類', '影響度', '対応手順', '担当', 'エスカレーション先', '復旧目標時間'])
    writer.writerow([1, 'CouchDB接続障害', '高', 'docker restart couchdb → ヘルスチェック → Tomcat再起動', 'インフラ担当', 'リードエンジニア', '30分'])
    writer.writerow([2, 'Solr検索障害', '中', 'Solrコア状態確認 → reindex API実行 → 検索テスト', 'インフラ担当', 'リードエンジニア', '1時間'])
    writer.writerow([3, 'Tomcatメモリ不足', '高', 'スレッドダンプ取得 → Tomcat再起動 → ヒープサイズ確認', 'インフラ担当', 'PM', '15分'])
    writer.writerow([4, 'CMIS認証エラー多発', '中', 'OIDC設定確認 → トークンキャッシュクリア → IdP側確認', 'セキュリティ担当', 'PM', '1時間'])
    writer.writerow([5, 'ディスク容量逼迫', '高', '不要ログ削除 → CouchDB compaction → アーカイブ移行', 'インフラ担当', 'PM', '2時間'])
    writer.writerow([6, 'UI表示崩れ', '低', 'ブラウザキャッシュクリア → CDN確認 → ビルド再デプロイ', 'フロント担当', 'リードエンジニア', '4時間'])

print(f"Generated files in {tmpdir}")
for f in sorted(os.listdir(tmpdir)):
    size = os.path.getsize(os.path.join(tmpdir, f))
    print(f"  {f} ({size:,} bytes)")
PYTHON_EOF

green "オフィスドキュメント生成完了"

# ----------------------------------------------------------------------------
# 1. フォルダ構造の作成
# ----------------------------------------------------------------------------
echo ""
blue "=== 1. フォルダ構造の作成 ==="

SALES_ID=$(create_folder "$ROOT_ID" "営業部")
echo "  営業部: $SALES_ID"

TECH_ID=$(create_folder "$ROOT_ID" "技術部")
echo "  技術部: $TECH_ID"

HR_ID=$(create_folder "$ROOT_ID" "人事部")
echo "  人事部: $HR_ID"

ACCOUNTING_ID=$(create_folder "$ROOT_ID" "経理部")
echo "  経理部: $ACCOUNTING_ID"

# サブフォルダ
SALES_REPORTS_ID=$(create_folder "$SALES_ID" "月次レポート")
echo "    営業部/月次レポート: $SALES_REPORTS_ID"

SALES_PROPOSALS_ID=$(create_folder "$SALES_ID" "提案書・見積")
echo "    営業部/提案書・見積: $SALES_PROPOSALS_ID"

TECH_DOCS_ID=$(create_folder "$TECH_ID" "設計書")
echo "    技術部/設計書: $TECH_DOCS_ID"

TECH_MANUALS_ID=$(create_folder "$TECH_ID" "マニュアル")
echo "    技術部/マニュアル: $TECH_MANUALS_ID"

TECH_MINUTES_ID=$(create_folder "$TECH_ID" "議事録")
echo "    技術部/議事録: $TECH_MINUTES_ID"

HR_POLICIES_ID=$(create_folder "$HR_ID" "社内規程")
echo "    人事部/社内規程: $HR_POLICIES_ID"

ACCOUNTING_INVOICE_ID=$(create_folder "$ACCOUNTING_ID" "請求書")
echo "    経理部/請求書: $ACCOUNTING_INVOICE_ID"

ACCOUNTING_EXPENSE_ID=$(create_folder "$ACCOUNTING_ID" "経費精算")
echo "    経理部/経費精算: $ACCOUNTING_EXPENSE_ID"

green "フォルダ構造の作成完了（4部署 + 8サブフォルダ）"

# ----------------------------------------------------------------------------
# 1.5 フォルダACLの設定
# ----------------------------------------------------------------------------
echo ""
blue "=== 1.5 フォルダACLの設定 ==="

# 各部署フォルダに対して:
#   - 所属部署グループ → cmis:all（読み書き全権限）
#   - 他部署グループ → cmis:read（読み取り専用）

set_department_acl "$SALES_ID" "sales" "engineering" "hr" "accounting"
echo "  営業部: sales→cmis:all, 他部署→cmis:read"

set_department_acl "$TECH_ID" "engineering" "sales" "hr" "accounting"
echo "  技術部: engineering→cmis:all, 他部署→cmis:read"

set_department_acl "$HR_ID" "hr" "sales" "engineering" "accounting"
echo "  人事部: hr→cmis:all, 他部署→cmis:read"

set_department_acl "$ACCOUNTING_ID" "accounting" "sales" "engineering" "hr"
echo "  経理部: accounting→cmis:all, 他部署→cmis:read"

green "ACL設定完了（4部署フォルダ）"

# ----------------------------------------------------------------------------
# 2. ドキュメントのアップロード
# ----------------------------------------------------------------------------
echo ""
blue "=== 2. ドキュメントのアップロード ==="

# --- 人事部/社内規程 ---
DOC_RULE1=$(upload_file "$HR_POLICIES_ID" "$WORK_DIR/就業規則.docx" "就業規則.docx" \
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
echo "  [docx] 人事部/社内規程/就業規則.docx: $DOC_RULE1"

DOC_RULE2=$(upload_file "$HR_POLICIES_ID" "$WORK_DIR/情報セキュリティポリシー.docx" "情報セキュリティポリシー.docx" \
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
echo "  [docx] 人事部/社内規程/情報セキュリティポリシー.docx: $DOC_RULE2"

DOC_RULE3=$(upload_file "$HR_POLICIES_ID" "$WORK_DIR/旅費規程.docx" "旅費規程.docx" \
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
echo "  [docx] 人事部/社内規程/旅費規程.docx: $DOC_RULE3"

# --- 営業部/月次レポート ---
DOC_REPORT1=$(upload_file "$SALES_REPORTS_ID" "$WORK_DIR/2026年1月_売上レポート.xlsx" "2026年1月_売上レポート.xlsx" \
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
echo "  [xlsx] 営業部/月次レポート/2026年1月_売上レポート.xlsx: $DOC_REPORT1"

DOC_REPORT2=$(create_text_document "$SALES_REPORTS_ID" "2025年度_年間売上サマリー.csv" \
  "月,売上（万円）,前年比,主要案件数
1月,1500,105%,12
2月,1620,108%,15
3月,2100,112%,18
4月,1800,103%,14
5月,1650,98%,11
6月,1900,107%,16
7月,1750,104%,13
8月,1400,95%,9
9月,1850,110%,15
10月,2050,115%,17
11月,1950,109%,16
12月,2200,118%,20
合計,21770,107%,176")
echo "  [csv]  営業部/月次レポート/2025年度_年間売上サマリー.csv: $DOC_REPORT2"

# --- 営業部/提案書・見積 ---
DOC_PROPOSAL=$(upload_file "$SALES_PROPOSALS_ID" "$WORK_DIR/見積書_EST-2026-0018.xlsx" "見積書_EST-2026-0018.xlsx" \
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
echo "  [xlsx] 営業部/提案書・見積/見積書_EST-2026-0018.xlsx: $DOC_PROPOSAL"

DOC_PPT=$(upload_file "$SALES_PROPOSALS_ID" "$WORK_DIR/プロジェクト計画書_NemakiWare導入.pptx" "プロジェクト計画書_NemakiWare導入.pptx" \
  "application/vnd.openxmlformats-officedocument.presentationml.presentation")
echo "  [pptx] 営業部/提案書・見積/プロジェクト計画書_NemakiWare導入.pptx: $DOC_PPT"

# --- 技術部/設計書 ---
DOC_DESIGN=$(upload_file "$TECH_DOCS_ID" "$WORK_DIR/NemakiWare_システム設計書_v2.pdf" "NemakiWare_システム設計書_v2.pdf" \
  "application/pdf")
echo "  [pdf]  技術部/設計書/NemakiWare_システム設計書_v2.pdf: $DOC_DESIGN"

DOC_API=$(upload_file "$TECH_DOCS_ID" "$WORK_DIR/NemakiWare_API仕様書.pdf" "NemakiWare_API仕様書.pdf" \
  "application/pdf")
echo "  [pdf]  技術部/設計書/NemakiWare_API仕様書.pdf: $DOC_API"

# --- 技術部/マニュアル ---
DOC_MANUAL=$(create_text_document "$TECH_MANUALS_ID" "NemakiWare運用マニュアル.txt" \
  "NemakiWare 運用マニュアル v3.1.0
=================================

1. 起動手順
   docker compose -f docker-compose-simple.yml up -d --build --force-recreate
   ※ docker compose restart は使用禁止（WAR未更新で起動するため）

2. ヘルスチェック
   curl -u admin:admin http://localhost:8080/core/atom/bedroom
   → HTTP 200 + XML が正常

3. ログ確認
   docker logs docker-core-1 --tail 100

4. CouchDB確認
   curl -u admin:password http://localhost:5984/_all_dbs

5. Solr確認
   curl http://localhost:8983/solr/nemaki/admin/ping

6. バックアップ
   CouchDB: curl http://localhost:5984/bedroom/_all_docs > backup.json
   設定: /usr/local/tomcat/conf/nemakiware.properties

7. トラブルシューティング
   UIが更新されない → npm run build → WAR再ビルド → Docker再起動
   検索が動かない → Solr reindex API実行")
echo "  [txt]  技術部/マニュアル/NemakiWare運用マニュアル.txt: $DOC_MANUAL"

DOC_INCIDENT=$(upload_file "$TECH_MANUALS_ID" "$WORK_DIR/障害対応手順一覧.csv" "障害対応手順一覧.csv" \
  "text/csv")
echo "  [csv]  技術部/マニュアル/障害対応手順一覧.csv: $DOC_INCIDENT"

# --- 技術部/議事録 ---
DOC_MINUTES=$(upload_file "$TECH_MINUTES_ID" "$WORK_DIR/議事録_第12回定例会議_20260207.docx" "議事録_第12回定例会議_20260207.docx" \
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
echo "  [docx] 技術部/議事録/議事録_第12回定例会議_20260207.docx: $DOC_MINUTES"

# --- 経理部/請求書 ---
DOC_INVOICE=$(upload_file "$ACCOUNTING_INVOICE_ID" "$WORK_DIR/請求書_INV-2026-0042.xlsx" "請求書_INV-2026-0042.xlsx" \
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
echo "  [xlsx] 経理部/請求書/請求書_INV-2026-0042.xlsx: $DOC_INVOICE"

# --- 経理部/経費精算 ---
DOC_EXPENSE=$(upload_file "$ACCOUNTING_EXPENSE_ID" "$WORK_DIR/経費精算書_鈴木一郎_202601.xlsx" "経費精算書_鈴木一郎_202601.xlsx" \
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
echo "  [xlsx] 経理部/経費精算/経費精算書_鈴木一郎_202601.xlsx: $DOC_EXPENSE"

green "ドキュメントアップロード完了（15件: docx×4, xlsx×4, pptx×1, pdf×2, csv×2, txt×2）"

# ----------------------------------------------------------------------------
# 3. バージョン管理テスト（PDF設計書 v2 → v3）
# ----------------------------------------------------------------------------
echo ""
blue "=== 3. バージョン管理テスト ==="

echo "  システム設計書(PDF)をチェックアウト..."
PWC_ID=$(checkout_document "$DOC_DESIGN")
if [ -n "$PWC_ID" ]; then
  echo "  PWC ID: $PWC_ID"

  # v3 PDF を生成
  python3 << PYEOF
import os, sys
tmpdir = "$WORK_DIR"
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
import reportlab.lib.enums as enums

font_registered = False
for font_path in [
    '/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc',
    '/System/Library/Fonts/Hiragino Sans GB.ttc',
]:
    try:
        pdfmetrics.registerFont(TTFont('JapaneseFont', font_path, subfontIndex=0))
        font_registered = True
        break
    except:
        continue

styles = getSampleStyleSheet()
fn = 'JapaneseFont' if font_registered else 'Helvetica'
styles.add(ParagraphStyle(name='JP', fontName=fn, fontSize=10, leading=14))
styles.add(ParagraphStyle(name='JPTitle', fontName=fn, fontSize=18, leading=22,
                           alignment=enums.TA_CENTER, spaceAfter=12))
styles.add(ParagraphStyle(name='JPH1', fontName=fn, fontSize=14, leading=18, spaceAfter=8))

pdf_path = os.path.join(tmpdir, 'NemakiWare_システム設計書_v3.pdf')
doc = SimpleDocTemplate(pdf_path, pagesize=A4, topMargin=25*mm, bottomMargin=25*mm)
story = []
story.append(Paragraph('NemakiWare システム設計書', styles['JPTitle']))
story.append(Paragraph('Version 3.0 — アーカイブ機能追加', styles['JP']))
story.append(Paragraph('2026-02-09', styles['JP']))
story.append(Spacer(1, 10*mm))
story.append(Paragraph('変更履歴', styles['JPH1']))
story.append(Paragraph('v3.0 (2026-02-09): アーカイブ管理機能・コールドストレージ基盤を追加', styles['JP']))
story.append(Paragraph('v2.0 (2026-02-01): Docker構成・セキュリティ設計を追加', styles['JP']))
story.append(Paragraph('v1.0 (2025-12-01): 初版', styles['JP']))
story.append(Spacer(1, 10*mm))
story.append(Paragraph('6. アーカイブ管理（新規）', styles['JPH1']))
story.append(Paragraph(
    '削除されたドキュメントはアーカイブ領域に移動し、管理者がUI上で復元または完全削除できる。'
    'アーカイブ状態のドキュメントは一定期間後にコールドストレージ(S3)へ自動移行可能。', styles['JP']))
story.append(Paragraph('7. Bedrock Embedding（β）', styles['JPH1']))
story.append(Paragraph(
    'RAG検索のEmbedding生成にAWS Bedrockを使用可能（TEI代替）。'
    'nemakiware.propertiesでrag.embedding.provider=bedrockを設定。', styles['JP']))
doc.build(story)
PYEOF

  echo "  バージョン3.0でチェックイン..."
  DOC_DESIGN_V3=$(checkin_document "$PWC_ID" "v3.0: アーカイブ機能・Bedrock Embedding追加" \
    "$WORK_DIR/NemakiWare_システム設計書_v3.pdf" "NemakiWare_システム設計書_v2.pdf" "application/pdf")
  echo "  チェックイン完了: $DOC_DESIGN_V3"
  green "バージョン管理テスト完了（PDF v2.0 → v3.0）"
else
  red "チェックアウト失敗"
fi

# ----------------------------------------------------------------------------
# 4. アーカイブテスト（削除→アーカイブ）
# ----------------------------------------------------------------------------
echo ""
blue "=== 4. アーカイブテスト ==="

ARCHIVE_DOC=$(create_text_document "$ROOT_ID" "【削除予定】旧フォーマット見積書.txt" \
  "この文書は旧フォーマットのため削除予定です。アーカイブ動作確認用。")
echo "  作成: 【削除予定】旧フォーマット見積書.txt ($ARCHIVE_DOC)"

curl -s -u "$AUTH" -X POST \
  -F "cmisaction=delete" \
  -F "objectId=$ARCHIVE_DOC" \
  "$BASE_URL" > /dev/null 2>&1
echo "  削除完了"

sleep 1
# totalItems, not len(archives): the index response is bounded (default limit 100),
# so counting the returned page would silently misreport large sets.
ARCHIVE_COUNT=$(curl -s -u "$AUTH" "http://localhost:8080/core/rest/repo/bedroom/archive/index" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('totalItems', len(d.get('archives', []))))
" 2>/dev/null)
echo "  アーカイブ件数: $ARCHIVE_COUNT"
green "アーカイブテスト完了"

# ----------------------------------------------------------------------------
# 5. Solr/RAG インデックス同期
# ----------------------------------------------------------------------------
echo ""
blue "=== 5. Solr/RAG インデックス同期 ==="

echo "  Solr Full Reindex 実行中..."
SOLR_REINDEX=$(curl -s -u "$AUTH" -X POST -H "X-Requested-With: XMLHttpRequest" "http://localhost:8080/core/rest/repo/bedroom/search-engine/reindex" 2>&1)
echo "    Solr: $(echo "$SOLR_REINDEX" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('message','unknown'))" 2>/dev/null)"

# Solr reindex 完了を待機
echo "  Solr Reindex 完了待ち..."
for i in $(seq 1 60); do
  SOLR_STATUS=$(curl -s -u "$AUTH" "http://localhost:8080/core/rest/repo/bedroom/search-engine/reindex/status" 2>/dev/null | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    status = d.get('status', 'unknown')
    indexed = d.get('indexedCount', 0)
    total = d.get('totalDocuments', 0)
    if status in ('completed', 'idle'):
        print(f'done ({indexed} indexed)')
    else:
        print(f'running ({indexed}/{total})')
except:
    print('checking...')
" 2>/dev/null)
  if echo "$SOLR_STATUS" | grep -q "done"; then
    echo "    $SOLR_STATUS"
    break
  fi
  sleep 2
done

echo "  RAG Full Reindex 実行中..."
RAG_REINDEX=$(curl -s -u "$AUTH" -X POST -H "X-Requested-With: XMLHttpRequest" "http://localhost:8080/core/rest/repo/bedroom/search-engine/rag/reindex" 2>&1)
echo "    RAG: $(echo "$RAG_REINDEX" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('message','unknown'))" 2>/dev/null)"
echo "    ※ RAG reindexはバックグラウンドで実行中（Embedding生成に数分かかります）"

# インデックス件数の確認
SOLR_COUNT=$(curl -s "http://localhost:8983/solr/nemaki/select?q=*:*&fq=-doc_type:rag_parent&fq=-doc_type:rag_chunk&rows=0&wt=json" | python3 -c "import sys,json; print(json.load(sys.stdin)['response']['numFound'])" 2>/dev/null)
RAG_COUNT=$(curl -s "http://localhost:8983/solr/nemaki/select?q=doc_type:rag_parent&rows=0&wt=json" | python3 -c "import sys,json; print(json.load(sys.stdin)['response']['numFound'])" 2>/dev/null)
echo ""
echo "  Solr インデックス: ${SOLR_COUNT:-?} ドキュメント"
echo "  RAG インデックス: ${RAG_COUNT:-?} ドキュメント"

green "インデックス同期完了"

# ----------------------------------------------------------------------------
# 6. 最終確認
# ----------------------------------------------------------------------------
echo ""
blue "=== 6. 最終確認 ==="

# ルート直下
curl -s -u "$AUTH" "$BASE_URL/root?cmisselector=children" | python3 -c "
import sys, json
d = json.load(sys.stdin)
objects = d.get('objects', [])
print(f'ルートフォルダ直下: {len(objects)}件')
for obj in objects:
    props = obj['object'].get('succinctProperties') or obj['object'].get('properties', {})
    if 'cmis:name' in props:
        name = props['cmis:name'] if isinstance(props['cmis:name'], str) else props['cmis:name']['value']
        base = props.get('cmis:baseTypeId')
        if isinstance(base, dict): base = base['value']
        icon = '  [folder]' if base == 'cmis:folder' else '  [doc]   '
        print(f'{icon} {name}')
" 2>/dev/null

# ドキュメント形式の内訳
echo ""
curl -s -u "$AUTH" "$BASE_URL/root?cmisselector=descendants&depth=-1" | python3 -c "
import sys, json
d = json.load(sys.stdin)

def count_docs(objects, counts):
    for obj in objects:
        o = obj.get('object', {})
        props = o.get('succinctProperties') or o.get('properties', {})
        base = props.get('cmis:baseTypeId')
        if isinstance(base, dict): base = base['value']
        name = props.get('cmis:name', '')
        if isinstance(name, dict): name = name['value']
        if base == 'cmis:document':
            ext = name.rsplit('.', 1)[-1].lower() if '.' in name else 'other'
            counts[ext] = counts.get(ext, 0) + 1
        children = obj.get('children', [])
        if children:
            count_docs(children, counts)
    return counts

counts = count_docs(d, {})
total = sum(counts.values())
print(f'ドキュメント形式の内訳（全{total}件）:')
for ext in sorted(counts.keys()):
    print(f'  .{ext}: {counts[ext]}件')
" 2>/dev/null

# アーカイブ
echo ""
curl -s -u "$AUTH" "http://localhost:8080/core/rest/repo/bedroom/archive/index" | python3 -c "
import sys, json
d = json.load(sys.stdin)
archives = d.get('archives', [])
total = d.get('totalItems', len(archives))
print(f'アーカイブ: {total}件 (表示は先頭{len(archives)}件)')
for a in archives:
    print(f'  [archive] {a[\"name\"]} (元パス: {a[\"path\"]})')
" 2>/dev/null

echo ""
green "============================================"
green " テストデータ投入完了"
green "============================================"
echo ""
echo "投入内容:"
echo "  ユーザー: 8名（tanaka, suzuki, sato, takahashi, watanabe, yamamoto, kobayashi, yamada）"
echo "  グループ: 5グループ（sales, engineering, hr, accounting, managers）"
echo "  フォルダ: 4部署 + 8サブフォルダ"
echo "  ACL: 4部署フォルダ（所属部署→cmis:all, 他部署→cmis:read）"
echo "  ドキュメント: 15件"
echo "    .docx (Word): 4件 — 就業規則, セキュリティポリシー, 旅費規程, 議事録"
echo "    .xlsx (Excel): 4件 — 売上レポート, 見積書, 請求書, 経費精算書"
echo "    .pptx (PowerPoint): 1件 — プロジェクト計画書"
echo "    .pdf (PDF): 2件 — システム設計書, API仕様書"
echo "    .csv: 2件 — 年間売上サマリー, 障害対応手順"
echo "    .txt: 2件 — 運用マニュアル, アーカイブテスト用"
echo "  バージョン履歴: 1件 (設計書PDF v2.0 → v3.0)"
echo "  アーカイブ: ${ARCHIVE_COUNT:-1}件"
echo ""
echo "テストユーザー（パスワード: Pass1234）:"
echo "  tanaka/suzuki → 営業部 (salesグループ)"
echo "  sato/takahashi/watanabe → 技術部 (engineeringグループ)"
echo "  yamamoto → 人事部 (hrグループ)"
echo "  kobayashi → 経理部 (accountingグループ)"
echo "  yamada → 管理職 (managersグループ、全部署所属)"
echo ""
echo "確認方法:"
echo "  UI: http://localhost:8080/core/ui/"
echo "  API: curl -u admin:admin '$BASE_URL/root?cmisselector=children'"
echo "  Archive: curl -u admin:admin 'http://localhost:8080/core/rest/repo/bedroom/archive/index'"
echo "  Users: curl -u admin:admin 'http://localhost:8080/core/api/v1/cmis/repositories/bedroom/users'"
echo "  Groups: curl -u admin:admin 'http://localhost:8080/core/api/v1/cmis/repositories/bedroom/groups'"

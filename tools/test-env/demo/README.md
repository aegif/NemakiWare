# MCP 権限デモ動画

「同じ質問でも、ユーザによって MCP 経由で得られる答えが変わる」ことを見せる
デモ動画とその生成資材。動画は tools/test-env で投入したリモート環境
(AWS、NemakiWare 3.2.3、Bedrock RAG) の**実データ・実 MCP 応答**で構成される。

## 成果物

- `output/nemakiware-mcp-demo.webm` — 完成動画 (1280×720 / 約2分25秒)
  - 主要ブラウザ (Chrome / Firefox / Edge) と VLC でそのまま再生可
  - QuickTime は webm 非対応。mp4 が要る場合は H.264 対応の ffmpeg で
    `ffmpeg -i output/nemakiware-mcp-demo.webm -c:v libx264 -pix_fmt yuv420p out.mp4`
    (Playwright 同梱 ffmpeg は VP8 のみで mp4 muxer 非搭載のため変換不可)

## 構成 (3 部)

1. **概観スライド** — タイトル → 組織図 (15名・ネストグループ13) →
   フォルダ/ACL (300文書・8エリア)
2. **実 UI ツアー** — 清水(人事課) と 宮田(インフラ課) で実際にログインし、
   トップフォルダ配下に見えるエリアが違うことを本物の画面で見せる
   (宮田はインフラ課⊂技術本部のネスト解決で技術本部が見える = v3.2.3)
3. **MCP シナリオ ×3** — 同一クエリをペルソナ別に MCP 実行し、ヒット文書と
   類似度バーを並べて対比 (機密プロジェクト / 人事機密 / 技術情報)

MCP 応答の表示は、ペルソナ 3 枚のカードに「読めるエリアのタグ + ヒット文書 +
類似度バー」を出す独自パネルで表現。極秘文書 (Aurora / 給与テーブル / 障害報告)
は紫の左罫でハイライトし、「誰に出て誰に出ないか」が一目で分かるようにした。

## 再生成手順

```bash
cd tools/test-env

# 1. リモート(or ローカル)環境にデータ投入済みであること
#    python3 setup_test_env.py --base-url https://<host> --no-flatten --wait-rag

# 2. 実 MCP 応答を収集して demo-data.json を作る
python3 demo/fetch_demo_data.py --base-url https://<host> --manifest manifest-remote.json

# 3. 動画を収録 (Playwright + viewer.html、実UIツアー込み)
node demo/record_demo.cjs https://<host>
#    → demo/output/nemakiware-mcp-demo.webm
```

## ファイル

| ファイル | 役割 |
|---|---|
| `fetch_demo_data.py` | 組織/フォルダ概観 + ユーザ別の**実 MCP 応答**を `demo-data.json` に固める |
| `viewer.html` | スライド + MCP 応答パネルを描画 (`window.demoApi` を録画側が駆動)。全描画 createElement/textContent で XSS 余地なし |
| `record_demo.cjs` | Playwright で viewer と実 UI を 1 本の webm に収録 |
| `demo-data.json` | 収録に使うデータ (再生成される中間成果物) |
| `output/` | 完成動画 |

## 注記

- `demo-data.json` の類似度スコアは Bedrock (titan-embed-text-v2) 実測値。
  ローカルの TEI (multilingual-e5-large) とはモデルが違うためスコアの絶対値は
  変わるが、「誰に何が出るか」の ACL フィルタ挙動は同じ。
- 実 UI ツアーは Ant Design のフォルダツリーを操作する。UI 改修でセレクタが
  変わった場合は `record_demo.cjs` の `uiTour` を調整。ツアーが失敗しても
  録画は継続する (概観 + MCP パートは維持)。

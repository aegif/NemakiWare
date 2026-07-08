/**
 * デモ動画レコーダー。
 *
 * viewer.html (スライド + MCP応答アニメーション) と、リモート NemakiWare の
 * 実UI (ユーザ毎のフォルダ可視性) を 1 本の動画 (1280x720 webm) に収録する。
 * 収録後、Playwright 同梱の ffmpeg で mp4 変換を試みる (H.264 が無ければ
 * webm のまま)。
 *
 * 使い方:
 *   node tools/test-env/demo/record_demo.cjs https://35.79.113.17.nip.io
 */
"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFileSync } = require("child_process");

const UI_NODE_MODULES = path.resolve(
  __dirname, "../../../core/src/main/webapp/ui/node_modules");
const { chromium } = require(path.join(UI_NODE_MODULES, "playwright"));

const DEMO_DIR = __dirname;
const OUT_DIR = path.join(DEMO_DIR, "output");
const BASE = process.argv[2] || "https://35.79.113.17.nip.io";
const DATA = JSON.parse(fs.readFileSync(path.join(DEMO_DIR, "demo-data.json"), "utf8"));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width: 1280, height: 720 },
    recordVideo: { dir: OUT_DIR, size: { width: 1280, height: 720 } },
    locale: "ja-JP",
  });
  const page = await context.newPage();
  const viewerUrl = "file://" + path.join(DEMO_DIR, "viewer.html");

  const showViewer = async () => {
    await page.goto(viewerUrl);
    await page.evaluate((d) => window.demoApi.init(d), DATA);
  };
  const show = (id) => page.evaluate((i) => window.demoApi.show(i), id);

  // ---- Part 1: タイトル / 組織 / フォルダ概観 -----------------------------
  console.log("part 1: overview slides");
  await showViewer();
  await show("s-title");   await sleep(5500);
  await show("s-org");     await sleep(10000);
  await show("s-folders"); await sleep(10000);

  // ---- Part 2: 実UIでの見え方の違い (2ユーザ) -----------------------------
  const caption = (text) => page.evaluate((t) => {
    const old = document.getElementById("demo-caption");
    if (old) old.remove();
    const div = document.createElement("div");
    div.id = "demo-caption";
    div.textContent = t;
    Object.assign(div.style, {
      position: "fixed", left: "50%", bottom: "26px", transform: "translateX(-50%)",
      background: "rgba(11,16,32,.92)", color: "#e8ecf4", border: "1px solid #3b4c78",
      borderRadius: "12px", padding: "12px 22px", fontSize: "17px", zIndex: 99999,
      fontFamily: '"Hiragino Sans", sans-serif', boxShadow: "0 8px 30px rgba(0,0,0,.5)",
      maxWidth: "900px", textAlign: "center",
    });
    document.body.appendChild(div);
  }, text);

  const uiTour = async (user, pass, note, dwellMs) => {
    console.log(`part 2: UI tour as ${user}`);
    try {
      await page.goto(BASE + "/core/ui/index.html", { waitUntil: "domcontentloaded" });
      const userInput = page
        .locator('input[placeholder*="ユーザー"], input[placeholder*="User"], input[name="username"]')
        .first();
      await userInput.waitFor({ timeout: 25000 });
      await caption(`実UI: ${note.who} としてログインします`);
      await sleep(1500);
      await userInput.fill(user);
      await page.locator('input[type="password"]').first().fill(pass);
      await sleep(700);
      await page.getByRole("button", { name: /ログイン|Login/ }).first().click();
      await page.waitForSelector(".ant-table", { timeout: 40000 });
      await sleep(2200);
      // トップフォルダ「組織共有文書」に入って、配下に見えるエリアの違いを見せる。
      // 行リンクは Ant のセル内 render 差でクリック不安定なので、フォルダツリーの
      // ノードを優先し、ダメなら行リンク、それも無理なら一覧のまま見せる。
      const treeNode = page.locator(".ant-tree-title, .ant-tree-node-content-wrapper")
        .filter({ hasText: "組織共有文書" }).first();
      const rowLink = page.getByRole("link", { name: "組織共有文書" }).first();
      let entered = false;
      for (const target of [treeNode, rowLink]) {
        try {
          await target.waitFor({ state: "visible", timeout: 6000 });
          await target.click({ timeout: 6000 });
          entered = true;
          break;
        } catch (_) { /* try next */ }
      }
      await sleep(entered ? 2000 : 500);
      await caption(note.msg);
      await sleep(dwellMs);
    } catch (e) {
      console.log(`  UI tour (${user}) skipped: ${e.message.split("\n")[0]}`);
    }
    // ログアウト相当 (トークン破棄)
    await context.clearCookies();
    try {
      await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
    } catch (_) { /* cross-origin etc. */ }
  };
  await uiTour("shimizu", "Pass1234", {
    who: "清水 綾 (人事課)",
    msg: "清水(人事課)に見えるのは「管理本部」「全社共有」だけ — 営業・技術・機密プロジェクトは存在ごと見えない",
  }, 6000);
  await uiTour("miyata", "Pass1234", {
    who: "宮田 聡 (インフラ課)",
    msg: "宮田(インフラ課)は「技術本部」「全社共有」— インフラ課⊂技術本部のネストグループ解決で権限が付与されている (v3.2.3)",
  }, 6000);

  // ---- Part 3: MCP シナリオ (ライブ応答のリプレイ) ------------------------
  console.log("part 3: MCP scenarios");
  await showViewer();
  for (let si = 0; si < DATA.scenarios.length; si++) {
    await show("s-scen-" + si);
    await sleep(3000);
    for (let pi = 0; pi < DATA.scenarios[si].personas.length; pi++) {
      await page.evaluate(([s, p]) => window.demoApi.runPersona(s, p), [si, pi]);
      await sleep(3800);
    }
    await page.evaluate((i) => window.demoApi.showNote(i), si);
    await sleep(4500);
  }
  await show("s-end");
  await sleep(6500);

  // ---- 保存 + 変換 ---------------------------------------------------------
  const video = page.video();
  await context.close(); // flush
  const rawPath = await video.path();
  const webm = path.join(OUT_DIR, "nemakiware-mcp-demo.webm");
  fs.copyFileSync(rawPath, webm);
  fs.unlinkSync(rawPath);
  await browser.close();
  console.log("webm:", webm);

  // Playwright 同梱 ffmpeg で mp4 (H.264) 変換を試みる
  try {
    const cacheDir = path.join(os.homedir(), "Library/Caches/ms-playwright");
    const ffmpegDir = fs.readdirSync(cacheDir).filter((d) => d.startsWith("ffmpeg")).sort().pop();
    const ffmpeg = path.join(cacheDir, ffmpegDir, "ffmpeg-mac");
    const encoders = execFileSync(ffmpeg, ["-hide_banner", "-encoders"]).toString();
    if (/\blibx264\b/.test(encoders)) {
      const mp4 = path.join(OUT_DIR, "nemakiware-mcp-demo.mp4");
      execFileSync(ffmpeg, ["-y", "-i", webm, "-c:v", "libx264", "-pix_fmt", "yuv420p",
        "-crf", "20", "-r", "30", mp4], { stdio: "inherit" });
      console.log("mp4 (H.264):", mp4);
    } else if (/\bmpeg4\b/.test(encoders)) {
      // Playwright 同梱 ffmpeg は H.264 非搭載。QuickTime 等で開ける
      // MPEG-4 Part 2 で .mp4 コンテナに包む (webm も併せて残す)。
      const mp4 = path.join(OUT_DIR, "nemakiware-mcp-demo.mp4");
      execFileSync(ffmpeg, ["-y", "-i", webm, "-c:v", "mpeg4", "-q:v", "3",
        "-pix_fmt", "yuv420p", "-r", "30", mp4], { stdio: "inherit" });
      console.log("mp4 (MPEG-4 Part 2):", mp4);
    } else {
      console.log("bundled ffmpeg has no usable mp4 encoder — webm のまま提供");
    }
  } catch (e) {
    console.log("mp4 conversion skipped:", e.message.split("\n")[0]);
  }
}

main().catch((e) => { console.error(e); process.exit(1); });

# test.skip 棚卸しポリシー

## 置換ルール

### 1. 実装済み・常設 UI → `await expect(locator).toBeVisible()`

タブ、ボタン、フォーム要素が常に存在するべき場合:

```typescript
// ❌ masking skip
if (await button.count() > 0) { /* test */ } else { test.skip('not found') }

// ❌ 無検証 green (skip より悪い)
if (await button.count() > 0) { /* test */ } else { console.log('not found') }

// ✅ 正しい
await expect(button).toBeVisible({ timeout: 10000 });
```

セレクタは i18n 対応の `getByRole` + 日英 regex を使用:
```typescript
page.getByRole('tab', { name: /セカンダリタイプ|Secondary Types/i })
page.getByRole('tab', { name: /リレーションシップ|Relationships/i })
page.getByRole('tab', { name: /プレビュー|Preview/i })
page.getByRole('button', { name: /アップロード|Upload/i })
```

### 2. テストデータで作れる前提 → API で固定してから必須 assert

```typescript
// beforeEach or beforeAll:
const docId = await cmisApi.createDocument(repoId, folderId, 'test.txt', 'content');
await cmisApi.addSecondaryType(repoId, docId, 'nemaki:externalIntegration');
await cmisApi.createRelationship(repoId, sourceId, targetId);

// test:
await expect(page.getByRole('tab', { name: /リレーションシップ|Relationships/i })).toBeVisible();
```

### 3. 環境依存 → `test.skip(condition, 'ENV: reason')` を残す

```typescript
// ✅ 正当な環境スキップ (プレフィックスで分類)
test.skip(!keycloakReachable, 'ENV: Keycloak not available');
test.skip(!solrAvailable, 'ENV: Solr indexing not operational');
test.skip(browserName !== 'chromium', 'BROWSER: CDP WebAuthn requires Chromium');
test.skip(!featureEnabled, 'FEATURE_FLAG: Cloud directory sync not enabled');
```

### 4. 未実装機能 → `test.fixme()`

```typescript
// ❌ skip で隠す
test.skip('Feature not implemented yet');

// ✅ fixme で「将来直すべき」として可視化
test.fixme('Bulk relationship creation UI not yet implemented');
```

## 検出基準: 「無検証 green パス」の優先除去

以下のパターンを優先的に修正:

1. `if (await *.count() > 0) { ... } else { console.log(...) }` — assert なしで成功
2. `if` ブロック外に assert がないテスト — 条件が false なら何も検証しない
3. `test.skip` の理由に「IS implemented in ...」を含む — 機能は存在するのにスキップ

## セレクタ方針

- **日本語固定セレクタは使わない** — `filter({ hasText: 'プレビュー' })` ではなく `getByRole` + regex
- `getByRole('tab', { name: /日本語|English/i })` を標準パターンとする
- data-testid がある要素はそちらを優先

/**
 * Multilingual Search E2E Tests for NemakiWare (RC4)
 *
 * Verifies that the dual-index search (text_ja + text_en) works correctly:
 * - Japanese text search (Kuromoji morphological analysis)
 * - English text search (Porter stemming, stop word removal)
 * - Mixed Japanese-English text search
 * - Language switching between document versions
 * - Regression: existing Japanese search not broken by text_en addition
 *
 * Prerequisites:
 * - NemakiWare server running on localhost:8080
 * - Solr running and connected
 * - text_en field and copyField rules deployed in schema.xml
 */

import { test, expect } from '@playwright/test';
import { generateTestId } from '../utils/test-helper';
import { cleanupTestData } from '../utils/cleanup-helper';

// Sweep test-created objects so they do not accumulate in the root and
// slow later specs' document-list queries (flaky `.ant-table` timeouts).
test.afterAll(({ browser }) => cleanupTestData(browser, {
  documents: ['ml-probe-%'],
}));

const TEST_USER = 'admin';
const TEST_PASSWORD = 'admin';
const REPOSITORY_ID = 'bedroom';
const BASE_URL = 'http://localhost:8080';

function basicAuth(): string {
  return `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`;
}

// Helper: Create document with text content via multipart upload
async function createDocumentWithContent(request: any, name: string, content: string): Promise<string> {
  // Get root folder ID
  const repoRes = await request.get(
    `${BASE_URL}/core/browser/${REPOSITORY_ID}?cmisselector=repositoryInfo`,
    { headers: { 'Authorization': basicAuth() } }
  );
  const repoData = await repoRes.json();
  const rootFolderId = repoData[REPOSITORY_ID]?.rootFolderId;

  const boundary = '----FormBoundary' + Math.random().toString(36).substring(2);

  let body = '';
  body += `--${boundary}\r\n`;
  body += 'Content-Disposition: form-data; name="cmisaction"\r\n\r\n';
  body += 'createDocument\r\n';

  body += `--${boundary}\r\n`;
  body += 'Content-Disposition: form-data; name="propertyId[0]"\r\n\r\n';
  body += 'cmis:objectTypeId\r\n';

  body += `--${boundary}\r\n`;
  body += 'Content-Disposition: form-data; name="propertyValue[0]"\r\n\r\n';
  body += 'cmis:document\r\n';

  body += `--${boundary}\r\n`;
  body += 'Content-Disposition: form-data; name="propertyId[1]"\r\n\r\n';
  body += 'cmis:name\r\n';

  body += `--${boundary}\r\n`;
  body += 'Content-Disposition: form-data; name="propertyValue[1]"\r\n\r\n';
  body += name + '\r\n';

  body += `--${boundary}\r\n`;
  body += `Content-Disposition: form-data; name="content"; filename="${name}"\r\n`;
  body += 'Content-Type: text/plain; charset=utf-8\r\n\r\n';
  body += content + '\r\n';

  body += `--${boundary}--\r\n`;

  const response = await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}?objectId=${rootFolderId}`, {
    headers: {
      'Authorization': basicAuth(),
      'Content-Type': `multipart/form-data; boundary=${boundary}`,
    },
    data: body,
  });

  const data = await response.json();
  return data.properties?.['cmis:objectId']?.value || data.succinctProperties?.['cmis:objectId'];
}

// Helper: Delete document
async function deleteDocument(request: any, objectId: string): Promise<void> {
  const formData = new URLSearchParams();
  formData.append('cmisaction', 'delete');
  formData.append('objectId', objectId);
  formData.append('allVersions', 'true');

  await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
    headers: {
      'Authorization': basicAuth(),
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: formData.toString(),
  });
}

// Helper: Execute CMIS query
async function executeCmisQuery(request: any, query: string): Promise<any> {
  const response = await request.get(
    `${BASE_URL}/core/browser/${REPOSITORY_ID}?cmisselector=query&q=${encodeURIComponent(query)}`,
    { headers: { 'Authorization': basicAuth() } }
  );
  return response.json();
}

// Helper: Wait for Solr to index a document (poll by name)
async function waitForSolrIndex(request: any, docName: string, maxWaitMs: number = 60000): Promise<boolean> {
  const startTime = Date.now();
  const query = `SELECT cmis:objectId FROM cmis:document WHERE cmis:name = '${docName}'`;
  while (Date.now() - startTime < maxWaitMs) {
    try {
      const result = await executeCmisQuery(request, query);
      const results = result.results || [];
      if (results.length > 0) {
        return true;
      }
    } catch {
      // Query error, continue polling
    }
    await new Promise(resolve => setTimeout(resolve, 3000));
  }
  return false;
}

// Helper: Search via CONTAINS and return matching object IDs
async function searchContains(request: any, searchTerm: string): Promise<string[]> {
  const query = `SELECT cmis:objectId FROM cmis:document WHERE CONTAINS('${searchTerm}')`;
  const result = await executeCmisQuery(request, query);
  return (result.results || []).map((r: any) =>
    r.properties?.['cmis:objectId']?.value || r.succinctProperties?.['cmis:objectId']
  );
}

// Helper: Update document content (setContentStream) and return new version ID
async function updateDocumentContent(request: any, objectId: string, content: string, filename: string): Promise<string> {
  // Get current change token
  const getRes = await request.get(
    `${BASE_URL}/core/browser/${REPOSITORY_ID}/${objectId}?cmisselector=object`,
    { headers: { 'Authorization': basicAuth() } }
  );
  const data = await getRes.json();
  const changeToken = data.properties?.['cmis:changeToken']?.value
    || data.succinctProperties?.['cmis:changeToken'];

  const boundary = '----FormBoundary' + Math.random().toString(36).substring(2);

  let body = '';
  body += `--${boundary}\r\n`;
  body += 'Content-Disposition: form-data; name="cmisaction"\r\n\r\n';
  body += 'setContentStream\r\n';

  body += `--${boundary}\r\n`;
  body += 'Content-Disposition: form-data; name="objectId"\r\n\r\n';
  body += objectId + '\r\n';

  body += `--${boundary}\r\n`;
  body += 'Content-Disposition: form-data; name="changeToken"\r\n\r\n';
  body += changeToken + '\r\n';

  body += `--${boundary}\r\n`;
  body += 'Content-Disposition: form-data; name="overwriteFlag"\r\n\r\n';
  body += 'true\r\n';

  body += `--${boundary}\r\n`;
  body += `Content-Disposition: form-data; name="content"; filename="${filename}"\r\n`;
  body += 'Content-Type: text/plain; charset=utf-8\r\n\r\n';
  body += content + '\r\n';

  body += `--${boundary}--\r\n`;

  const response = await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
    headers: {
      'Authorization': basicAuth(),
      'Content-Type': `multipart/form-data; boundary=${boundary}`,
    },
    data: body,
  });

  const resData = await response.json();
  return resData.properties?.['cmis:objectId']?.value
    || resData.succinctProperties?.['cmis:objectId']
    || objectId;
}

test.describe('Multilingual Search Tests', () => {

  let solrAvailable = true;

  test.beforeAll(async ({ request }) => {
    // Probe: check if Solr indexing is operational
    const probeName = `ml-probe-${generateTestId()}.txt`;
    let probeId: string | null = null;
    try {
      probeId = await createDocumentWithContent(request, probeName, 'Solr multilingual probe');
      const indexed = await waitForSolrIndex(request, probeName, 30000);
      if (!indexed) {
        console.log('[ML-SEARCH] Solr indexing not operational - tests will be skipped');
        solrAvailable = false;
      }
    } catch {
      solrAvailable = false;
    } finally {
      if (probeId) await deleteDocument(request, probeId).catch(() => {});
    }
  });

  test.beforeEach(async () => {
    test.skip(!solrAvailable, 'ENV: Solr indexing is not operational in this environment');
  });

  // ========================================
  // Japanese Text Search (既存動作の回帰テスト)
  // ========================================

  test('ML1: Japanese text search - exact keyword match', async ({ request }) => {
    const uid = generateTestId();
    const docName = `ml-ja-exact-${uid}.txt`;
    const keyword = `日本語検索テスト${uid}`;
    let docId: string | null = null;

    try {
      docId = await createDocumentWithContent(request, docName,
        `この文書は${keyword}のための文書です。`);

      const indexed = await waitForSolrIndex(request, docName);
      test.skip(!indexed, 'ENV: Solr indexing timeout');

      const results = await searchContains(request, keyword);
      expect(results).toContain(docId);
    } finally {
      if (docId) await deleteDocument(request, docId).catch(() => {});
    }
  });

  test('ML2: Japanese morphological analysis - verb inflection', async ({ request }) => {
    const uid = generateTestId();
    const docName = `ml-ja-morph-${uid}.txt`;
    const docNameNoMatch = `ml-ja-morph-nomatch-${uid}.txt`;
    // Kuromoji は「実行しています」を「実行」+「する」+「いる」に分解する。
    // テスト: 活用形「実行しています」を含む文書を、原形「実行」で検索してヒットさせる。
    // ユニークマーカーで他文書との分離を確保しつつ、形態素解析の検証も行う。
    const uniqueMarker = `JAMORPH${uid}`;
    let docId: string | null = null;
    let docIdNoMatch: string | null = null;

    try {
      // 活用形を含む文書（「実行しています」= 連用形+て+いる+ます）
      docId = await createDocumentWithContent(request, docName,
        `${uniqueMarker} ユーザーがシステム上で操作を実行しています。`);

      // 「実行」を含まない文書
      docIdNoMatch = await createDocumentWithContent(request, docNameNoMatch,
        `${uniqueMarker} この文書には関連する動詞がありません。`);

      const idx1 = await waitForSolrIndex(request, docName);
      const idx2 = await waitForSolrIndex(request, docNameNoMatch);
      test.skip(!idx1 || !idx2, 'ENV: Solr indexing timeout');

      // 原形「実行」で検索 → 活用形「実行しています」を含む文書がヒットするはず
      const results = await searchContains(request, `${uniqueMarker} 実行`);
      expect(results).toContain(docId);
      expect(results).not.toContain(docIdNoMatch);
    } finally {
      if (docId) await deleteDocument(request, docId).catch(() => {});
      if (docIdNoMatch) await deleteDocument(request, docIdNoMatch).catch(() => {});
    }
  });

  // ========================================
  // English Text Search (新規 text_en 機能)
  // ========================================

  test('ML3: English text search - exact keyword match', async ({ request }) => {
    const uid = generateTestId();
    const docName = `ml-en-exact-${uid}.txt`;
    const keyword = `ENGLISHTEST${uid}`;
    let docId: string | null = null;

    try {
      docId = await createDocumentWithContent(request, docName,
        `This document contains ${keyword} for search verification.`);

      const indexed = await waitForSolrIndex(request, docName);
      test.skip(!indexed, 'ENV: Solr indexing timeout');

      const results = await searchContains(request, keyword);
      expect(results).toContain(docId);
    } finally {
      if (docId) await deleteDocument(request, docId).catch(() => {});
    }
  });

  test('ML4: English stemming - inflected form matches base form', async ({ request }) => {
    const uid = generateTestId();
    const docNameInflected = `ml-en-stem-inflected-${uid}.txt`;
    const docNameNoMatch = `ml-en-stem-nomatch-${uid}.txt`;
    // Use a unique marker to isolate test documents, and test stemming separately.
    // Document contains ONLY the inflected form "running" (not "run").
    // Search with the base form "run" — should match via Porter stemming.
    const uniqueMarker = `XSTEMTEST${uid}`;
    let docIdInflected: string | null = null;
    let docIdNoMatch: string | null = null;

    try {
      // Document with inflected form ONLY ("running", NOT "run")
      docIdInflected = await createDocumentWithContent(request, docNameInflected,
        `${uniqueMarker} The program was running all day long without stopping.`);

      // Document without any form of "run"
      docIdNoMatch = await createDocumentWithContent(request, docNameNoMatch,
        `${uniqueMarker} This document has completely unrelated content about cooking.`);

      const idx1 = await waitForSolrIndex(request, docNameInflected);
      const idx2 = await waitForSolrIndex(request, docNameNoMatch);
      test.skip(!idx1 || !idx2, 'ENV: Solr indexing timeout');

      // Search with marker + base form "run" — should match "running" via stemming
      const results = await searchContains(request, `${uniqueMarker} run`);
      expect(results).toContain(docIdInflected);
      expect(results).not.toContain(docIdNoMatch);
    } finally {
      if (docIdInflected) await deleteDocument(request, docIdInflected).catch(() => {});
      if (docIdNoMatch) await deleteDocument(request, docIdNoMatch).catch(() => {});
    }
  });

  test('ML5: English search is case-insensitive', async ({ request }) => {
    const uid = generateTestId();
    const docName = `ml-en-case-${uid}.txt`;
    const keyword = `CaseSensTest${uid}`;
    let docId: string | null = null;

    try {
      docId = await createDocumentWithContent(request, docName,
        `The ${keyword} value should be found regardless of case.`);

      const indexed = await waitForSolrIndex(request, docName);
      test.skip(!indexed, 'ENV: Solr indexing timeout');

      // Search with different casing
      const results = await searchContains(request, keyword.toLowerCase());
      expect(results).toContain(docId);
    } finally {
      if (docId) await deleteDocument(request, docId).catch(() => {});
    }
  });

  // ========================================
  // Mixed Japanese-English Text Search
  // ========================================

  test('ML6: Mixed language document - Japanese keyword matches', async ({ request }) => {
    const uid = generateTestId();
    const docName = `ml-mixed-ja-${uid}.txt`;
    const jaKeyword = `混合テスト${uid}`;
    let docId: string | null = null;

    try {
      docId = await createDocumentWithContent(request, docName,
        `This is a mixed language document. ${jaKeyword}を含む文書です。The end.`);

      const indexed = await waitForSolrIndex(request, docName);
      test.skip(!indexed, 'ENV: Solr indexing timeout');

      const results = await searchContains(request, jaKeyword);
      expect(results).toContain(docId);
    } finally {
      if (docId) await deleteDocument(request, docId).catch(() => {});
    }
  });

  test('ML7: Mixed language document - English keyword matches', async ({ request }) => {
    const uid = generateTestId();
    const docName = `ml-mixed-en-${uid}.txt`;
    const enKeyword = `MIXEDLANGTEST${uid}`;
    let docId: string | null = null;

    try {
      docId = await createDocumentWithContent(request, docName,
        `この文書は日本語と英語が混在しています。${enKeyword} is the identifier.`);

      const indexed = await waitForSolrIndex(request, docName);
      test.skip(!indexed, 'ENV: Solr indexing timeout');

      const results = await searchContains(request, enKeyword);
      expect(results).toContain(docId);
    } finally {
      if (docId) await deleteDocument(request, docId).catch(() => {});
    }
  });

  // ========================================
  // Cross-language negative tests
  // ========================================

  test('ML8: Japanese keyword does NOT match English-only document', async ({ request }) => {
    const uid = generateTestId();
    const docNameEn = `ml-neg-en-${uid}.txt`;
    const jaKeyword = `存在しない検索語${uid}`;
    let docIdEn: string | null = null;

    try {
      docIdEn = await createDocumentWithContent(request, docNameEn,
        `This document is entirely in English with no Japanese text. Marker: NEGTEST${uid}`);

      const indexed = await waitForSolrIndex(request, docNameEn);
      test.skip(!indexed, 'ENV: Solr indexing timeout');

      const results = await searchContains(request, jaKeyword);
      expect(results).not.toContain(docIdEn);
    } finally {
      if (docIdEn) await deleteDocument(request, docIdEn).catch(() => {});
    }
  });

  // ========================================
  // Version Language Switch Edge Case
  // ========================================

  test('ML9: Language switch between versions - new content is searchable', async ({ request }) => {
    const uid = generateTestId();
    const docName = `ml-version-switch-${uid}.txt`;
    const jaMarker = `バージョン言語テスト${uid}`;
    const enMarker = `VERSIONLANGTEST${uid}`;
    let docId: string | null = null;

    try {
      // Version 1: Japanese content
      docId = await createDocumentWithContent(request, docName,
        `${jaMarker} この文書は日本語で書かれています。`);

      const indexed1 = await waitForSolrIndex(request, docName);
      test.skip(!indexed1, 'ENV: Solr indexing timeout');

      // Verify Japanese content is searchable
      let results = await searchContains(request, jaMarker);
      expect(results).toContain(docId);

      // Version 2: Update content to English
      const newDocId = await updateDocumentContent(request, docId,
        `${enMarker} This document has been updated to English content.`,
        docName);
      // Update docId if it changed
      if (newDocId && newDocId !== docId) {
        docId = newDocId;
      }

      // Wait for re-indexing (poll for new content)
      let reindexed = false;
      const start = Date.now();
      while (Date.now() - start < 60000) {
        const enResults = await searchContains(request, enMarker);
        if (enResults.includes(docId)) {
          reindexed = true;
          break;
        }
        await new Promise(resolve => setTimeout(resolve, 3000));
      }
      test.skip(!reindexed, 'ENV: Re-indexing timeout after content update');

      // Verify English content is searchable
      results = await searchContains(request, enMarker);
      expect(results).toContain(docId);

      // Old Japanese content should no longer match (content was replaced)
      results = await searchContains(request, jaMarker);
      expect(results).not.toContain(docId);
    } finally {
      if (docId) await deleteDocument(request, docId).catch(() => {});
    }
  });

  // ========================================
  // Regression: text_en addition does not break existing search
  // ========================================

  test('ML10: Multiple documents - correct isolation across languages', async ({ request }) => {
    const uid = generateTestId();
    const docNameJa = `ml-iso-ja-${uid}.txt`;
    const docNameEn = `ml-iso-en-${uid}.txt`;
    const docNameMixed = `ml-iso-mixed-${uid}.txt`;
    const jaOnly = `隔離確認用語句${uid}`;
    const enOnly = `ISOLATIONCHECK${uid}`;
    const mixedMarker = `MIXISO${uid}`;
    let docIdJa: string | null = null;
    let docIdEn: string | null = null;
    let docIdMixed: string | null = null;

    try {
      docIdJa = await createDocumentWithContent(request, docNameJa,
        `${jaOnly}を含む純粋な日本語文書です。`);

      docIdEn = await createDocumentWithContent(request, docNameEn,
        `This is a pure English document with ${enOnly} marker.`);

      docIdMixed = await createDocumentWithContent(request, docNameMixed,
        `${mixedMarker} この文書は${jaOnly}と${enOnly}の両方を含みます。`);

      const idx1 = await waitForSolrIndex(request, docNameJa);
      const idx2 = await waitForSolrIndex(request, docNameEn);
      const idx3 = await waitForSolrIndex(request, docNameMixed);
      test.skip(!idx1 || !idx2 || !idx3, 'ENV: Solr indexing timeout');

      // Japanese keyword: should match JA doc and mixed doc, not EN doc
      let results = await searchContains(request, jaOnly);
      expect(results).toContain(docIdJa);
      expect(results).not.toContain(docIdEn);
      expect(results).toContain(docIdMixed);

      // English keyword: should match EN doc and mixed doc, not JA doc
      results = await searchContains(request, enOnly);
      expect(results).not.toContain(docIdJa);
      expect(results).toContain(docIdEn);
      expect(results).toContain(docIdMixed);

      // Mixed marker: should match only mixed doc
      results = await searchContains(request, mixedMarker);
      expect(results).not.toContain(docIdJa);
      expect(results).not.toContain(docIdEn);
      expect(results).toContain(docIdMixed);
    } finally {
      if (docIdJa) await deleteDocument(request, docIdJa).catch(() => {});
      if (docIdEn) await deleteDocument(request, docIdEn).catch(() => {});
      if (docIdMixed) await deleteDocument(request, docIdMixed).catch(() => {});
    }
  });
});

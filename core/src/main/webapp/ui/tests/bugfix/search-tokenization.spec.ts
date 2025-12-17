/**
 * Bug Fix Verification: Search Tokenization Issue (2025-12-17)
 *
 * Reported Scenario:
 * - When searching with CONTAINS('SEARCH_TEST_KEYWORD_123'), documents that only
 *   contain 'test' in their content were incorrectly returned
 * - This was caused by Solr tokenizing the search term into 'search', 'test', 'keyword', '123'
 *   and matching any of these tokens independently
 *
 * Root Cause:
 * - SolrPredicateWalker was creating TermQuery without phrase quoting
 * - Solr's StandardTokenizer splits on underscores
 * - Result: 'SEARCH_TEST_KEYWORD_123' → ['search', 'test', 'keyword', '123']
 *
 * Fix:
 * - Wrap search terms in double quotes to force phrase search
 * - This ensures exact sequence matching, not individual token matching
 */

import { test, expect } from '@playwright/test';

// Test configuration
const TEST_USER = 'admin';
const TEST_PASSWORD = 'admin';
const REPOSITORY_ID = 'bedroom';
const BASE_URL = 'http://localhost:8080';
const ROOT_FOLDER_ID = 'e02f784f8360a02cc14d1314c10038ff';

// Unique search term that won't exist in other documents
const UNIQUE_SEARCH_TERM = `UNIQUE_SEARCH_VERIFY_${Date.now()}`;

function basicAuth(): string {
  return `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`;
}

// Helper: Create document with content
async function createDocumentWithContent(request: any, name: string, content: string): Promise<string> {
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
  body += 'Content-Type: text/plain\r\n\r\n';
  body += content + '\r\n';

  body += `--${boundary}--\r\n`;

  const response = await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}?objectId=${ROOT_FOLDER_ID}`, {
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
  const response = await request.get(`${BASE_URL}/core/browser/${REPOSITORY_ID}?cmisselector=query&q=${encodeURIComponent(query)}`, {
    headers: { 'Authorization': basicAuth() },
  });
  return response.json();
}

// Helper: Add secondary type to document
async function addSecondaryType(request: any, objectId: string, secondaryTypeId: string): Promise<void> {
  // Get current change token
  const getResponse = await request.get(`${BASE_URL}/core/browser/${REPOSITORY_ID}/${objectId}?cmisselector=object`, {
    headers: { 'Authorization': basicAuth() },
  });
  const data = await getResponse.json();
  const changeToken = data.properties?.['cmis:changeToken']?.value;

  const formData = new URLSearchParams();
  formData.append('cmisaction', 'update');
  formData.append('objectId', objectId);
  formData.append('changeToken', changeToken);
  formData.append('addSecondaryTypeIds', secondaryTypeId);

  await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
    headers: {
      'Authorization': basicAuth(),
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: formData.toString(),
  });
}

test.describe('Search Tokenization Bug Verification', () => {

  test('CONTAINS search should only match exact phrase, not tokenized words', async ({ request }) => {
    // Create test documents
    const docWithKeyword = `search-with-keyword-${Date.now()}.txt`;
    const docWithoutKeyword = `search-without-keyword-${Date.now()}.txt`;
    const docWithPartialMatch = `search-partial-${Date.now()}.txt`;

    let docId1: string | null = null;
    let docId2: string | null = null;
    let docId3: string | null = null;

    try {
      // Document 1: Contains the exact search term
      docId1 = await createDocumentWithContent(
        request,
        docWithKeyword,
        `This document contains ${UNIQUE_SEARCH_TERM} in its content`
      );
      console.log('[TEST] Created doc with keyword:', docId1);

      // Document 2: Does NOT contain the search term
      docId2 = await createDocumentWithContent(
        request,
        docWithoutKeyword,
        'This document has no special keyword at all'
      );
      console.log('[TEST] Created doc without keyword:', docId2);

      // Document 3: Contains only partial match (e.g., "UNIQUE" or "SEARCH")
      // This would incorrectly match if tokenization bug exists
      docId3 = await createDocumentWithContent(
        request,
        docWithPartialMatch,
        'This document contains UNIQUE and SEARCH and VERIFY words separately'
      );
      console.log('[TEST] Created doc with partial match:', docId3);

      // Wait for Solr indexing
      await new Promise(resolve => setTimeout(resolve, 3000));

      // Execute CMIS CONTAINS query
      const query = `SELECT * FROM cmis:document WHERE CONTAINS('${UNIQUE_SEARCH_TERM}')`;
      console.log('[TEST] Executing query:', query);

      const result = await executeCmisQuery(request, query);
      const resultIds = result.results?.map((r: any) =>
        r.properties?.['cmis:objectId']?.value || r.succinctProperties?.['cmis:objectId']
      ) || [];

      console.log('[TEST] Search returned', resultIds.length, 'results');
      console.log('[TEST] Result IDs:', resultIds);

      // CRITICAL ASSERTIONS
      // Only document 1 should be returned (exact match)
      expect(resultIds).toContain(docId1);

      // Document 2 should NOT be returned (no match)
      expect(resultIds).not.toContain(docId2);

      // Document 3 should NOT be returned (partial match should not work after fix)
      expect(resultIds).not.toContain(docId3);

      // Should return exactly 1 result
      expect(resultIds.length).toBe(1);

    } finally {
      // Cleanup
      if (docId1) await deleteDocument(request, docId1).catch(() => {});
      if (docId2) await deleteDocument(request, docId2).catch(() => {});
      if (docId3) await deleteDocument(request, docId3).catch(() => {});
    }
  });

  test('Search should work correctly for documents with Commentable secondary type', async ({ request }) => {
    const docWithCommentable = `search-commentable-${Date.now()}.txt`;
    const docWithCommentableNoMatch = `search-commentable-nomatch-${Date.now()}.txt`;

    let docId1: string | null = null;
    let docId2: string | null = null;

    try {
      // Document 1: With Commentable type AND contains search term
      docId1 = await createDocumentWithContent(
        request,
        docWithCommentable,
        `Document with Commentable type containing ${UNIQUE_SEARCH_TERM}`
      );
      await addSecondaryType(request, docId1, 'nemaki:commentable');
      console.log('[TEST] Created Commentable doc with keyword:', docId1);

      // Document 2: With Commentable type but NO search term
      docId2 = await createDocumentWithContent(
        request,
        docWithCommentableNoMatch,
        'Document with Commentable type but no special keyword'
      );
      await addSecondaryType(request, docId2, 'nemaki:commentable');
      console.log('[TEST] Created Commentable doc without keyword:', docId2);

      // Wait for Solr indexing
      await new Promise(resolve => setTimeout(resolve, 3000));

      // Execute CMIS CONTAINS query
      const query = `SELECT * FROM cmis:document WHERE CONTAINS('${UNIQUE_SEARCH_TERM}')`;
      const result = await executeCmisQuery(request, query);
      const resultIds = result.results?.map((r: any) =>
        r.properties?.['cmis:objectId']?.value || r.succinctProperties?.['cmis:objectId']
      ) || [];

      console.log('[TEST] Search returned', resultIds.length, 'results');

      // Only document with keyword should be returned
      expect(resultIds).toContain(docId1);
      expect(resultIds).not.toContain(docId2);

    } finally {
      if (docId1) await deleteDocument(request, docId1).catch(() => {});
      if (docId2) await deleteDocument(request, docId2).catch(() => {});
    }
  });

  test('Underscore-separated search terms should match exactly', async ({ request }) => {
    // Test specifically for underscore tokenization issue
    const searchTerm = 'TEST_UNDERSCORE_TERM';
    const docWithExact = `exact-underscore-${Date.now()}.txt`;
    const docWithPartial = `partial-underscore-${Date.now()}.txt`;

    let docId1: string | null = null;
    let docId2: string | null = null;

    try {
      // Document 1: Contains exact underscore term
      docId1 = await createDocumentWithContent(
        request,
        docWithExact,
        `This file has ${searchTerm} exactly`
      );

      // Document 2: Contains only "TEST" or "UNDERSCORE" separately
      // This should NOT match after the fix
      docId2 = await createDocumentWithContent(
        request,
        docWithPartial,
        'This file has TEST content and some TERM but not together'
      );

      await new Promise(resolve => setTimeout(resolve, 3000));

      const query = `SELECT * FROM cmis:document WHERE CONTAINS('${searchTerm}')`;
      const result = await executeCmisQuery(request, query);
      const resultIds = result.results?.map((r: any) =>
        r.properties?.['cmis:objectId']?.value || r.succinctProperties?.['cmis:objectId']
      ) || [];

      console.log('[TEST] Underscore search returned', resultIds.length, 'results');

      // Only exact match should be returned
      expect(resultIds).toContain(docId1);
      expect(resultIds).not.toContain(docId2);

    } finally {
      if (docId1) await deleteDocument(request, docId1).catch(() => {});
      if (docId2) await deleteDocument(request, docId2).catch(() => {});
    }
  });
});

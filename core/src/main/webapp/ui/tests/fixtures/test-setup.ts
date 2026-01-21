/**
 * Test Setup Utilities
 *
 * Provides functions to set up test data (folders and files) before tests
 * and clean up after tests.
 */
import * as fs from 'fs';
import * as path from 'path';

const BASE_URL = 'http://localhost:8080/core/browser/bedroom';
const AUTH = 'Basic ' + Buffer.from('admin:admin').toString('base64');

export interface TestFolder {
  id: string;
  name: string;
}

export interface TestFile {
  id: string;
  name: string;
}

/**
 * Create a test folder in the root directory
 */
export async function createTestFolder(folderName: string): Promise<TestFolder> {
  const rootFolderId = 'e02f784f8360a02cc14d1314c10038ff';

  const formData = new FormData();
  formData.append('cmisaction', 'createFolder');
  formData.append('propertyId[0]', 'cmis:objectTypeId');
  formData.append('propertyValue[0]', 'cmis:folder');
  formData.append('propertyId[1]', 'cmis:name');
  formData.append('propertyValue[1]', folderName);
  formData.append('succinct', 'true');

  const response = await fetch(`${BASE_URL}?objectId=${rootFolderId}`, {
    method: 'POST',
    headers: {
      'Authorization': AUTH,
    },
    body: formData,
  });

  if (!response.ok) {
    throw new Error(`Failed to create folder: ${response.status} ${await response.text()}`);
  }

  const data = await response.json();
  return {
    id: data.succinctProperties['cmis:objectId'],
    name: data.succinctProperties['cmis:name'],
  };
}

/**
 * Upload a file to a folder
 */
export async function uploadFile(folderId: string, filePath: string, fileName: string): Promise<TestFile> {
  const fileContent = fs.readFileSync(filePath);
  const mimeType = getMimeType(fileName);

  const formData = new FormData();
  formData.append('cmisaction', 'createDocument');
  formData.append('propertyId[0]', 'cmis:objectTypeId');
  formData.append('propertyValue[0]', 'cmis:document');
  formData.append('propertyId[1]', 'cmis:name');
  formData.append('propertyValue[1]', fileName);
  formData.append('succinct', 'true');

  // Create a Blob from file content
  const blob = new Blob([fileContent], { type: mimeType });
  formData.append('content', blob, fileName);

  const response = await fetch(`${BASE_URL}?objectId=${folderId}`, {
    method: 'POST',
    headers: {
      'Authorization': AUTH,
    },
    body: formData,
  });

  if (!response.ok) {
    throw new Error(`Failed to upload file ${fileName}: ${response.status} ${await response.text()}`);
  }

  const data = await response.json();
  return {
    id: data.succinctProperties['cmis:objectId'],
    name: data.succinctProperties['cmis:name'],
  };
}

/**
 * Delete a folder and all its contents
 */
export async function deleteFolder(folderId: string): Promise<void> {
  const formData = new FormData();
  formData.append('cmisaction', 'deleteTree');
  formData.append('folderId', folderId);
  formData.append('allVersions', 'true');
  formData.append('continueOnFailure', 'true');

  const response = await fetch(BASE_URL, {
    method: 'POST',
    headers: {
      'Authorization': AUTH,
    },
    body: formData,
  });

  if (!response.ok) {
    console.warn(`Warning: Failed to delete folder ${folderId}: ${response.status}`);
  }
}

/**
 * Delete a single object
 */
export async function deleteObject(objectId: string): Promise<void> {
  const formData = new FormData();
  formData.append('cmisaction', 'delete');
  formData.append('objectId', objectId);
  formData.append('allVersions', 'true');

  const response = await fetch(BASE_URL, {
    method: 'POST',
    headers: {
      'Authorization': AUTH,
    },
    body: formData,
  });

  if (!response.ok) {
    console.warn(`Warning: Failed to delete object ${objectId}: ${response.status}`);
  }
}

/**
 * Get MIME type from filename
 */
function getMimeType(fileName: string): string {
  const ext = path.extname(fileName).toLowerCase();
  const mimeTypes: Record<string, string> = {
    '.pdf': 'application/pdf',
    '.txt': 'text/plain',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif': 'image/gif',
    '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    '.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  };
  return mimeTypes[ext] || 'application/octet-stream';
}

/**
 * Set up all test files in a new test folder
 */
export async function setupTestData(): Promise<{ folderId: string; files: TestFile[] }> {
  const fixturesDir = path.join(__dirname);

  // Create test folder with timestamp to avoid conflicts
  const timestamp = Date.now();
  const folderName = `playwright-test-${timestamp}`;
  const folder = await createTestFolder(folderName);

  const files: TestFile[] = [];

  // Upload test files (all available fixtures)
  const testFiles = [
    'テキストサンプル.txt',
    'PDFサンプル.pdf',
    '画像サンプル.png',
    'Excelサンプル.xlsx',
    'PowerPointサンプル.pptx',
    'Wordサンプル.docx',
  ];

  for (const fileName of testFiles) {
    const filePath = path.join(fixturesDir, fileName);
    if (fs.existsSync(filePath)) {
      try {
        const file = await uploadFile(folder.id, filePath, fileName);
        files.push(file);
        console.log(`Uploaded: ${fileName}`);
      } catch (error) {
        console.error(`Failed to upload ${fileName}:`, error);
      }
    } else {
      console.warn(`Fixture file not found: ${filePath}`);
    }
  }

  return { folderId: folder.id, files };
}

/**
 * Clean up test data
 */
export async function cleanupTestData(folderId: string): Promise<void> {
  await deleteFolder(folderId);
  console.log(`Cleaned up test folder: ${folderId}`);
}

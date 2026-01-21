/**
 * Preview Test Setup
 *
 * Provides shared setup/teardown for all preview-related tests.
 * Creates a test folder with all required sample files.
 */
import * as fs from 'fs';
import * as path from 'path';

const BASE_URL = 'http://localhost:8080/core/browser/bedroom';
const AUTH = 'Basic ' + Buffer.from('admin:admin').toString('base64');
const ROOT_FOLDER_ID = 'e02f784f8360a02cc14d1314c10038ff';

export interface TestContext {
  folderId: string;
  folderName: string;
  files: {
    pdf?: string;
    xlsx?: string;
    pptx?: string;
    docx?: string;
    txt?: string;
    png?: string;
  };
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
 * Create test folder and upload all fixtures
 */
export async function setupPreviewTestData(): Promise<TestContext> {
  // Navigate from tests/preview to tests/fixtures
  const fixturesDir = path.join(__dirname, '..', 'fixtures');
  const timestamp = Date.now();
  const folderName = `preview-test-${timestamp}`;

  console.log(`Creating test folder: ${folderName}`);
  console.log(`Fixtures directory: ${fixturesDir}`);

  // Create folder
  const createFolderData = new FormData();
  createFolderData.append('cmisaction', 'createFolder');
  createFolderData.append('propertyId[0]', 'cmis:objectTypeId');
  createFolderData.append('propertyValue[0]', 'cmis:folder');
  createFolderData.append('propertyId[1]', 'cmis:name');
  createFolderData.append('propertyValue[1]', folderName);
  createFolderData.append('succinct', 'true');

  const folderResponse = await fetch(`${BASE_URL}?objectId=${ROOT_FOLDER_ID}`, {
    method: 'POST',
    headers: { 'Authorization': AUTH },
    body: createFolderData,
  });

  if (!folderResponse.ok) {
    throw new Error(`Failed to create folder: ${folderResponse.status} ${await folderResponse.text()}`);
  }

  const folderData = await folderResponse.json();
  const folderId = folderData.succinctProperties['cmis:objectId'];
  console.log(`Created folder: ${folderId}`);

  // Upload files
  const files: TestContext['files'] = {};
  const filesToUpload = [
    { name: 'PDFサンプル.pdf', key: 'pdf' as const },
    { name: 'Excelサンプル.xlsx', key: 'xlsx' as const },
    { name: 'PowerPointサンプル.pptx', key: 'pptx' as const },
    { name: 'Wordサンプル.docx', key: 'docx' as const },
    { name: 'テキストサンプル.txt', key: 'txt' as const },
    { name: '画像サンプル.png', key: 'png' as const },
  ];

  for (const { name, key } of filesToUpload) {
    const filePath = path.join(fixturesDir, name);
    if (!fs.existsSync(filePath)) {
      console.warn(`Fixture file not found: ${filePath}`);
      continue;
    }

    const fileContent = fs.readFileSync(filePath);
    const mimeType = getMimeType(name);

    const uploadData = new FormData();
    uploadData.append('cmisaction', 'createDocument');
    uploadData.append('propertyId[0]', 'cmis:objectTypeId');
    uploadData.append('propertyValue[0]', 'cmis:document');
    uploadData.append('propertyId[1]', 'cmis:name');
    uploadData.append('propertyValue[1]', name);
    uploadData.append('succinct', 'true');

    const blob = new Blob([fileContent], { type: mimeType });
    uploadData.append('content', blob, name);

    const uploadResponse = await fetch(`${BASE_URL}?objectId=${folderId}`, {
      method: 'POST',
      headers: { 'Authorization': AUTH },
      body: uploadData,
    });

    if (uploadResponse.ok) {
      const uploadResult = await uploadResponse.json();
      files[key] = uploadResult.succinctProperties['cmis:objectId'];
      console.log(`Uploaded: ${name} -> ${files[key]}`);
    } else {
      console.error(`Failed to upload ${name}: ${uploadResponse.status}`);
    }
  }

  return { folderId, folderName, files };
}

/**
 * Clean up test folder and all its contents
 */
export async function cleanupPreviewTestData(folderId: string): Promise<void> {
  console.log(`Cleaning up test folder: ${folderId}`);

  const deleteData = new FormData();
  deleteData.append('cmisaction', 'deleteTree');
  deleteData.append('folderId', folderId);
  deleteData.append('allVersions', 'true');
  deleteData.append('continueOnFailure', 'true');

  const response = await fetch(BASE_URL, {
    method: 'POST',
    headers: { 'Authorization': AUTH },
    body: deleteData,
  });

  if (!response.ok) {
    console.warn(`Warning: Failed to delete folder ${folderId}: ${response.status}`);
  } else {
    console.log('Test folder cleaned up successfully');
  }
}

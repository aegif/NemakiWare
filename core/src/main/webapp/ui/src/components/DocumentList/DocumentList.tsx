/**
 * DocumentList Component for NemakiWare React UI
 *
 * Main document management interface providing comprehensive CMIS operations:
 * - Document/Folder browsing with table view and folder tree sidebar
 * - File upload with drag-and-drop support (Ant Design Upload.Dragger)
 * - Folder creation, deletion, and navigation
 * - Document versioning (check-out, check-in, cancel, version history)
 * - Search functionality with CMIS SQL queries
 * - Download, permissions management, and detailed view
 * - Breadcrumb navigation with path tracking
 * - Private Working Copy (PWC) detection and status display
 * - CMISService integration for all repository operations
 * - AuthContext integration for authentication error handling
 *
 * Component Architecture:
 * <Row gutter={16}>
 *   <Col span={6}>
 *     <Card title="フォルダツリー">
 *       <FolderTree onSelect={handleFolderSelect} />
 *     </Card>
 *   </Col>
 *   <Col span={18}>
 *     <Card>
 *       <Breadcrumb /> + Search + Upload/Create buttons
 *       <Table columns={6} dataSource={objects} />
 *     </Card>
 *   </Col>
 * </Row>
 *
 * Modals:
 * - Upload Modal (Form + Upload.Dragger + name input)
 * - Folder Creation Modal (Form + name input)
 * - Check-in Modal (Form + file upload + version type + comment)
 * - Version History Modal (Table with download actions)
 *
 * Usage Examples:
 * ```typescript
 * // App.tsx - Authenticated document management route
 * <Route path="/documents" element={
 *   <ProtectedRoute>
 *     <DocumentList repositoryId={authToken.repositoryId} />
 *   </ProtectedRoute>
 * } />
 *
 * // Document Browsing Flow:
 * // 1. Component mounts → useEffect sets root folder ID (e02f784f8360a02cc14d1314c10038ff)
 * // 2. useEffect triggers loadObjects() when currentFolderId changes
 * // 3. CMISService.getChildren() fetches folder contents
 * // 4. Table displays objects with type icons, names, sizes, dates
 * // 5. User clicks folder name → setCurrentFolderId → loadObjects() → table updates
 *
 * // File Upload Flow:
 * // 1. User clicks "ファイルアップロード" → setUploadModalVisible(true)
 * // 2. Modal opens with Upload.Dragger and name input
 * // 3. User drags file → onChange sets default filename in name field
 * // 4. User clicks "アップロード" → handleUpload extracts file from fileList
 * // 5. CMISService.createDocument() uploads to currentFolderId
 * // 6. await loadObjects() refreshes table → user sees new document
 *
 * // Versioning Flow (Check-out → Check-in):
 * // 1. User clicks document check-out button → handleCheckOut(objectId)
 * // 2. CMISService.checkOut() creates PWC (Private Working Copy)
 * // 3. Table refreshes → document shows "作業中" tag and check-in/cancel buttons
 * // 4. User clicks check-in → modal opens with file upload + version type + comment
 * // 5. User uploads new version → CMISService.checkIn() creates new version
 * // 6. Table refreshes → document shows latest version, "作業中" tag removed
 *
 * // Search Flow:
 * // 1. User types keyword in search input → setSearchQuery
 * // 2. User presses Enter or clicks "検索" → handleSearch()
 * // 3. setIsSearchMode(true) → enables "クリア" button
 * // 4. CMISService.search() executes CMIS SQL: SELECT * FROM cmis:document WHERE cmis:name LIKE '%keyword%'
 * // 5. Table displays search results instead of folder contents
 * // 6. User clicks "クリア" → setIsSearchMode(false) → loadObjects() → back to folder view
 * ```
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. Two-Stage useEffect Initialization (Lines 64-78):
 *    - First useEffect: Sets root folder ID on mount (runs once per repositoryId change)
 *    - Second useEffect: Loads objects when currentFolderId changes (runs on every folder navigation)
 *    - Rationale: Prevents infinite loop - setting currentFolderId in first useEffect would trigger second useEffect
 *    - Implementation: Separate dependencies [repositoryId] vs [currentFolderId]
 *    - Advantage: Clean separation of initialization and navigation logic
 *    - Debug logs: "Initializing with root folder ID" vs "currentFolderId changed, loading objects"
 *
 * 2. FolderTree Integration Pattern (Lines 113-116, 463-471):
 *    - FolderTree callback: handleFolderSelect(folderId, folderPath) updates both ID and path
 *    - State coordination: setCurrentFolderId + setCurrentFolderPath in single handler
 *    - Rationale: Breadcrumb needs path, table needs folderId for getChildren API
 *    - Implementation: Dual state management synchronized by single callback
 *    - Advantage: Folder tree clicks update both table and breadcrumb simultaneously
 *
 * 3. Modal State Management Strategy (Lines 50-53, 520-725):
 *    - Four independent modal states: uploadModalVisible, folderModalVisible, checkInModalVisible, versionHistoryModalVisible
 *    - Rationale: Only one modal can be open at a time, boolean flags simpler than enum
 *    - Implementation: setXxxModalVisible(true/false) controls open prop
 *    - Advantage: Clear intent in code, easy debugging (inspect boolean flags)
 *    - Form.resetFields() on cancel prevents data leakage between modal opens
 *
 * 4. PWC (Private Working Copy) Detection Logic (Lines 304-306, 371-372):
 *    - Checks BOTH cmis:isPrivateWorkingCopy AND cmis:isVersionSeriesCheckedOut properties
 *    - Rationale: Different CMIS implementations may use different properties for PWC status
 *    - Implementation: const isPWC = isPrivateWorkingCopy === true || isVersionSeriesCheckedOut === true
 *    - Advantage: Maximum compatibility across CMIS repositories
 *    - Visual feedback: "作業中" orange tag, different action buttons (check-in/cancel vs check-out)
 *
 * 5. Conditional Action Buttons Display (Lines 393-420):
 *    - Versioning buttons only for documents (baseType === 'cmis:document')
 *    - Check-out button only for non-PWC documents (isVersionable && !isPWC)
 *    - Check-in/Cancel buttons only for PWC documents (isVersionable && isPWC)
 *    - Rationale: Prevent invalid operations (can't check-out a folder, can't check-out PWC twice)
 *    - Implementation: Nested conditional rendering with Tooltip wrappers
 *    - Advantage: Clear user feedback, prevents API errors from invalid operations
 *
 * 6. await loadObjects() Pattern (Lines 142, 156, 170):
 *    - File upload: await createDocument() → await loadObjects()
 *    - Folder creation: await createFolder() → await loadObjects()
 *    - Delete: await deleteObject() → await loadObjects()
 *    - Rationale: Ensure table updates before UI tests proceed (Playwright verification)
 *    - Implementation: Sequential async/await instead of Promise.all
 *    - Advantage: Table always shows latest server state, no stale data
 *    - Comment: "FIXED: Await loadObjects() to ensure table updates before UI tests proceed"
 *
 * 7. Search Mode Toggle (Lines 260-285):
 *    - isSearchMode state controls "クリア" button visibility and table data source
 *    - setIsSearchMode(true) when search executes, setIsSearchMode(false) when cleared
 *    - Rationale: User needs visual indication of search mode and way to return to folder view
 *    - Implementation: Conditional rendering {isSearchMode && <Button onClick={handleClearSearch}>}
 *    - Advantage: Clear UX - user knows they're viewing search results not folder contents
 *
 * 8. Error Logging Strategy:
 *    - console.error() calls for error conditions only
 *    - Debug logs removed (2025-12-03) for production readiness
 *    - Rationale: Clean console output in production, errors still logged for debugging
 *    - Implementation: Errors logged with descriptive context for troubleshooting
 *    - Advantage: Professional production experience without console noise
 *
 * 9. Error Handling and Message Display (Lines 98-110, 143-146, 157-159, etc.):
 *    - Try/catch blocks on all async operations
 *    - message.error() for user-facing error notifications (Ant Design)
 *    - console.error() for developer debugging with detailed context
 *    - Rationale: Graceful degradation - user sees friendly message, developer gets stack trace
 *    - Implementation: catch (error) { message.error(errorMessage); console.error(details); }
 *    - Advantage: Professional UX, doesn't expose technical errors to users
 *
 * 10. Upload.Dragger Auto-Filename Pattern (Lines 542-546):
 *     - onChange callback automatically sets filename in name field when file selected
 *     - Rationale: Reduce user input - most users want original filename
 *     - Implementation: form.setFieldsValue({ name: info.fileList[0].name })
 *     - Advantage: One less manual input, faster workflow
 *     - User can still override: name field is editable Input component
 *
 * Expected Results:
 * - DocumentList: Renders dual-pane layout (folder tree sidebar + document table)
 * - Initial load: Shows root folder contents in table (e.g., Sites, Technical Documents folders)
 * - Folder navigation: Clicking folder updates table, breadcrumb, and folder tree selection
 * - File upload: Drag-drop or click-select → auto-fill filename → upload → table refreshes
 * - Folder creation: Name input → create → table shows new folder
 * - Versioning: Check-out → PWC tag appears → check-in with new file → version increments
 * - Search: Keyword search → table shows matching documents → clear → back to folder view
 * - Actions: Download, delete, permissions, view details all functional per object type
 *
 * Performance Characteristics:
 * - Initial render: <100ms (dual useEffect initialization)
 * - Folder navigation: ~200-500ms (getChildren API + table re-render)
 * - File upload: ~500-2000ms (depends on file size + network)
 * - Search: ~300-1000ms (CMIS SQL query execution time)
 * - Modal open: <50ms (state change + Ant Design animation)
 * - Table render: ~50-200ms (20 items per page with complex action buttons)
 *
 * Debugging Features:
 * - Console logs with "DEBUG" prefix at all state transitions
 * - Folder click logs: name, id, baseType, objectType
 * - PWC debug logs: isPrivateWorkingCopy, isVersionSeriesCheckedOut, all properties
 * - Load objects logs: repository, folder ID, children count, error details
 * - React DevTools: Inspect currentFolderId, currentFolderPath, objects array, modal states
 *
 * Known Limitations:
 * - No infinite scroll (pagination only, 20 items per page)
 * - Search limited to cmis:name field (no advanced filters)
 * - No bulk operations (multi-select delete, bulk download)
 * - No drag-and-drop file organization (can't drag documents between folders)
 * - No column sorting/filtering (Ant Design Table sortable columns not configured)
 * - PWC detection requires both property checks (CMIS spec ambiguity)
 * - Debug logs in production (should be feature-gated)
 * - Hard-coded root folder ID (e02f784f8360a02cc14d1314c10038ff)
 * - Search query not sanitized (SQL injection risk with user input in LIKE clause)
 *
 * Relationships to Other Components:
 * - Used by: App.tsx /documents route (main document management page)
 * - Depends on: FolderTree component (sidebar folder navigation)
 * - Uses: CMISService (all repository operations)
 * - Uses: AuthContext via useAuth hook (handleAuthError for 401 errors)
 * - Navigates to: DocumentViewer (/documents/:objectId), PermissionManagement (/permissions/:objectId)
 * - Ant Design: Table, Modal, Form, Upload.Dragger, Button, Space, Card, Breadcrumb, Tooltip, Popconfirm, Tag, Radio
 *
 * Common Failure Scenarios:
 * - currentFolderId not set: No objects load, console warning "No currentFolderId, skipping load"
 * - CMISService getChildren fails: Table shows empty, error message "オブジェクトの読み込みに失敗しました"
 * - Upload file validation fails: Error message "ファイルが選択されていません"
 * - Check-out on PWC: Button hidden (conditional rendering prevents invalid operation)
 * - Search with empty query: Warning message "検索キーワードを入力してください"
 * - Version history fetch fails: Error message "バージョン履歴の取得に失敗しました"
 * - Delete without confirmation: Popconfirm blocks action until user confirms
 * - Network timeout during loadObjects: Loading spinner shows, then error message after timeout
 */

import React, { useState, useEffect } from 'react';
import {
  Table,
  Button,
  Space,
  Upload,
  Modal,
  Form,
  Input,
  Select,
  message,
  Tooltip,
  Row,
  Col,
  Card,
  Breadcrumb,
  Tag,
  Radio,
  Alert
} from 'antd';
import {
  FileOutlined,
  FolderOutlined,
  UploadOutlined,
  PlusOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  LockOutlined,
  HomeOutlined,
  HistoryOutlined,
  EditOutlined,
  CheckOutlined,
  CloseOutlined,
  UpOutlined,
  FormOutlined,
  ImportOutlined,
  ExportOutlined
} from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { CMISService } from '../../services/cmis';
import { CMISObject, TypeDefinition } from '../../types/cmis';
import { FolderTree } from '../FolderTree/FolderTree';
import { useAuth } from '../../contexts/AuthContext';

interface DocumentListProps {
  repositoryId: string;
}

// Root folder ID constant - extracted to avoid hard-coded values throughout the component
const ROOT_FOLDER_ID = 'e02f784f8360a02cc14d1314c10038ff';

/**
 * Escape special characters in CMIS SQL LIKE clause to prevent SQL injection.
 * CMIS SQL uses single quotes for strings and % for wildcards.
 */
const escapeForCmisSql = (input: string): string => {
  // Escape single quotes by doubling them (CMIS SQL standard)
  // Escape % and _ which are LIKE wildcards
  return input
    .replace(/'/g, "''")
    .replace(/%/g, '\\%')
    .replace(/_/g, '\\_');
};

export const DocumentList: React.FC<DocumentListProps> = ({ repositoryId }) => {
  const { t } = useTranslation();
  const [objects, setObjects] = useState<CMISObject[]>([]);
  const [loading, setLoading] = useState(false);
  // selectedFolderId: The folder whose contents are displayed in the list pane
  // Changes on any folder click in tree
  // Initialize synchronously from URL to show correct folder contents on return from DocumentViewer
  const [selectedFolderId, setSelectedFolderId] = useState<string>(() => {
    const urlParams = new URLSearchParams(window.location.search);
    return urlParams.get('folderId') || '';
  });
  // currentFolderId: The tree pivot point - ancestors are calculated from this folder
  // Only changes when clicking an already-selected folder (second click)
  // CRITICAL FIX (2025-12-29): Initialize from sessionStorage to preserve across page navigation
  // This allows returning from DocumentViewer to the same tree pivot point
  // CRITICAL FIX (2025-12-30): Also check URL for currentFolderId as backup
  const [currentFolderId, setCurrentFolderId] = useState<string>(() => {
    // Priority: 1. URL currentFolderId param (explicit), 2. sessionStorage (persisted), 3. empty
    const urlParams = new URLSearchParams(window.location.search);
    const fromUrl = urlParams.get('currentFolderId');
    if (fromUrl) {
      console.log('[DocumentList] Initial currentFolderId from URL:', fromUrl);
      return fromUrl;
    }
    const saved = sessionStorage.getItem(`nemakiware_currentFolderId_${repositoryId}`);
    console.log('[DocumentList] Initial currentFolderId from sessionStorage:', saved);
    return saved || '';
  });
  // Track if currentFolderId has been explicitly set by user action (not just defaulted to ROOT)
  const [currentFolderIdIsUserSet, setCurrentFolderIdIsUserSet] = useState<boolean>(() => {
    const urlParams = new URLSearchParams(window.location.search);
    const fromUrl = urlParams.get('currentFolderId');
    const saved = sessionStorage.getItem(`nemakiware_currentFolderId_${repositoryId}`);
    return !!(fromUrl || saved);
  });
  const [currentFolderPath, setCurrentFolderPath] = useState<string>('/');

  // CRITICAL FIX (2025-12-29): Persist currentFolderId to sessionStorage when it changes
  // CRITICAL FIX (2025-12-30): Only persist if it's a user-set value (not auto-defaulted to ROOT)
  // This prevents overwriting the saved value with ROOT during initialization
  useEffect(() => {
    if (currentFolderId && repositoryId && currentFolderIdIsUserSet) {
      console.log('[DocumentList] Saving currentFolderId to sessionStorage:', currentFolderId);
      sessionStorage.setItem(`nemakiware_currentFolderId_${repositoryId}`, currentFolderId);
    }
  }, [currentFolderId, repositoryId, currentFolderIdIsUserSet]);

  // CRITICAL FIX (2025-12-30): Rehydrate currentFolderId when repositoryId changes
  // This handles the case where repositoryId was not available during initial render
  useEffect(() => {
    if (repositoryId && !currentFolderId) {
      const saved = sessionStorage.getItem(`nemakiware_currentFolderId_${repositoryId}`);
      if (saved) {
        console.log('[DocumentList] Rehydrating currentFolderId from sessionStorage:', saved);
        setCurrentFolderId(saved);
        setCurrentFolderIdIsUserSet(true);
      }
    }
  }, [repositoryId]);
  const [uploadModalVisible, setUploadModalVisible] = useState(false);
  const [folderModalVisible, setFolderModalVisible] = useState(false);
  const [checkInModalVisible, setCheckInModalVisible] = useState(false);
  const [versionHistoryModalVisible, setVersionHistoryModalVisible] = useState(false);
  const [currentDocumentId, setCurrentDocumentId] = useState<string>('');
  const [versionHistory, setVersionHistory] = useState<CMISObject[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [isSearchMode, setIsSearchMode] = useState(false);

  // Error states for inline Alert components (Error Recovery test fix)
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [folderError, setFolderError] = useState<string | null>(null);

  // Upload progress states (2025-12-26)
  const [isUploading, setIsUploading] = useState(false);

  // Delete confirmation modal states (2025-12-22)
  // For showing cascade deletion warnings when parentChildRelationship descendants exist
  const [deleteModalVisible, setDeleteModalVisible] = useState(false);
  const [deleteTargetId, setDeleteTargetId] = useState<string>('');
  const [deleteTargetName, setDeleteTargetName] = useState<string>('');
  const [deleteDescendantCount, setDeleteDescendantCount] = useState(0);
  const [deleteLoading, setDeleteLoading] = useState(false);

  // Rename modal states (2025-12-26)
  const [renameModalVisible, setRenameModalVisible] = useState(false);
  const [renameTargetId, setRenameTargetId] = useState<string>('');
  const [renameTargetName, setRenameTargetName] = useState<string>('');
  const [renameForm] = Form.useForm();

  // Bulk operation states (2026-01-28)
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [bulkDeleteModalVisible, setBulkDeleteModalVisible] = useState(false);
  const [bulkDeleteLoading, setBulkDeleteLoading] = useState(false);

  // Type selection states (2025-12-11)
  const [documentTypes, setDocumentTypes] = useState<TypeDefinition[]>([]);
  const [folderTypes, setFolderTypes] = useState<TypeDefinition[]>([]);
  const [typesLoading, setTypesLoading] = useState(false);

  // Custom property input states (2025-12-23)
  const [selectedDocumentTypeDefinition, setSelectedDocumentTypeDefinition] = useState<TypeDefinition | null>(null);
  const [selectedFolderTypeDefinition, setSelectedFolderTypeDefinition] = useState<TypeDefinition | null>(null);

  // Import/Export states (2026-01-28)
  const [importModalVisible, setImportModalVisible] = useState(false);
  const [isImporting, setIsImporting] = useState(false);
  const [isExporting, setIsExporting] = useState(false);
  const [importProgress, setImportProgress] = useState(0);
  const [importForm] = Form.useForm();

  const [form] = Form.useForm();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

  // Debug: Log component mount/unmount
  useEffect(() => {
    console.log('[DocumentList] Component MOUNTED, URL:', window.location.href);
    return () => {
      console.log('[DocumentList] Component UNMOUNTED');
    };
  }, []);

  // Initialize folder ID from URL parameter or default to root
  // CRITICAL FIX (2025-12-29): URL only affects selectedFolderId (folder being displayed)
  // currentFolderId (tree pivot point) is preserved in sessionStorage and should NOT be set from URL
  // This allows the tree pivot to be preserved even when viewing different folders
  useEffect(() => {
    const folderIdFromUrl = searchParams.get('folderId');
    console.log('[DocumentList] URL useEffect triggered');
    console.log('[DocumentList] folderIdFromUrl:', folderIdFromUrl);
    console.log('[DocumentList] current selectedFolderId:', selectedFolderId);
    console.log('[DocumentList] current currentFolderId:', currentFolderId);

    if (folderIdFromUrl) {
      // URL determines which folder's contents to display (selectedFolderId)
      // Do NOT change currentFolderId - it should only change when user explicitly clicks an already-selected folder
      if (folderIdFromUrl !== selectedFolderId) {
        console.log('[DocumentList] Setting selectedFolderId to:', folderIdFromUrl);
        setSelectedFolderId(folderIdFromUrl);
      }
      // REMOVED: Do not set currentFolderId from URL
      // currentFolderId is preserved via sessionStorage and user action only
    } else if (!selectedFolderId) {
      // Default to root folder if no URL parameter and no selected folder
      console.log('[DocumentList] No folderId in URL, defaulting to ROOT');
      setSelectedFolderId(ROOT_FOLDER_ID);
      // Only set currentFolderId to ROOT if not already set (from sessionStorage)
      if (!currentFolderId) {
        console.log('[DocumentList] Setting currentFolderId to ROOT (no sessionStorage value)');
        setCurrentFolderId(ROOT_FOLDER_ID);
      }
      setSearchParams({ folderId: ROOT_FOLDER_ID });
    }
  }, [repositoryId, searchParams, setSearchParams]); // Include searchParams to react to URL changes

  // Load available document and folder types for upload/creation dialogs
  useEffect(() => {
    const loadTypes = async () => {
      setTypesLoading(true);
      try {
        const [docTypes, fldTypes] = await Promise.all([
          cmisService.getDocumentTypes(repositoryId),
          cmisService.getFolderTypes(repositoryId)
        ]);
        setDocumentTypes(docTypes);
        setFolderTypes(fldTypes);
      } catch (error) {
        console.error('Failed to load types:', error);
        // Set defaults if type loading fails
        setDocumentTypes([{ id: 'cmis:document', displayName: t('documentList.types.document') } as TypeDefinition]);
        setFolderTypes([{ id: 'cmis:folder', displayName: t('documentList.types.folder') } as TypeDefinition]);
      } finally {
        setTypesLoading(false);
      }
    };
    loadTypes();
  }, [repositoryId]);

  // Handle document type selection change - load type definition for custom properties
  const handleDocumentTypeChange = async (typeId: string) => {
    form.setFieldsValue({ objectTypeId: typeId });
    try {
      const typeDef = await cmisService.getType(repositoryId, typeId);
      setSelectedDocumentTypeDefinition(typeDef);
    } catch (error) {
      console.error('Failed to load document type definition:', error);
      setSelectedDocumentTypeDefinition(null);
    }
  };

  // Handle folder type selection change - load type definition for custom properties
  const handleFolderTypeChange = async (typeId: string) => {
    form.setFieldsValue({ objectTypeId: typeId });
    try {
      const typeDef = await cmisService.getType(repositoryId, typeId);
      setSelectedFolderTypeDefinition(typeDef);
    } catch (error) {
      console.error('Failed to load folder type definition:', error);
      setSelectedFolderTypeDefinition(null);
    }
  };

  // Load objects when selectedFolderId changes (the folder displayed in list pane)
  useEffect(() => {
    if (selectedFolderId) {
      loadObjects();
    }
  }, [selectedFolderId]);

  const loadObjects = async () => {
    // Load objects for the selected folder (the one displayed in list pane)
    if (!selectedFolderId) {
      return;
    }

    setLoading(true);
    try {
      const children = await cmisService.getChildren(repositoryId, selectedFolderId);
      setObjects(children);

      // CRITICAL FIX (2025-11-26): Get selected folder's path from CMIS properties
      // This ensures currentFolderPath is always accurate, not just for root folder
      // Previous bug: Only root folder path was set, causing Up button to be disabled
      // when navigating via table clicks (currentFolderPath stuck at "/")
      const folder = await cmisService.getObject(repositoryId, selectedFolderId);
      const folderPath = folder.path || '/';
      setCurrentFolderPath(folderPath);
    } catch (error) {
      message.error(`${t('documentList.messages.loadObjectsError')}: ${error instanceof Error ? error.message : 'Unknown error'}`);
      // Clear objects on error to show empty state
      setObjects([]);
    } finally {
      setLoading(false);
    }
  };

  // Called when user clicks a folder in the tree (single click)
  // This only changes which folder's contents are displayed, not the tree pivot
  const handleFolderSelect = (folderId: string, folderPath: string) => {
    setSelectedFolderId(folderId);
    setCurrentFolderPath(folderPath);
    // Update URL parameter to enable deep linking
    setSearchParams({ folderId });
  };

  // Called when user clicks an already-selected folder (second click)
  // This changes the tree pivot point and redraws the tree around the clicked folder
  const handleCurrentFolderChange = (folderId: string) => {
    setCurrentFolderId(folderId);
    // CRITICAL FIX (2025-12-30): Mark as user-set so it gets persisted to sessionStorage
    setCurrentFolderIdIsUserSet(true);
    // Also update selected folder to keep them in sync after tree redraw
    setSelectedFolderId(folderId);
    setSearchParams({ folderId });
  };


  const handleUpload = async (values: any) => {
    const { file, name, objectTypeId } = values;

    // Clear previous error
    setUploadError(null);

    try {
      const actualFile = file?.[0]?.originFileObj || file?.[0] || file?.fileList?.[0]?.originFileObj;

      if (!actualFile) {
        const errorMsg = t('documentList.messages.noFileSelected');
        setUploadError(errorMsg);
        message.error(errorMsg);
        return;
      }

      if (!selectedFolderId) {
        const errorMsg = t('documentList.messages.noFolderSelected');
        setUploadError(errorMsg);
        message.error(errorMsg);
        return;
      }

      // Set uploading state (2025-12-26)
      setIsUploading(true);

      // VERSIONING FEATURE (2026-01-08): Check if a document with the same name already exists
      // If it does, update the content stream (creates new version) instead of creating new document
      const existingDocument = await cmisService.findDocumentByNameInFolder(repositoryId, selectedFolderId, name);

      if (existingDocument) {
        // Same name file exists - update content stream to create new version
        console.log(`[DocumentList] Found existing document "${name}" (${existingDocument.id}), creating new version`);
        await cmisService.setContentStream(repositoryId, existingDocument.id, actualFile, true);
        message.success(t('documentList.messages.newVersionCreated', { name }));
      } else {
        // No same name file - create new document
        // Build properties with selected type and custom properties (2025-12-23)
        const properties: Record<string, any> = {
          'cmis:name': name,
          'cmis:objectTypeId': objectTypeId || 'cmis:document'
        };

        // Add custom properties from form values
        for (const [key, value] of Object.entries(values)) {
          if (!key.startsWith('cmis:') && key !== 'file' && key !== 'name' && key !== 'objectTypeId') {
            if (value !== undefined && value !== null && value !== '') {
              properties[key] = value;
            }
          }
        }

        await cmisService.createDocument(repositoryId, selectedFolderId, actualFile, properties);
        message.success(t('documentList.messages.uploadSuccess'));
      }

      setUploadModalVisible(false);
      setUploadError(null);
      setSelectedDocumentTypeDefinition(null);
      form.resetFields();

      // FIXED: Await loadObjects() to ensure table updates before UI tests proceed
      await loadObjects();
    } catch (error) {
      console.error('Upload error:', error);
      const errorMsg = t('documentList.messages.uploadError');
      setUploadError(errorMsg);
      message.error(errorMsg);
    } finally {
      setIsUploading(false);
    }
  };

  const handleCreateFolder = async (values: any) => {
    // Clear previous error
    setFolderError(null);

    try {
      // Build properties with selected type and custom properties (2025-12-23)
      const properties: Record<string, any> = {
        'cmis:name': values.name,
        'cmis:objectTypeId': values.objectTypeId || 'cmis:folder'
      };

      // Add custom properties from form values
      for (const [key, value] of Object.entries(values)) {
        if (!key.startsWith('cmis:') && key !== 'name' && key !== 'objectTypeId') {
          if (value !== undefined && value !== null && value !== '') {
            properties[key] = value;
          }
        }
      }

      await cmisService.createFolder(repositoryId, selectedFolderId, values.name, properties);
      message.success(t('documentList.messages.createFolderSuccess'));
      setFolderModalVisible(false);
      setFolderError(null);
      setSelectedFolderTypeDefinition(null);
      form.resetFields();
      // FIXED: Await loadObjects() to ensure table updates before UI tests proceed
      await loadObjects();
    } catch (error) {
      console.error('Folder creation error:', error);
      const errorMsg = t('documentList.messages.createFolderError');
      setFolderError(errorMsg);
      message.error(errorMsg);
    }
  };

  /**
   * Show delete confirmation modal with cascade deletion info.
   * Checks for parentChildRelationship descendants before deletion.
   */
  const handleDeleteClick = async (objectId: string, objectName: string) => {
    setDeleteTargetId(objectId);
    setDeleteTargetName(objectName);
    setDeleteLoading(true);
    setDeleteModalVisible(true);

    try {
      // Check for parentChildRelationship descendants
      const descendantCount = await cmisService.getParentChildDescendantCount(repositoryId, objectId);
      setDeleteDescendantCount(descendantCount);
    } catch (error) {
      console.error('Error checking descendants:', error);
      setDeleteDescendantCount(0);
    } finally {
      setDeleteLoading(false);
    }
  };

  /**
   * Execute deletion after confirmation.
   * Uses cascade deletion for parentChildRelationship descendants.
   */
  const handleDeleteConfirm = async () => {
    if (!deleteTargetId) return;

    try {
      // Set loading state before starting deletion
      setLoading(true);
      setDeleteModalVisible(false);

      // NemakiWare-specific: Use cascade deletion for parentChildRelationship
      // This will delete all descendant objects linked via nemaki:parentChildRelationship
      const result = await cmisService.deleteObjectWithCascade(repositoryId, deleteTargetId);

      // Reload objects from server after successful deletion
      await loadObjects();

      // Show appropriate success message based on cascade deletion results
      if (result.deletedCount > 1) {
        message.success(t('documentList.messages.deleteSuccessWithCount', { count: result.deletedCount }));
      } else {
        message.success(t('documentList.messages.deleteSuccess'));
      }

      // Warn about any failures during cascade deletion
      if (result.failedIds.length > 0) {
        message.warning(t('documentList.messages.deletePartialFail', { count: result.failedIds.length }));
      }
    } catch (error) {
      message.error(t('documentList.messages.deleteError'));
    } finally {
      setLoading(false);
      setDeleteTargetId('');
      setDeleteTargetName('');
      setDeleteDescendantCount(0);
    }
  };

  const handleDeleteCancel = () => {
    setDeleteModalVisible(false);
    setDeleteTargetId('');
    setDeleteTargetName('');
    setDeleteDescendantCount(0);
  };

  /**
   * Show rename modal with current name prefilled.
   */
  const handleRenameClick = (objectId: string, objectName: string) => {
    setRenameTargetId(objectId);
    setRenameTargetName(objectName);
    renameForm.setFieldsValue({ newName: objectName });
    setRenameModalVisible(true);
  };

  /**
   * Execute rename operation using CMIS updateProperties.
   */
  const handleRename = async (values: { newName: string }) => {
    if (!renameTargetId || !values.newName.trim()) return;

    try {
      setLoading(true);
      await cmisService.updateProperties(repositoryId, renameTargetId, {
        'cmis:name': values.newName.trim()
      });
      message.success(t('documentList.messages.renameSuccess'));
      setRenameModalVisible(false);
      renameForm.resetFields();
      await loadObjects();
    } catch (error) {
      console.error('Rename error:', error);
      message.error(t('documentList.messages.renameError'));
    } finally {
      setLoading(false);
      setRenameTargetId('');
      setRenameTargetName('');
    }
  };

  const handleRenameCancel = () => {
    setRenameModalVisible(false);
    renameForm.resetFields();
    setRenameTargetId('');
    setRenameTargetName('');
  };

  /**
   * Handle bulk delete operation.
   * Deletes all selected items with cascade deletion for related objects.
   * Uses deleteObjectWithCascade return value to accurately count successes and failures.
   * Reports partial failures when descendant deletions fail.
   */
  const handleBulkDelete = async () => {
    setBulkDeleteLoading(true);
    try {
      let successCount = 0;
      let failedCount = 0;
      let partialFailureCount = 0;

      for (const objectId of selectedRowKeys) {
        try {
          const objectIdStr = String(objectId);
          const result = await cmisService.deleteObjectWithCascade(repositoryId, objectIdStr);
          // Count the root object as success only if it was actually deleted (not in failedIds)
          if (!result.failedIds.includes(objectIdStr)) {
            successCount++;
            // Check if there were descendant failures (partial success)
            if (result.failedIds.length > 0) {
              partialFailureCount++;
              console.warn(`Cascade delete for ${objectIdStr} succeeded but ${result.failedIds.length} descendant(s) failed:`, result.failedIds);
            }
          } else {
            failedCount++;
          }
        } catch (error) {
          console.error(`Failed to delete object ${objectId}:`, error);
          failedCount++;
        }
      }

      if (successCount > 0) {
        message.success(t('documentList.messages.bulkDeleteSuccess', { count: successCount }));
      }
      if (partialFailureCount > 0) {
        message.warning(t('documentList.messages.bulkDeletePartial', { count: partialFailureCount }));
      }
      if (failedCount > 0) {
        message.warning(t('documentList.messages.bulkDeleteFailed', { count: failedCount }));
      }

      setSelectedRowKeys([]);
      setBulkDeleteModalVisible(false);
      await loadObjects();
    } catch (error) {
      console.error('Bulk delete error:', error);
      message.error(t('documentList.messages.bulkDeleteError'));
    } finally {
      setBulkDeleteLoading(false);
    }
  };

  const handleDownload = (objectId: string) => {
    const url = cmisService.getDownloadUrl(repositoryId, objectId);
    window.open(url, '_blank');
  };

  const handleCheckOut = async (objectId: string) => {
    try {
      setLoading(true);
      await cmisService.checkOut(repositoryId, objectId);
      message.success(t('documentList.messages.checkoutSuccess'));
      await loadObjects();
    } catch (error) {
      console.error('Check-out error:', error);
      message.error(t('documentList.messages.checkoutError'));
    } finally {
      setLoading(false);
    }
  };

  const handleCheckInClick = (objectId: string) => {
    setCurrentDocumentId(objectId);
    setCheckInModalVisible(true);
  };

  const handleCheckIn = async (values: any) => {
    const { file, versionType, comment } = values;

    try {
      setLoading(true);
      const actualFile = file?.[0]?.originFileObj || file?.[0] || file?.fileList?.[0]?.originFileObj;

      await cmisService.checkIn(
        repositoryId,
        currentDocumentId,
        actualFile,
        {
          major: versionType === 'major',
          checkinComment: comment || ''
        }
      );

      message.success(t('documentList.messages.checkinSuccess'));
      setCheckInModalVisible(false);
      form.resetFields();
      await loadObjects();
    } catch (error) {
      console.error('Check-in error:', error);
      message.error(t('documentList.messages.checkinError'));
    } finally {
      setLoading(false);
    }
  };

  const handleCancelCheckOut = async (objectId: string) => {
    try {
      setLoading(true);
      await cmisService.cancelCheckOut(repositoryId, objectId);
      message.success(t('documentList.messages.cancelCheckoutSuccess'));
      await loadObjects();
    } catch (error) {
      console.error('Cancel check-out error:', error);
      message.error(t('documentList.messages.cancelCheckoutError'));
    } finally {
      setLoading(false);
    }
  };

  const handleViewVersionHistory = async (objectId: string) => {
    try {
      setLoading(true);
      const history = await cmisService.getVersionHistory(repositoryId, objectId);
      setVersionHistory(history.versions);
      setVersionHistoryModalVisible(true);
    } catch (error) {
      console.error('Version history error:', error);
      message.error(t('documentList.messages.versionHistoryError'));
    } finally {
      setLoading(false);
    }
  };

  const handleSearch = async () => {
    if (!searchQuery.trim()) {
      message.warning(t('documentList.messages.enterSearchKeyword'));
      return;
    }

    setIsSearchMode(true);
    setLoading(true);

    try {
      // SECURITY FIX: Escape user input to prevent SQL injection
      const escapedQuery = escapeForCmisSql(searchQuery);
      const query = `SELECT * FROM cmis:document WHERE cmis:name LIKE '%${escapedQuery}%'`;
      const searchResult = await cmisService.search(repositoryId, query);
      setObjects(searchResult.objects);
    } catch (error) {
      console.error('Search error:', error);
      message.error(`${t('documentList.messages.searchError')}: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setLoading(false);
    }
  };

  const handleClearSearch = () => {
    setSearchQuery('');
    setIsSearchMode(false);
    loadObjects();
  };

  // Import handler (2026-01-28)
  const handleImport = async (values: { file: { originFileObj?: File; name?: string }[] }) => {
    if (!values.file || values.file.length === 0) {
      message.error(t('importExport.noFileSelected'));
      return;
    }

    const fileItem = values.file[0];
    const file = fileItem.originFileObj;
    if (!file || !(file instanceof File)) {
      message.error(t('importExport.noFileSelected'));
      return;
    }

    // Validate file type
    const fileName = file.name.toLowerCase();
    if (!fileName.endsWith('.acp') && !fileName.endsWith('.zip')) {
      message.error(t('importExport.invalidFileType'));
      return;
    }

    setIsImporting(true);
    setImportProgress(0);

    try {
      const result = await cmisService.importContent(
        repositoryId,
        selectedFolderId || ROOT_FOLDER_ID,
        file as File,
        (progress) => setImportProgress(progress)
      );

      if (result.status === 'success') {
        message.success(
          `${t('importExport.importSuccess')} - ${t('importExport.foldersCreated', { count: result.foldersCreated })}, ${t('importExport.documentsCreated', { count: result.documentsCreated })}`
        );
      } else if (result.status === 'partial') {
        message.warning(t('importExport.partialSuccess'));
        if (result.warnings && result.warnings.length > 0) {
          console.warn('Import warnings:', result.warnings);
        }
        if (result.errors && result.errors.length > 0) {
          console.error('Import errors:', result.errors);
        }
      }

      setImportModalVisible(false);
      importForm.resetFields();
      await loadObjects();
    } catch (error) {
      console.error('Import error:', error);
      message.error(`${t('importExport.importError')}: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setIsImporting(false);
      setImportProgress(0);
    }
  };

  // Export handler (2026-01-28)
  const handleExport = async () => {
    if (!selectedFolderId) {
      message.warning(t('documentList.messages.selectFolder'));
      return;
    }

    setIsExporting(true);

    try {
      const blob = await cmisService.exportContent(
        repositoryId,
        selectedFolderId
      );

      // Create download link
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `export_${selectedFolderId}_${Date.now()}.zip`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(url);

      message.success(t('importExport.exportSuccess'));
    } catch (error) {
      console.error('Export error:', error);
      message.error(`${t('importExport.exportError')}: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setIsExporting(false);
    }
  };

  const columns = [
    {
      title: t('documentList.columns.type'),
      dataIndex: 'baseType',
      key: 'type',
      width: 60,
      render: (baseType: string) => (
        baseType === 'cmis:folder' ? 
          <FolderOutlined style={{ color: '#1890ff', fontSize: '16px' }} /> :
          <FileOutlined style={{ color: '#52c41a', fontSize: '16px' }} />
      ),
    },
    {
      title: t('documentList.columns.name'),
      dataIndex: 'name',
      key: 'name',
      render: (name: string, record: CMISObject) => {
        // Handle both boolean and string values (AtomPub returns strings, Browser binding returns booleans)
        const isPrivateWorkingCopy = record.properties?.['cmis:isPrivateWorkingCopy'];
        const isVersionSeriesCheckedOut = record.properties?.['cmis:isVersionSeriesCheckedOut'];
        const isPWC = isPrivateWorkingCopy === true || isPrivateWorkingCopy === 'true' ||
                      isVersionSeriesCheckedOut === true || isVersionSeriesCheckedOut === 'true';

        return (
          <Space>
            <Button
              type="link"
              onClick={() => {
                if (record.baseType === 'cmis:folder') {
                  // CRITICAL FIX (2025-12-03): Only update selectedFolderId, NOT currentFolderId
                  // Previous bug: Every table folder click changed currentFolderId, causing tree to redraw
                  // User expectation: Table clicks should only change which folder is displayed (selectedFolderId)
                  // The tree pivot (currentFolderId) should only change when user explicitly requests it
                  // (by clicking an already-selected folder in the tree)
                  setSelectedFolderId(record.id);
                  // DO NOT: setCurrentFolderId(record.id); - this would redraw the tree
                  setSearchParams({ folderId: record.id });
                  // Path will be set by loadObjects() after fetching from CMIS - no manual construction
                } else {
                  // CRITICAL FIX (2025-12-16): Use selectedFolderId with URL fallback for back button navigation
                  // selectedFolderId = the folder whose contents are currently displayed
                  // currentFolderId = the tree pivot point (may differ from selected folder)
                  // When user clicks back from document detail, they should return to selectedFolderId
                  // FALLBACK: If selectedFolderId is empty (race condition), use URL param or ROOT_FOLDER_ID
                  const effectiveFolderId = selectedFolderId || searchParams.get('folderId') || ROOT_FOLDER_ID;
                  // CRITICAL FIX (2025-12-30): Also pass currentFolderId in URL as backup for sessionStorage
                  // This ensures the tree pivot point is preserved even if sessionStorage fails
                  const effectiveCurrentFolderId = currentFolderId || ROOT_FOLDER_ID;
                  const folderParam = `?folderId=${effectiveFolderId}&currentFolderId=${effectiveCurrentFolderId}`;
                  const targetUrl = `/documents/${record.id}${folderParam}`;
                  navigate(targetUrl);
                }
              }}
            >
              {name}
            </Button>
            {isPWC && (
              <Tag color="orange">{t('documentList.tags.working')}</Tag>
            )}
          </Space>
        );
      },
    },
    // Search mode columns: objectType, path, createdBy, creationDate
    // FEATURE: Added 2025-12-25 per user request for search result metadata
    ...(isSearchMode ? [
      {
        title: t('documentList.columns.objectType'),
        dataIndex: 'objectType',
        key: 'objectType',
        width: 150,
        render: (objectType: string) => {
          // Display friendly name for common types
          const typeLabels: Record<string, string> = {
            'cmis:document': t('documentList.types.document'),
            'cmis:folder': t('documentList.types.folder'),
          };
          return typeLabels[objectType] || objectType || '-';
        },
      },
      {
        title: t('documentList.columns.path'),
        dataIndex: 'path',
        key: 'path',
        width: 200,
        render: (path: string) => {
          if (!path || path === '/') {
            return '/';
          }

          const segments = path.split('/').filter(Boolean);
          return (
            <span>
              <span
                style={{ color: '#1890ff', cursor: 'pointer', textDecoration: 'underline' }}
                onClick={async (e) => {
                  e.stopPropagation();
                  try {
                    setSelectedFolderId(ROOT_FOLDER_ID);
                    setCurrentFolderId(ROOT_FOLDER_ID);
                    setCurrentFolderPath('/');
                    setSearchParams({ folderId: ROOT_FOLDER_ID });
                    setIsSearchMode(false);
                    setSearchQuery('');
                  } catch (error) {
                    console.error('Navigation error:', error);
                    message.error(t('common.errors.navigationFailed'));
                  }
                }}
              >
                /
              </span>
              {segments.map((segment, index) => {
                const segmentPath = '/' + segments.slice(0, index + 1).join('/');
                const isLast = index === segments.length - 1;

                return (
                  <span key={index}>
                    <span
                      style={{
                        color: isLast ? 'inherit' : '#1890ff',
                        cursor: isLast ? 'default' : 'pointer',
                        textDecoration: isLast ? 'none' : 'underline'
                      }}
                      onClick={async (e) => {
                        e.stopPropagation();
                        if (!isLast) {
                          try {
                            const folderObject = await cmisService.getObjectByPath(repositoryId, segmentPath);
                            if (folderObject && folderObject.id) {
                              // Note: Path segment navigation updates both selected and current folder IDs
                              setSelectedFolderId(folderObject.id);
                              setCurrentFolderId(folderObject.id);
                              setCurrentFolderPath(segmentPath);
                              setSearchParams({ folderId: folderObject.id });
                              setIsSearchMode(false);
                              setSearchQuery('');
                            }
                          } catch (error) {
                            console.error('Path navigation error:', error);
                            message.error(t('documentList.messages.folderNavigationError'));
                          }
                        }
                      }}
                    >
                      {segment}
                    </span>
                    {!isLast && <span>/</span>}
                  </span>
                );
              })}
            </span>
          );
        },
      },
      {
        title: t('documentList.columns.createdBy'),
        dataIndex: 'createdBy',
        key: 'createdBy',
        width: 100,
        render: (createdBy: string) => createdBy || '-',
      },
      {
        title: t('documentList.columns.creationDate'),
        dataIndex: 'creationDate',
        key: 'creationDate',
        width: 150,
        render: (date: string) => date ? new Date(date).toLocaleString('ja-JP') : '-',
      },
    ] : []),
    {
      title: t('documentList.columns.size'),
      dataIndex: 'contentStreamLength',
      key: 'size',
      width: 100,
      render: (size: number) => {
        // CMIS returns -1 for unknown/no content, null/undefined for folders
        if (size === null || size === undefined || size < 0) return '-';
        if (size === 0) return '0 B';
        if (size < 1024) return `${size} B`;
        if (size < 1024 * 1024) return `${Math.round(size / 1024)} KB`;
        return `${(size / (1024 * 1024)).toFixed(1)} MB`;
      },
    },
    {
      title: t('documentList.columns.modifiedDate'),
      dataIndex: 'lastModificationDate',
      key: 'modified',
      width: 180,
      render: (date: string) => date ? new Date(date).toLocaleString('ja-JP') : '-',
    },
    {
      title: t('documentList.columns.modifiedBy'),
      dataIndex: 'lastModifiedBy',
      key: 'modifiedBy',
      width: 120,
    },
    {
      title: t('common.actions'),
      key: 'actions',
      width: 300,
      render: (_: any, record: CMISObject) => {
        // Handle both boolean and string values (AtomPub returns strings, Browser binding returns booleans)
        const isPrivateWorkingCopy = record.properties?.['cmis:isPrivateWorkingCopy'];
        const isVersionSeriesCheckedOut = record.properties?.['cmis:isVersionSeriesCheckedOut'];
        const isPWC = isPrivateWorkingCopy === true || isPrivateWorkingCopy === 'true' ||
                      isVersionSeriesCheckedOut === true || isVersionSeriesCheckedOut === 'true';
        const isVersionable = record.baseType === 'cmis:document';

        return (
          <Space>
            <Tooltip title={t('documentList.actions.viewDetails')}>
              <Button
                icon={<EyeOutlined />}
                size="small"
                onClick={() => {
                  // CRITICAL FIX (2025-12-16): Use selectedFolderId with URL fallback for back button navigation
                  const effectiveFolderId = selectedFolderId || searchParams.get('folderId') || ROOT_FOLDER_ID;
                  const folderParam = `?folderId=${effectiveFolderId}`;
                  const targetUrl = `/documents/${record.id}${folderParam}`;
                  navigate(targetUrl);
                }}
              />
            </Tooltip>
            <Tooltip title={t('documentList.actions.rename')}>
              <Button
                icon={<FormOutlined />}
                size="small"
                onClick={() => handleRenameClick(record.id, record.name)}
              />
            </Tooltip>
            {record.baseType === 'cmis:document' && (
              <Tooltip title={t('common.download')}>
                <Button
                  icon={<DownloadOutlined />}
                  size="small"
                  onClick={() => handleDownload(record.id)}
                />
              </Tooltip>
            )}
            {isVersionable && !isPWC && (
              <Tooltip title={t('documentList.actions.checkout')}>
                <Button
                  icon={<EditOutlined />}
                  size="small"
                  onClick={() => handleCheckOut(record.id)}
                />
              </Tooltip>
            )}
            {isVersionable && isPWC && (
              <>
                <Tooltip title={t('documentList.actions.checkin')}>
                  <Button
                    icon={<CheckOutlined />}
                    size="small"
                    type="primary"
                    onClick={() => handleCheckInClick(record.id)}
                  />
                </Tooltip>
                <Tooltip title={t('documentList.actions.cancelCheckout')}>
                  <Button
                    icon={<CloseOutlined />}
                    size="small"
                    onClick={() => handleCancelCheckOut(record.id)}
                  />
                </Tooltip>
              </>
            )}
            {isVersionable && (
              <Tooltip title={t('documentList.actions.versionHistory')}>
                <Button
                  icon={<HistoryOutlined />}
                  size="small"
                  onClick={() => handleViewVersionHistory(record.id)}
                />
              </Tooltip>
            )}
            <Tooltip title={t('documentList.actions.permissionManagement')}>
              <Button
                icon={<LockOutlined />}
                size="small"
                onClick={() => {
                  // CRITICAL FIX (2025-12-23): Preserve folderId when navigating to PermissionManagement
                  const effectiveFolderId = selectedFolderId || searchParams.get('folderId') || ROOT_FOLDER_ID;
                  navigate(`/permissions/${record.id}?folderId=${effectiveFolderId}`);
                }}
              >
                {t('documentList.actions.permissionManagement')}
              </Button>
            </Tooltip>
            <Tooltip title={t('common.delete')}>
              <Button
                icon={<DeleteOutlined />}
                size="small"
                danger
                onClick={() => handleDeleteClick(record.id, record.name)}
              />
            </Tooltip>
          </Space>
        );
      },
    },
  ];

  // Handle breadcrumb click - navigate to clicked folder segment
  const handleBreadcrumbClick = async (index: number) => {
    try {
      // Reconstruct path up to clicked segment
      const pathSegments = currentFolderPath.split('/').filter(Boolean);

      if (index === 0) {
        // Click on home icon - navigate to root
        // Note: Breadcrumb navigation updates both selected and current folder IDs
        setSelectedFolderId(ROOT_FOLDER_ID);
        setCurrentFolderId(ROOT_FOLDER_ID);
        setCurrentFolderPath('/');
        setSearchParams({ folderId: ROOT_FOLDER_ID });
      } else {
        // Click on folder segment - resolve path to folder ID
        const targetPath = '/' + pathSegments.slice(0, index).join('/');

        // Use CMIS getObjectByPath to resolve folder ID
        const folderObject = await cmisService.getObjectByPath(repositoryId, targetPath);
        if (folderObject && folderObject.id) {
          // Note: Breadcrumb navigation updates both selected and current folder IDs
          setSelectedFolderId(folderObject.id);
          setCurrentFolderId(folderObject.id);
          setCurrentFolderPath(targetPath);
          setSearchParams({ folderId: folderObject.id });
        }
      }
    } catch (error) {
      console.error('Breadcrumb navigation error:', error);
      message.error(t('documentList.messages.folderNavigationError'));
    }
  };

  // Handle "Up to Parent Folder" button click
  // Feature Request (2025-11-26): "親フォルダへのナビゲーションがないと元にもどっていけません"
  const handleGoToParent = async () => {
    try {
      const pathSegments = currentFolderPath.split('/').filter(Boolean);

      if (pathSegments.length === 0) {
        // Already in root, nothing to do
        return;
      }

      if (pathSegments.length === 1) {
        // Parent is root folder
        const rootFolderId = 'e02f784f8360a02cc14d1314c10038ff';
        // Note: Parent navigation updates both selected and current folder IDs
        setSelectedFolderId(rootFolderId);
        setCurrentFolderId(rootFolderId);
        setCurrentFolderPath('/');
        setSearchParams({ folderId: rootFolderId });
      } else {
        // Parent is another subfolder - navigate up one level
        const parentPath = '/' + pathSegments.slice(0, -1).join('/');
        const parentObject = await cmisService.getObjectByPath(repositoryId, parentPath);
        if (parentObject && parentObject.id) {
          // Note: Parent navigation updates both selected and current folder IDs
          setSelectedFolderId(parentObject.id);
          setCurrentFolderId(parentObject.id);
          setCurrentFolderPath(parentPath);
          setSearchParams({ folderId: parentObject.id });
        }
      }
    } catch (error) {
      console.error('Navigate to parent error:', error);
      message.error(t('documentList.messages.parentNavigationError'));
    }
  };

  // Always show root item first, then folder segments
  const breadcrumbItems = [
    {
      title: <HomeOutlined />,
      onClick: () => handleBreadcrumbClick(0),
      className: 'breadcrumb-clickable',
    },
    ...currentFolderPath.split('/').filter(Boolean).map((segment, index) => ({
      title: segment,
      onClick: () => handleBreadcrumbClick(index + 1),
      className: 'breadcrumb-clickable',
    }))
  ];

  // Check if we're in root folder (disable Up button if so)
  const isInRootFolder = currentFolderPath === '/' || currentFolderPath.split('/').filter(Boolean).length === 0;

  return (
    <div>
      <Row gutter={16}>
        <Col span={6}>
          <Card title={t('documentList.folderTree')} size="small">
            <FolderTree
              repositoryId={repositoryId}
              onSelect={handleFolderSelect}
              onCurrentFolderChange={handleCurrentFolderChange}
              selectedFolderId={selectedFolderId}
              currentFolderId={currentFolderId}
            />
          </Card>
        </Col>
        <Col span={18}>
          <Card>
            <Space direction="vertical" style={{ width: '100%' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <Space>
                  <Button
                    icon={<UpOutlined />}
                    onClick={handleGoToParent}
                    disabled={isInRootFolder}
                    title={t('documentList.goToParent')}
                  >
                    {t('documentList.up')}
                  </Button>
                  <Breadcrumb items={breadcrumbItems} />
                </Space>
                <Space>
                  <Input
                    placeholder={t('documentList.searchPlaceholder')}
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    onPressEnter={handleSearch}
                    style={{ width: 200 }}
                    className="search-input"
                  />
                  <Button onClick={handleSearch} className="search-button">{t('common.search')}</Button>
                  {isSearchMode && (
                    <Button onClick={handleClearSearch}>{t('common.clear')}</Button>
                  )}
                  <Button
                    type="primary"
                    icon={<UploadOutlined />}
                    onClick={() => setUploadModalVisible(true)}
                  >
                    {t('documentList.uploadFile')}
                  </Button>
                  <Button
                    icon={<PlusOutlined />}
                    onClick={() => setFolderModalVisible(true)}
                  >
                    {t('documentList.createFolder')}
                  </Button>
                  <Button
                    icon={<ImportOutlined />}
                    onClick={() => setImportModalVisible(true)}
                  >
                    {t('importExport.import')}
                  </Button>
                  <Button
                    icon={<ExportOutlined />}
                    onClick={handleExport}
                    loading={isExporting}
                  >
                    {t('importExport.export')}
                  </Button>
                  {selectedRowKeys.length > 0 && (
                    <Button
                      danger
                      icon={<DeleteOutlined />}
                      onClick={() => setBulkDeleteModalVisible(true)}
                    >
                      {t('documentList.bulkDelete', { count: selectedRowKeys.length })}
                    </Button>
                  )}
                </Space>
              </div>
              
              <Table
                columns={columns}
                dataSource={objects}
                rowKey="id"
                loading={loading}
                pagination={{ pageSize: 20 }}
                size="small"
                rowSelection={{
                  selectedRowKeys,
                  onChange: (newSelectedRowKeys: React.Key[]) => {
                    setSelectedRowKeys(newSelectedRowKeys);
                  },
                }}
              />
            </Space>
          </Card>
        </Col>
      </Row>

      <Modal
        title={isUploading ? t('documentList.uploadingFile') : t('documentList.uploadFile')}
        open={uploadModalVisible}
        onCancel={() => {
          if (isUploading) return; // Prevent closing during upload
          setUploadModalVisible(false);
          setUploadError(null);
          setSelectedDocumentTypeDefinition(null);
          form.resetFields();
        }}
        footer={null}
        maskClosable={false}
        closable={!isUploading}
        width={700}
      >
        <Form form={form} onFinish={handleUpload} layout="vertical">
          {uploadError && (
            <Alert
              message={uploadError}
              type="error"
              closable
              onClose={() => setUploadError(null)}
              style={{ marginBottom: 16 }}
              className="upload-error-alert"
            />
          )}
          <Form.Item
            name="file"
            label={t('documentList.file')}
            rules={[{ required: true, message: t('documentList.validation.selectFile') }]}
            valuePropName="fileList"
            getValueFromEvent={(e) => {
              if (Array.isArray(e)) {
                return e;
              }
              return e?.fileList;
            }}
          >
            <Upload.Dragger
              beforeUpload={() => false}
              maxCount={1}
              onChange={(info) => {
                if (info.fileList.length > 0 && info.fileList[0].name) {
                  form.setFieldsValue({ name: info.fileList[0].name });
                }
              }}
            >
              <p className="ant-upload-drag-icon">
                <UploadOutlined />
              </p>
              <p className="ant-upload-text">{t('documentList.dragDropOrClick')}</p>
            </Upload.Dragger>
          </Form.Item>
          <Form.Item
            name="name"
            label={t('documentList.fileName')}
            rules={[{ required: true, message: t('documentList.validation.enterFileName') }]}
          >
            <Input placeholder={t('documentList.placeholders.enterFileName')} />
          </Form.Item>
          <Form.Item
            name="objectTypeId"
            label={t('common.type')}
            initialValue="cmis:document"
          >
            <Select
              loading={typesLoading}
              placeholder={t('documentList.placeholders.selectDocumentType')}
              showSearch
              optionFilterProp="label"
              onChange={handleDocumentTypeChange}
            >
              {documentTypes.map(type => (
                <Select.Option key={type.id} value={type.id} label={type.displayName || type.id}>
                  {type.displayName || type.id}
                  {type.description && (
                    <span style={{ color: '#888', marginLeft: 8, fontSize: '12px' }}>
                      - {type.description}
                    </span>
                  )}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>

          {/* Custom Properties Section for Document Upload (2025-12-23) */}
          {selectedDocumentTypeDefinition &&
           Object.entries(selectedDocumentTypeDefinition.propertyDefinitions || {})
             .filter(([propId]) => !propId.startsWith('cmis:')).length > 0 && (
            <div style={{ marginBottom: 16, padding: 16, backgroundColor: '#fafafa', borderRadius: 8 }}>
              <h4 style={{ marginTop: 0, marginBottom: 12 }}>{t('documentList.customProperties')}</h4>
              {Object.entries(selectedDocumentTypeDefinition.propertyDefinitions || {})
                .filter(([propId]) => !propId.startsWith('cmis:'))
                .map(([propId, propDef]: [string, any]) => (
                  <Form.Item
                    key={propId}
                    name={propId}
                    label={
                      <span>
                        {propDef.displayName || propId}
                        {propDef.required && <span style={{ color: 'red', marginLeft: 4 }}>*</span>}
                      </span>
                    }
                    tooltip={propDef.description}
                    rules={propDef.required ? [{ required: true, message: t('documentList.validation.enterProperty', { name: propDef.displayName || propId }) }] : []}
                  >
                    {propDef.propertyType === 'boolean' ? (
                      <Select placeholder={t('common.placeholders.select', { field: '' })} allowClear>
                        <Select.Option value="true">{t('common.yes')}</Select.Option>
                        <Select.Option value="false">{t('common.no')}</Select.Option>
                      </Select>
                    ) : propDef.propertyType === 'integer' || propDef.propertyType === 'decimal' ? (
                      <Input type="number" placeholder={propDef.description || t('documentList.placeholders.enterProperty', { name: propDef.displayName || propId })} />
                    ) : propDef.propertyType === 'datetime' ? (
                      <Input type="datetime-local" />
                    ) : (
                      <Input placeholder={propDef.description || t('documentList.placeholders.enterProperty', { name: propDef.displayName || propId })} />
                    )}
                  </Form.Item>
                ))}
            </div>
          )}

          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                loading={isUploading}
                disabled={isUploading}
              >
                {isUploading ? t('common.uploading') : t('common.upload')}
              </Button>
              <Button
                onClick={() => {
                  setUploadModalVisible(false);
                  setUploadError(null);
                  setSelectedDocumentTypeDefinition(null);
                  form.resetFields();
                }}
                disabled={isUploading}
              >
                {t('common.cancel')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('documentList.createFolder')}
        open={folderModalVisible}
        onCancel={() => {
          setFolderModalVisible(false);
          setFolderError(null);
          setSelectedFolderTypeDefinition(null);
          form.resetFields();
        }}
        footer={null}
        maskClosable={false}
        width={700}
      >
        <Form form={form} onFinish={handleCreateFolder} layout="vertical">
          {folderError && (  // Inline Alert component
            <Alert
              message={folderError}
              type="error"
              closable
              onClose={() => setFolderError(null)}
              style={{ marginBottom: 16 }}
              className="folder-error-alert"
            />
          )}
          <Form.Item
            name="name"
            label={t('documentList.folderName')}
            rules={[{ required: true, message: t('documentList.validation.enterFolderName') }]}
          >
            <Input placeholder={t('documentList.placeholders.enterFolderName')} />
          </Form.Item>
          <Form.Item
            name="objectTypeId"
            label={t('common.type')}
            initialValue="cmis:folder"
          >
            <Select
              loading={typesLoading}
              placeholder={t('documentList.placeholders.selectFolderType')}
              showSearch
              optionFilterProp="label"
              onChange={handleFolderTypeChange}
            >
              {folderTypes.map(type => (
                <Select.Option key={type.id} value={type.id} label={type.displayName || type.id}>
                  {type.displayName || type.id}
                  {type.description && (
                    <span style={{ color: '#888', marginLeft: 8, fontSize: '12px' }}>
                      - {type.description}
                    </span>
                  )}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>

          {/* Custom Properties Section for Folder Creation (2025-12-23) */}
          {selectedFolderTypeDefinition &&
           Object.entries(selectedFolderTypeDefinition.propertyDefinitions || {})
             .filter(([propId]) => !propId.startsWith('cmis:')).length > 0 && (
            <div style={{ marginBottom: 16, padding: 16, backgroundColor: '#fafafa', borderRadius: 8 }}>
              <h4 style={{ marginTop: 0, marginBottom: 12 }}>{t('documentList.customProperties')}</h4>
              {Object.entries(selectedFolderTypeDefinition.propertyDefinitions || {})
                .filter(([propId]) => !propId.startsWith('cmis:'))
                .map(([propId, propDef]: [string, any]) => (
                  <Form.Item
                    key={propId}
                    name={propId}
                    label={
                      <span>
                        {propDef.displayName || propId}
                        {propDef.required && <span style={{ color: 'red', marginLeft: 4 }}>*</span>}
                      </span>
                    }
                    tooltip={propDef.description}
                    rules={propDef.required ? [{ required: true, message: t('documentList.validation.enterProperty', { name: propDef.displayName || propId }) }] : []}
                  >
                    {propDef.propertyType === 'boolean' ? (
                      <Select placeholder={t('common.placeholders.select', { field: '' })} allowClear>
                        <Select.Option value="true">{t('common.yes')}</Select.Option>
                        <Select.Option value="false">{t('common.no')}</Select.Option>
                      </Select>
                    ) : propDef.propertyType === 'integer' || propDef.propertyType === 'decimal' ? (
                      <Input type="number" placeholder={propDef.description || t('documentList.placeholders.enterProperty', { name: propDef.displayName || propId })} />
                    ) : propDef.propertyType === 'datetime' ? (
                      <Input type="datetime-local" />
                    ) : (
                      <Input placeholder={propDef.description || t('documentList.placeholders.enterProperty', { name: propDef.displayName || propId })} />
                    )}
                  </Form.Item>
                ))}
            </div>
          )}

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                {t('common.create')}
              </Button>
              <Button onClick={() => {
                setFolderModalVisible(false);
                setSelectedFolderTypeDefinition(null);
                form.resetFields();
              }}>
                {t('common.cancel')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('documentList.checkin')}
        open={checkInModalVisible}
        onCancel={() => {
          setCheckInModalVisible(false);
          form.resetFields();
        }}
        footer={null}
        width={600}
        maskClosable={false}
      >
        <Form form={form} onFinish={handleCheckIn} layout="vertical" initialValues={{ versionType: 'minor' }}>
          <Form.Item
            name="file"
            label={t('documentList.checkinFileLabel')}
            valuePropName="fileList"
            getValueFromEvent={(e) => {
              if (Array.isArray(e)) {
                return e;
              }
              return e?.fileList;
            }}
          >
            <Upload.Dragger
              beforeUpload={() => false}
              maxCount={1}
            >
              <p className="ant-upload-drag-icon">
                <UploadOutlined />
              </p>
              <p className="ant-upload-text">{t('documentList.dragDropOrClick')}</p>
              <p className="ant-upload-hint">{t('documentList.checkinFileHint')}</p>
            </Upload.Dragger>
          </Form.Item>
          <Form.Item
            name="versionType"
            label={t('documentList.versionType')}
            rules={[{ required: true, message: t('documentList.validation.selectVersionType') }]}
          >
            <Radio.Group>
              <Radio value="minor">{t('documentList.minorVersion')}</Radio>
              <Radio value="major">{t('documentList.majorVersion')}</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item
            name="comment"
            label={t('documentList.checkinComment')}
          >
            <Input.TextArea
              rows={4}
              placeholder={t('documentList.placeholders.enterCheckinComment')}
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                {t('documentList.checkin')}
              </Button>
              <Button onClick={() => {
                setCheckInModalVisible(false);
                form.resetFields();
              }}>
                {t('common.cancel')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('documentList.versionHistory')}
        open={versionHistoryModalVisible}
        onCancel={() => setVersionHistoryModalVisible(false)}
        footer={null}
        width={800}
        maskClosable={false}
      >
        <Table
          dataSource={versionHistory}
          rowKey="id"
          pagination={false}
          size="small"
          columns={[
            {
              title: t('documentList.columns.version'),
              dataIndex: 'versionLabel',
              key: 'version',
              width: 100,
            },
            {
              title: t('documentList.columns.modifiedDate'),
              dataIndex: 'lastModificationDate',
              key: 'date',
              width: 180,
              render: (date: string) => date ? new Date(date).toLocaleString('ja-JP') : '-',
            },
            {
              title: t('documentList.columns.modifiedBy'),
              dataIndex: 'lastModifiedBy',
              key: 'author',
              width: 120,
            },
            {
              title: t('documentList.columns.comment'),
              key: 'comment',
              render: (record: CMISObject) => record.properties?.['cmis:checkinComment'] || '-',
            },
            {
              title: t('common.actions'),
              key: 'actions',
              width: 100,
              render: (_: any, record: CMISObject) => (
                <Tooltip title={t('common.download')}>
                  <Button
                    icon={<DownloadOutlined />}
                    size="small"
                    onClick={() => handleDownload(record.id)}
                  />
                </Tooltip>
              ),
            },
          ]}
        />
      </Modal>

      {/* Delete Confirmation Modal with cascade deletion info */}
      <Modal
        title={t('documentList.deleteConfirmation')}
        open={deleteModalVisible}
        onOk={handleDeleteConfirm}
        onCancel={handleDeleteCancel}
        okText={t('documentList.deleteButton')}
        cancelText={t('common.cancel')}
        okButtonProps={{ danger: true, loading: loading }}
        maskClosable={false}
      >
        {deleteLoading ? (
          <div style={{ textAlign: 'center', padding: '20px' }}>
            {t('documentList.checkingRelatedObjects')}
          </div>
        ) : (
          <div>
            <p>
              {t('documentList.deleteConfirmMessage', { name: deleteTargetName })}
            </p>
            {deleteDescendantCount > 0 && (
              <div style={{
                backgroundColor: '#fff7e6',
                border: '1px solid #ffd591',
                borderRadius: '4px',
                padding: '12px',
                marginTop: '12px'
              }}>
                <p style={{ margin: 0, color: '#d46b08' }}>
                  <strong>{t('documentList.deleteWarningTitle')}</strong> {t('documentList.deleteWarningMessage', { count: deleteDescendantCount })}
                </p>
                <p style={{ margin: '8px 0 0 0', color: '#d46b08' }}>
                  {t('documentList.deleteWarningCascade')}
                </p>
              </div>
            )}
          </div>
        )}
      </Modal>

      {/* Rename Modal */}
      <Modal
        title={t('documentList.renameTitle', { name: renameTargetName })}
        open={renameModalVisible}
        onOk={() => renameForm.submit()}
        onCancel={handleRenameCancel}
        okText={t('documentList.changeButton')}
        cancelText={t('common.cancel')}
        okButtonProps={{ loading: loading }}
        maskClosable={false}
      >
        <Form
          form={renameForm}
          layout="vertical"
          onFinish={handleRename}
        >
          <Form.Item
            name="newName"
            label={t('documentList.newName')}
            rules={[
              { required: true, message: t('documentList.validation.enterName') },
              { min: 1, message: t('documentList.validation.enterName') },
              { max: 255, message: t('documentList.validation.nameMaxLength') }
            ]}
          >
            <Input placeholder={t('documentList.placeholders.enterNewName')} autoFocus />
          </Form.Item>
        </Form>
      </Modal>

      {/* Import Modal (2026-01-28) */}
      <Modal
        title={isImporting ? t('importExport.importing') : t('importExport.importTitle')}
        open={importModalVisible}
        onCancel={() => {
          if (isImporting) return;
          setImportModalVisible(false);
          importForm.resetFields();
        }}
        footer={null}
        maskClosable={false}
        closable={!isImporting}
        width={600}
      >
        <Form form={importForm} onFinish={handleImport} layout="vertical">
          <p style={{ marginBottom: 16, color: '#666' }}>
            {t('importExport.importDescription')}
          </p>
          <Form.Item
            name="file"
            label={t('importExport.selectFile')}
            rules={[{ required: true, message: t('importExport.noFileSelected') }]}
            valuePropName="fileList"
            getValueFromEvent={(e) => {
              if (Array.isArray(e)) {
                return e;
              }
              return e?.fileList;
            }}
          >
            <Upload.Dragger
              beforeUpload={() => false}
              maxCount={1}
              accept=".acp,.zip"
              disabled={isImporting}
            >
              <p className="ant-upload-drag-icon">
                <ImportOutlined />
              </p>
              <p className="ant-upload-text">{t('importExport.dragDropHint')}</p>
              <p className="ant-upload-hint">{t('importExport.supportedFormats')}</p>
            </Upload.Dragger>
          </Form.Item>

          {isImporting && (
            <div style={{ marginBottom: 16 }}>
              <p>{t('importExport.importInProgress')}</p>
              <div style={{ 
                width: '100%', 
                height: 8, 
                backgroundColor: '#f0f0f0', 
                borderRadius: 4,
                overflow: 'hidden'
              }}>
                <div style={{ 
                  width: `${importProgress}%`, 
                  height: '100%', 
                  backgroundColor: '#1890ff',
                  transition: 'width 0.3s ease'
                }} />
              </div>
              <p style={{ textAlign: 'center', marginTop: 8 }}>
                {t('importExport.progress', { progress: importProgress })}
              </p>
            </div>
          )}

          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                loading={isImporting}
                disabled={isImporting}
              >
                {isImporting ? t('importExport.importing') : t('importExport.import')}
              </Button>
              <Button
                onClick={() => {
                  setImportModalVisible(false);
                  importForm.resetFields();
                }}
                disabled={isImporting}
              >
                {t('common.cancel')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      {/* Bulk Delete Confirmation Modal */}
      <Modal
        title={t('documentList.bulkDeleteConfirmation')}
        open={bulkDeleteModalVisible}
        onOk={handleBulkDelete}
        onCancel={() => {
          setBulkDeleteModalVisible(false);
        }}
        okText={t('documentList.deleteButton')}
        cancelText={t('common.cancel')}
        okButtonProps={{ danger: true, loading: bulkDeleteLoading }}
        maskClosable={false}
      >
        <p>
          {t('documentList.bulkDeleteConfirmMessage', { count: selectedRowKeys.length })}
        </p>
      </Modal>
    </div>
  );
};

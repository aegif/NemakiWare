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
 * 8. Debug Logging Strategy (Lines 67, 75, 86, 90, 94, 100, 307, 323):
 *    - Extensive console.log calls with "DEBUG" prefixes
 *    - Logs folder navigation, API calls, PWC status, error details
 *    - Rationale: Complex state management benefits from detailed runtime logging
 *    - Implementation: Descriptive messages with context objects (repositoryId, currentFolderId, etc.)
 *    - Advantage: Easy debugging in production, clear audit trail of user actions
 *    - Note: Production builds should remove or gate behind feature flag
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

import React, { useState, useEffect, useMemo } from 'react';
import {
  Table,
  Button,
  Space,
  Upload,
  Modal,
  Form,
  Input,
  message,
  Popconfirm,
  Tooltip,
  Row,
  Col,
  Card,
  Breadcrumb,
  Tag,
  Radio,
  DatePicker,
  Select
} from 'antd';

const { RangePicker } = DatePicker;
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
  CloseOutlined
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { CMISService } from '../../services/cmis';
import { CMISObject } from '../../types/cmis';
import { FolderTree } from '../FolderTree/FolderTree';
import { useAuth } from '../../contexts/AuthContext';

interface DocumentListProps {
  repositoryId: string;
}

export const DocumentList: React.FC<DocumentListProps> = ({ repositoryId }) => {
  const [objects, setObjects] = useState<CMISObject[]>([]);
  const [loading, setLoading] = useState(false);
  const [currentFolderId, setCurrentFolderId] = useState<string>('');
  const [currentFolderPath, setCurrentFolderPath] = useState<string>('/');
  const [uploadModalVisible, setUploadModalVisible] = useState(false);
  const [folderModalVisible, setFolderModalVisible] = useState(false);
  const [checkInModalVisible, setCheckInModalVisible] = useState(false);
  const [versionHistoryModalVisible, setVersionHistoryModalVisible] = useState(false);
  const [currentDocumentId, setCurrentDocumentId] = useState<string>('');
  const [versionHistory, setVersionHistory] = useState<CMISObject[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [isSearchMode, setIsSearchMode] = useState(false);

  // ENHANCEMENT (2025-11-19): Advanced search filter states
  const [dateRange, setDateRange] = useState<[any, any] | null>(null);
  const [fileType, setFileType] = useState<string>('');
  const [creator, setCreator] = useState<string>('');

  const [form] = Form.useForm();
  const navigate = useNavigate();
  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

  // Initialize root folder ID immediately
  useEffect(() => {
    if (!currentFolderId) {
      setCurrentFolderId('e02f784f8360a02cc14d1314c10038ff');
    }
  }, [repositoryId]); // Only depend on repositoryId

  // Load objects when currentFolderId changes
  useEffect(() => {
    if (currentFolderId) {
      loadObjects();
    }
  }, [currentFolderId]);

  const loadObjects = async () => {
    if (!currentFolderId) {
      // No folder selected - skip load
      return;
    }

    setLoading(true);
    try {
      const children = await cmisService.getChildren(repositoryId, currentFolderId);
      setObjects(children);

      // Update folder path for root folder
      if (currentFolderId === 'e02f784f8360a02cc14d1314c10038ff') {
        setCurrentFolderPath('/');
      }
    } catch (error) {
      // Failed to load children
      message.error(`オブジェクトの読み込みに失敗しました: ${error instanceof Error ? error.message : 'Unknown error'}`);
      // Clear objects on error to show empty state
      setObjects([]);
    } finally {
      setLoading(false);
    }
  };

  const handleFolderSelect = (folderId: string, folderPath: string) => {
    setCurrentFolderId(folderId);
    setCurrentFolderPath(folderPath);
  };


  const handleUpload = async (values: any) => {
    const { file, name } = values;

    try {
      const actualFile = file?.[0]?.originFileObj || file?.[0] || file?.fileList?.[0]?.originFileObj;

      if (!actualFile) {
        message.error('ファイルが選択されていません');
        return;
      }

      if (!currentFolderId) {
        message.error('アップロード先フォルダが選択されていません');
        return;
      }

      await cmisService.createDocument(repositoryId, currentFolderId, actualFile, { 'cmis:name': name });

      message.success('ファイルをアップロードしました');
      setUploadModalVisible(false);
      form.resetFields();

      // FIXED: Await loadObjects() to ensure table updates before UI tests proceed
      await loadObjects();
    } catch (error) {
      // Upload failed
      message.error('ファイルのアップロードに失敗しました');
    }
  };

  const handleCreateFolder = async (values: any) => {
    try {
      await cmisService.createFolder(repositoryId, currentFolderId, values.name);
      message.success('フォルダを作成しました');
      setFolderModalVisible(false);
      form.resetFields();
      // FIXED: Await loadObjects() to ensure table updates before UI tests proceed
      await loadObjects();
    } catch (error) {
      message.error('フォルダの作成に失敗しました');
    }
  };

  const handleDelete = async (objectId: string) => {
    try {
      // Set loading state before starting deletion
      setLoading(true);

      await cmisService.deleteObject(repositoryId, objectId);

      // Reload objects from server after successful deletion
      await loadObjects();

      message.success('削除しました');
    } catch (error) {
      message.error('削除に失敗しました');
      setLoading(false);
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
      message.success('チェックアウトしました');
      await loadObjects();
    } catch (error) {
      // Check-out failed
      message.error('チェックアウトに失敗しました');
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

      message.success('チェックインしました');
      setCheckInModalVisible(false);
      form.resetFields();
      await loadObjects();
    } catch (error) {
      // Check-in failed
      message.error('チェックインに失敗しました');
    } finally {
      setLoading(false);
    }
  };

  const handleCancelCheckOut = async (objectId: string) => {
    try {
      setLoading(true);
      await cmisService.cancelCheckOut(repositoryId, objectId);
      message.success('チェックアウトをキャンセルしました');
      await loadObjects();
    } catch (error) {
      // Cancel check-out failed
      message.error('チェックアウトのキャンセルに失敗しました');
    } finally {
      setLoading(false);
    }
  };

  const handleViewVersionHistory = async (objectId: string) => {
    try {
      setLoading(true);
      const history = await cmisService.getVersionHistory(repositoryId, objectId);
      setVersionHistory(history);
      setVersionHistoryModalVisible(true);
    } catch (error) {
      // Version history failed
      message.error('バージョン履歴の取得に失敗しました');
    } finally {
      setLoading(false);
    }
  };

  /**
   * Sanitize search query to prevent SQL injection attacks.
   * CMIS SQL uses single quotes for string literals, so we need to escape them.
   * Also remove potentially dangerous SQL keywords and characters.
   */
  const sanitizeSearchQuery = (query: string): string => {
    // Escape single quotes by doubling them (CMIS SQL standard)
    let sanitized = query.replace(/'/g, "''");

    // Remove potentially dangerous SQL characters
    sanitized = sanitized
      .replace(/;/g, '')      // Remove semicolons (statement terminators)
      .replace(/--/g, '')     // Remove SQL line comments
      .replace(/\/\*/g, '')   // Remove multi-line comment start
      .replace(/\*\//g, '');  // Remove multi-line comment end

    // Limit length to prevent DoS via extremely long queries
    const MAX_QUERY_LENGTH = 100;
    if (sanitized.length > MAX_QUERY_LENGTH) {
      sanitized = sanitized.substring(0, MAX_QUERY_LENGTH);
    }

    return sanitized;
  };

  const handleSearch = async () => {
    if (!searchQuery.trim() && !dateRange && !fileType && !creator) {
      message.warning('検索キーワードまたはフィルター条件を入力してください');
      return;
    }

    setIsSearchMode(true);
    setLoading(true);

    // PERFORMANCE ENHANCEMENT (2025-11-19): Show loading feedback to user
    const loadingMessage = message.loading('検索を実行中...', 0);

    try {
      // ENHANCEMENT (2025-11-19): Build advanced search query with multiple filter conditions
      // Base query with name search (if provided)
      const conditions: string[] = [];

      // SECURITY FIX (2025-11-19): Sanitize user input before building SQL query
      if (searchQuery.trim()) {
        const sanitizedQuery = sanitizeSearchQuery(searchQuery.trim());
        conditions.push(`cmis:name LIKE '%${sanitizedQuery}%'`);
      }

      // Date range filter (creationDate)
      if (dateRange && dateRange[0] && dateRange[1]) {
        const startDate = dateRange[0].format('YYYY-MM-DD');
        const endDate = dateRange[1].format('YYYY-MM-DD');
        conditions.push(`cmis:creationDate >= TIMESTAMP '${startDate}T00:00:00.000Z'`);
        conditions.push(`cmis:creationDate <= TIMESTAMP '${endDate}T23:59:59.999Z'`);
      }

      // File type filter (contentStreamMimeType)
      if (fileType) {
        const sanitizedMimeType = sanitizeSearchQuery(fileType);
        conditions.push(`cmis:contentStreamMimeType = '${sanitizedMimeType}'`);
      }

      // Creator filter (createdBy)
      if (creator.trim()) {
        const sanitizedCreator = sanitizeSearchQuery(creator.trim());
        conditions.push(`cmis:createdBy LIKE '%${sanitizedCreator}%'`);
      }

      // Build final query with all conditions
      const whereClause = conditions.length > 0 ? conditions.join(' AND ') : '1=1';
      const query = `SELECT * FROM cmis:document WHERE ${whereClause}`;

      console.log('Advanced search query:', query);

      // PERFORMANCE ENHANCEMENT (2025-11-19): Measure search execution time
      const startTime = Date.now();
      const searchResult = await cmisService.search(repositoryId, query);
      const searchTime = Date.now() - startTime;

      // PERFORMANCE ENHANCEMENT (2025-11-19): Update loading message with progress
      loadingMessage();
      const pathMessage = message.loading(`${searchResult.objects.length}件のドキュメントが見つかりました。パス情報を取得中...`, 0);

      // CRITICAL FIX (2025-11-19): Calculate paths for search results
      // Documents don't have cmis:path property, so we compute from parent folder
      console.log('[handleSearch] Starting path calculation for search results:', searchResult.objects);

      const objectsWithPaths = await Promise.all(
        searchResult.objects.map(async (obj, index) => {
          // DEBUG: Log every object before condition check
          console.log(`[handleSearch] Processing object ${index}:`, {
            name: obj.name,
            baseType: obj.baseType,
            path: obj.path,
            pathType: typeof obj.path,
            pathValue: JSON.stringify(obj.path),
            condition1: obj.baseType === 'cmis:document',
            condition2: !obj.path,
            bothConditions: obj.baseType === 'cmis:document' && !obj.path
          });

          // Only calculate path for documents without path
          if (obj.baseType === 'cmis:document' && !obj.path) {
            console.log(`[handleSearch] Calculating path for document ${obj.name} (${obj.id})`);
            try {
              const parents = await cmisService.getObjectParents(repositoryId, obj.id);
              console.log(`[handleSearch] Got ${parents.length} parents for ${obj.name}:`, parents);

              if (parents.length > 0 && parents[0].path) {
                const parentPath = parents[0].path;
                const documentName = obj.name;
                obj.path = parentPath === '/' ? `/${documentName}` : `${parentPath}/${documentName}`;
                console.log(`[handleSearch] Set path for ${obj.name}: ${obj.path}`);
              } else {
                console.warn(`[handleSearch] No parent path found for ${obj.name}`);
              }
            } catch (parentError) {
              // Failed to get parent path - not critical, leave path undefined
              console.warn(`Failed to calculate path for ${obj.name}:`, parentError);
            }
          } else {
            console.log(`[handleSearch] Skipping path calculation for ${obj.name} (baseType: ${obj.baseType}, path exists: ${!!obj.path})`);
          }
          return obj;
        })
      );

      console.log('[handleSearch] Path calculation complete, final objects:', objectsWithPaths);

      pathMessage();
      setObjects(objectsWithPaths);

      // PERFORMANCE ENHANCEMENT (2025-11-19): Show success message with metrics
      const resultCount = objectsWithPaths.length;
      const totalTime = Date.now() - startTime;
      message.success(`検索完了: ${resultCount}件のドキュメントを検索しました (${(totalTime / 1000).toFixed(1)}秒)`, 3);

    } catch (error) {
      // PERFORMANCE ENHANCEMENT (2025-11-19): Enhanced error handling with context
      console.error('Search error details:', error);

      // Check for specific error types
      if (error instanceof Error) {
        if (error.message.includes('timeout') || error.message.includes('ETIMEDOUT')) {
          message.error('検索がタイムアウトしました。条件を絞り込んで再度お試しください。', 5);
        } else if (error.message.includes('network') || error.message.includes('ECONNREFUSED')) {
          message.error('ネットワークエラー: サーバーに接続できませんでした。', 5);
        } else if (error.message.includes('401') || error.message.includes('unauthorized')) {
          message.error('認証エラー: 再度ログインしてください。', 5);
        } else {
          message.error(`検索エラー: ${error.message}`, 5);
        }
      } else {
        message.error('検索に失敗しました。しばらくしてから再度お試しください。', 5);
      }

      // Clear search mode on error
      setIsSearchMode(false);

    } finally {
      // PERFORMANCE ENHANCEMENT (2025-11-19): Always clear loading message and state
      loadingMessage();
      setLoading(false);
    }
  };

  const handleClearSearch = () => {
    // Clear all search and filter states
    setSearchQuery('');
    setDateRange(null);
    setFileType('');
    setCreator('');
    setIsSearchMode(false);
    loadObjects();
  };

  // CRITICAL FIX (2025-11-19): Search results require different columns than folder browsing
  // Search mode should show: objectType, path, createdBy, creationDate for better document discovery
  // Folder browsing should show: size, lastModifiedBy, lastModificationDate for file management
  // useMemo ensures columns array recalculates when isSearchMode changes
  //
  // ENHANCEMENT (2025-11-19): Added sortable columns for improved search result usability
  // All text columns support alphabetical sorting, date columns support chronological sorting
  const columns = useMemo(() => [
    {
      title: 'タイプ',
      dataIndex: 'baseType',
      key: 'type',
      width: 60,
      sorter: (a: CMISObject, b: CMISObject) => {
        // Sort folders before documents
        if (a.baseType === b.baseType) return 0;
        return a.baseType === 'cmis:folder' ? -1 : 1;
      },
      render: (baseType: string) => (
        baseType === 'cmis:folder' ?
          <FolderOutlined style={{ color: '#1890ff', fontSize: '16px' }} /> :
          <FileOutlined style={{ color: '#52c41a', fontSize: '16px' }} />
      ),
    },
    {
      title: '名前',
      dataIndex: 'name',
      key: 'name',
      sorter: (a: CMISObject, b: CMISObject) => (a.name || '').localeCompare(b.name || '', 'ja'),
      render: (name: string, record: CMISObject) => {
        const isPWC = record.properties?.['cmis:isPrivateWorkingCopy'] === true ||
                      record.properties?.['cmis:isVersionSeriesCheckedOut'] === true;

        return (
          <Space>
            <Button
              type="link"
              onClick={() => {
                if (record.baseType === 'cmis:folder') {
                  setCurrentFolderId(record.id);
                } else {
                  navigate(`/documents/${record.id}`);
                }
              }}
            >
              {name}
            </Button>
            {isPWC && (
              <Tag color="orange">作業中</Tag>
            )}
          </Space>
        );
      },
    },
    // Conditionally include search-specific columns
    ...(isSearchMode ? [
      {
        title: 'オブジェクトタイプ',
        dataIndex: 'objectType',
        key: 'objectType',
        width: 150,
        sorter: (a: CMISObject, b: CMISObject) => (a.objectType || '').localeCompare(b.objectType || ''),
        render: (objectType: string) => objectType || '-',
      },
      {
        title: 'パス',
        dataIndex: 'path',
        key: 'path',
        width: 250,
        sorter: (a: CMISObject, b: CMISObject) => (a.path || '').localeCompare(b.path || ''),
        render: (path: string | undefined) => path || '（計算中...）',
      },
      {
        title: '作成者',
        dataIndex: 'createdBy',
        key: 'createdBy',
        width: 120,
        sorter: (a: CMISObject, b: CMISObject) => (a.createdBy || '').localeCompare(b.createdBy || '', 'ja'),
      },
      {
        title: '作成日時',
        dataIndex: 'creationDate',
        key: 'creationDate',
        width: 180,
        sorter: (a: CMISObject, b: CMISObject) => {
          const dateA = a.creationDate ? new Date(a.creationDate).getTime() : 0;
          const dateB = b.creationDate ? new Date(b.creationDate).getTime() : 0;
          return dateA - dateB;
        },
        render: (date: string) => date ? new Date(date).toLocaleString('ja-JP') : '-',
      },
    ] : [
      {
        title: 'サイズ',
        dataIndex: 'contentStreamLength',
        key: 'size',
        width: 100,
        sorter: (a: CMISObject, b: CMISObject) => {
          const sizeA = a.contentStreamLength || 0;
          const sizeB = b.contentStreamLength || 0;
          return sizeA - sizeB;
        },
        render: (size: number) => {
          if (!size) return '-';
          if (size < 1024) return `${size} B`;
          return `${Math.round(size / 1024)} KB`;
        },
      },
      {
        title: '更新日時',
        dataIndex: 'lastModificationDate',
        key: 'modified',
        width: 180,
        sorter: (a: CMISObject, b: CMISObject) => {
          const dateA = a.lastModificationDate ? new Date(a.lastModificationDate).getTime() : 0;
          const dateB = b.lastModificationDate ? new Date(b.lastModificationDate).getTime() : 0;
          return dateA - dateB;
        },
        render: (date: string) => date ? new Date(date).toLocaleString('ja-JP') : '-',
      },
      {
        title: '更新者',
        dataIndex: 'lastModifiedBy',
        key: 'modifiedBy',
        width: 120,
        sorter: (a: CMISObject, b: CMISObject) => (a.lastModifiedBy || '').localeCompare(b.lastModifiedBy || '', 'ja'),
      },
    ]),
    {
      title: 'アクション',
      key: 'actions',
      width: 300,
      render: (_: any, record: CMISObject) => {
        const isPWC = record.properties?.['cmis:isPrivateWorkingCopy'] === true ||
                      record.properties?.['cmis:isVersionSeriesCheckedOut'] === true;
        const isVersionable = record.baseType === 'cmis:document';

        return (
          <Space>
            <Tooltip title="詳細表示">
              <Button
                icon={<EyeOutlined />}
                size="small"
                onClick={() => navigate(`/documents/${record.id}`)}
              />
            </Tooltip>
            {record.baseType === 'cmis:document' && (
              <Tooltip title="ダウンロード">
                <Button
                  icon={<DownloadOutlined />}
                  size="small"
                  onClick={() => handleDownload(record.id)}
                />
              </Tooltip>
            )}
            {isVersionable && !isPWC && (
              <Tooltip title="チェックアウト">
                <Button
                  icon={<EditOutlined />}
                  size="small"
                  onClick={() => handleCheckOut(record.id)}
                />
              </Tooltip>
            )}
            {isVersionable && isPWC && (
              <>
                <Tooltip title="チェックイン">
                  <Button
                    icon={<CheckOutlined />}
                    size="small"
                    type="primary"
                    onClick={() => handleCheckInClick(record.id)}
                  />
                </Tooltip>
                <Tooltip title="チェックアウトキャンセル">
                  <Button
                    icon={<CloseOutlined />}
                    size="small"
                    onClick={() => handleCancelCheckOut(record.id)}
                  />
                </Tooltip>
              </>
            )}
            {isVersionable && (
              <Tooltip title="バージョン履歴">
                <Button
                  icon={<HistoryOutlined />}
                  size="small"
                  onClick={() => handleViewVersionHistory(record.id)}
                />
              </Tooltip>
            )}
            <Tooltip title="権限管理">
              <Button
                icon={<LockOutlined />}
                size="small"
                onClick={() => navigate(`/permissions/${record.id}`)}
              >
                権限管理
              </Button>
            </Tooltip>
            <Popconfirm
              title="削除しますか？"
              onConfirm={() => handleDelete(record.id)}
              okText="はい"
              cancelText="いいえ"
            >
              <Tooltip title="削除">
                <Button
                  icon={<DeleteOutlined />}
                  size="small"
                  danger
                />
              </Tooltip>
            </Popconfirm>
          </Space>
        );
      },
    },
  ], [isSearchMode]);

  const breadcrumbItems = currentFolderPath.split('/').filter(Boolean).map((segment, index) => ({
    title: index === 0 ? <HomeOutlined /> : segment,
  }));

  return (
    <div>
      <Row gutter={16}>
        <Col span={6}>
          <Card title="フォルダツリー" size="small">
            <FolderTree
              repositoryId={repositoryId}
              onSelect={handleFolderSelect}
              selectedFolderId={currentFolderId}
            />
          </Card>
        </Col>
        <Col span={18}>
          <Card>
            <Space direction="vertical" style={{ width: '100%' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <Breadcrumb items={breadcrumbItems} />
                <Space>
                  <Input
                    placeholder="ドキュメント検索"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    onPressEnter={handleSearch}
                    style={{ width: 200 }}
                    className="search-input"
                  />
                  <Button onClick={handleSearch} className="search-button">検索</Button>
                  {isSearchMode && (
                    <Button onClick={handleClearSearch}>クリア</Button>
                  )}
                  <Button
                    type="primary"
                    icon={<UploadOutlined />}
                    onClick={() => setUploadModalVisible(true)}
                  >
                    ファイルアップロード
                  </Button>
                  <Button
                    icon={<PlusOutlined />}
                    onClick={() => setFolderModalVisible(true)}
                  >
                    フォルダ作成
                  </Button>
                </Space>
              </div>

              {/* ENHANCEMENT (2025-11-19): Advanced search filters */}
              <div style={{ marginTop: 16, padding: '12px', backgroundColor: '#f5f5f5', borderRadius: '4px' }}>
                <Space wrap>
                  <RangePicker
                    placeholder={['作成日時（開始）', '作成日時（終了）']}
                    value={dateRange}
                    onChange={(dates) => setDateRange(dates)}
                    style={{ width: 280 }}
                  />
                  <Select
                    placeholder="ファイルタイプ"
                    value={fileType || undefined}
                    onChange={(value) => setFileType(value || '')}
                    allowClear
                    style={{ width: 200 }}
                    options={[
                      { label: 'すべて', value: '' },
                      { label: 'PDF', value: 'application/pdf' },
                      { label: 'Word文書', value: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' },
                      { label: 'Excel', value: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' },
                      { label: 'PowerPoint', value: 'application/vnd.openxmlformats-officedocument.presentationml.presentation' },
                      { label: '画像 (JPEG)', value: 'image/jpeg' },
                      { label: '画像 (PNG)', value: 'image/png' },
                      { label: 'テキスト', value: 'text/plain' },
                    ]}
                  />
                  <Input
                    placeholder="作成者"
                    value={creator}
                    onChange={(e) => setCreator(e.target.value)}
                    onPressEnter={handleSearch}
                    style={{ width: 200 }}
                  />
                </Space>
              </div>
              
              <Table
                columns={columns}
                dataSource={objects}
                rowKey="id"
                loading={loading}
                pagination={{ pageSize: 20 }}
                size="small"
              />
            </Space>
          </Card>
        </Col>
      </Row>

      <Modal
        title="ファイルアップロード"
        open={uploadModalVisible}
        onCancel={() => setUploadModalVisible(false)}
        footer={null}
        maskClosable={false}
      >
        <Form form={form} onFinish={handleUpload} layout="vertical">
          <Form.Item
            name="file"
            label="ファイル"
            rules={[{ required: true, message: 'ファイルを選択してください' }]}
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
              <p className="ant-upload-text">ファイルをドラッグ&amp;ドロップまたはクリックして選択</p>
            </Upload.Dragger>
          </Form.Item>
          <Form.Item
            name="name"
            label="ファイル名"
            rules={[{ required: true, message: 'ファイル名を入力してください' }]}
          >
            <Input placeholder="ファイル名を入力" />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                アップロード
              </Button>
              <Button onClick={() => {
                setUploadModalVisible(false);
                form.resetFields();
              }}>
                キャンセル
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="フォルダ作成"
        open={folderModalVisible}
        onCancel={() => setFolderModalVisible(false)}
        footer={null}
        maskClosable={false}
      >
        <Form form={form} onFinish={handleCreateFolder} layout="vertical">
          <Form.Item
            name="name"
            label="フォルダ名"
            rules={[{ required: true, message: 'フォルダ名を入力してください' }]}
          >
            <Input placeholder="フォルダ名を入力" />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                作成
              </Button>
              <Button onClick={() => setFolderModalVisible(false)}>
                キャンセル
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="チェックイン"
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
            label="ファイル (オプション - 新しいコンテンツで更新する場合)"
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
              <p className="ant-upload-text">ファイルをドラッグ&amp;ドロップまたはクリックして選択</p>
              <p className="ant-upload-hint">チェックイン時にコンテンツを更新する場合のみファイルを選択してください</p>
            </Upload.Dragger>
          </Form.Item>
          <Form.Item
            name="versionType"
            label="バージョンタイプ"
            rules={[{ required: true, message: 'バージョンタイプを選択してください' }]}
          >
            <Radio.Group>
              <Radio value="minor">マイナーバージョン (例: 1.1 → 1.2)</Radio>
              <Radio value="major">メジャーバージョン (例: 1.1 → 2.0)</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item
            name="comment"
            label="チェックインコメント"
          >
            <Input.TextArea
              rows={4}
              placeholder="変更内容のコメントを入力してください"
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                チェックイン
              </Button>
              <Button onClick={() => {
                setCheckInModalVisible(false);
                form.resetFields();
              }}>
                キャンセル
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="バージョン履歴"
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
              title: 'バージョン',
              dataIndex: 'versionLabel',
              key: 'version',
              width: 100,
            },
            {
              title: '更新日時',
              dataIndex: 'lastModificationDate',
              key: 'date',
              width: 180,
              render: (date: string) => date ? new Date(date).toLocaleString('ja-JP') : '-',
            },
            {
              title: '更新者',
              dataIndex: 'lastModifiedBy',
              key: 'author',
              width: 120,
            },
            {
              title: 'コメント',
              key: 'comment',
              render: (record: CMISObject) => record.properties?.['cmis:checkinComment'] || '-',
            },
            {
              title: 'アクション',
              key: 'actions',
              width: 100,
              render: (_: any, record: CMISObject) => (
                <Tooltip title="ダウンロード">
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
    </div>
  );
};

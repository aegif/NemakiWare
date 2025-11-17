/**
 * PermissionManagement Component for NemakiWare React UI
 *
 * ACL editing interface providing object-centric CMIS permission management:
 * - Single object ACL management with URL-based object ID navigation
 * - Permission list display in Ant Design Table with 4 columns (principal, permissions, direct flag, actions)
 * - Add permission via Modal form with principal select (users + groups) and permission checkboxes
 * - Remove permission with Popconfirm confirmation (direct permissions only)
 * - Principal icon-based rendering: UserOutlined blue for users, TeamOutlined green for groups
 * - Direct permission filtering: delete button only shown for direct permissions (not inherited)
 * - Separated API loading with detailed error handling: getObject, getACL, getUsers, getGroups individually
 * - Checkbox.Group for multiple permission selection: cmis:read, cmis:write, cmis:all
 * - Combined principal options: users and groups in same dropdown with showSearch
 * - Object information display card: ID, Type, Path in 3-column grid layout
 * - Navigation integration: back button to /documents/{objectId}
 * - Japanese localized UI with detailed console debugging
 *
 * Component Architecture:
 * PermissionManagement (object-centric ACL editor)
 *   ├─ useParams(): objectId from URL /permissions/:objectId
 *   ├─ useState(): object (CMISObject), acl (ACL), users (User[]), groups (Group[]), loading, modalVisible
 *   ├─ useEffect(): loadData() on objectId/repositoryId change
 *   ├─ loadData(): 4 separate API calls with individual error handling
 *   │   ├─ getObject(repositoryId, objectId) → setObject()
 *   │   ├─ getACL(repositoryId, objectId) → setACL()
 *   │   ├─ getUsers(repositoryId) → setUsers()
 *   │   └─ getGroups(repositoryId) → setGroups()
 *   ├─ Card: Object information (ID, Type, Path)
 *   ├─ Table: Permission list with columns
 *   │   ├─ Principal column: icon-based rendering (UserOutlined blue vs TeamOutlined green)
 *   │   ├─ Permissions column: Tag array with Space wrap
 *   │   ├─ Direct flag column: Tag green (直接) vs orange (継承)
 *   │   └─ Actions column: delete button (direct permissions only) with Popconfirm
 *   └─ Modal: Add permission form
 *       ├─ Form.Item: principalId (Select with users + groups combined)
 *       └─ Form.Item: permissions (Checkbox.Group with cmis:read, cmis:write, cmis:all)
 *
 * ACL Data Flow:
 * 1. Component mounts → useParams() extracts objectId from URL
 * 2. loadData() executes 4 API calls separately with detailed error logging
 * 3. Object metadata + ACL permissions + users + groups loaded into state
 * 4. Table renders ACL permissions with principal lookup (users.find() + groups.find())
 * 5. Add permission → Modal form with principal select + permission checkboxes
 * 6. handleAddPermission() → setACL(updatedACL) → loadData() refresh
 * 7. Remove permission (direct only) → handleRemovePermission() → filter permissions array → setACL()
 * 8. Back button → navigate(`/documents/${objectId}`) returns to document details
 *
 * Usage Examples:
 * ```typescript
 * // Layout.tsx - ACL management route (assumed)
 * <Route path="/permissions/:objectId" element={<PermissionManagement repositoryId={repositoryId} />} />
 *
 * // DocumentManagement.tsx - Navigate to ACL editor (assumed)
 * const handleManagePermissions = (objectId: string) => {
 *   navigate(`/permissions/${objectId}`);
 * };
 *
 * // URL-based navigation example
 * // http://localhost:8080/core/ui/permissions/abc123
 * // objectId = "abc123" extracted via useParams()
 *
 * // ACL structure example
 * const acl: ACL = {
 *   permissions: [
 *     { principalId: "admin", permissions: ["cmis:all"], direct: true },
 *     { principalId: "GROUP_EVERYONE", permissions: ["cmis:read"], direct: false }
 *   ]
 * };
 * // Renders 2 rows: admin (direct, delete button shown), GROUP_EVERYONE (inherited, no delete button)
 *
 * // Principal rendering example
 * users = [{ id: "admin", name: "Administrator" }]
 * groups = [{ id: "GROUP_EVERYONE", name: "Everyone" }]
 * // Principal column: UserOutlined blue "Administrator (admin)" vs TeamOutlined green "Everyone (GROUP_EVERYONE)"
 *
 * // Permission selection example
 * <Checkbox.Group>
 *   <Checkbox value="cmis:read">cmis:read</Checkbox>
 *   <Checkbox value="cmis:write">cmis:write</Checkbox>
 *   <Checkbox value="cmis:all">cmis:all</Checkbox>
 * </Checkbox.Group>
 * // User can select multiple permissions: ["cmis:read", "cmis:write"]
 * ```
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. ACL-Specific Component Object-Centric Permission Management (Lines 33-56, 241-264):
 *    - useParams<{ objectId: string }>() extracts objectId from URL /permissions/:objectId
 *    - Single object ACL management (not bulk or multi-object)
 *    - Component dedicated to one object's permissions at a time
 *    - navigate(`/documents/${objectId}`) returns to document details
 *    - Rationale: CMIS ACL model is object-centric, each object has its own ACL
 *    - Implementation: URL parameter-based navigation with useParams() hook
 *    - Advantage: Bookmarkable ACL editor URLs, browser back/forward support
 *    - Trade-off: Cannot manage multiple objects' ACLs simultaneously, requires navigation for each object
 *    - Pattern: Object-centric design focused on single resource management
 *
 * 2. Separated API Loading with Detailed Error Handling (Lines 58-103):
 *    - loadData() executes 4 separate API calls: getObject, getACL, getUsers, getGroups
 *    - Each API call wrapped in individual .catch() with specific error messages
 *    - console.log('[ACL DEBUG] ...') for detailed debugging at each step
 *    - Fail-fast approach: any API failure throws and stops entire load
 *    - Rationale: Better debugging compared to single try-catch block, clear error source identification
 *    - Implementation: try { obj = await getObject().catch(...); acl = await getACL().catch(...); ... }
 *    - Advantage: Precise error messages, clear debugging logs, easy to identify which API failed
 *    - Trade-off: More verbose code, all 4 APIs must succeed (no partial loading)
 *    - Pattern: Separated async operations with individual error boundaries
 *
 * 3. Principal Icon-Based Rendering User vs Group (Lines 152-173):
 *    - UserOutlined blue #1890ff for users, TeamOutlined green #52c41a for groups
 *    - users.find(u => u.id === principalId) and groups.find(g => g.id === principalId) for metadata lookup
 *    - Displays "{name} ({principalId})" format for users and groups
 *    - Falls back to raw principalId string if neither user nor group found
 *    - Rationale: Visual distinction between user and group principals, consistent with other components
 *    - Implementation: Conditional rendering in column render function with icon + Space
 *    - Advantage: Faster scanning of ACL list, clear user vs group identification
 *    - Trade-off: Requires users and groups to be loaded, may show raw ID if metadata missing
 *    - Pattern: Icon-based type visualization with color coding for principal types
 *
 * 4. Direct Permission Filtering for Delete Button (Lines 203-220):
 *    - record.direct && ( ... ) conditional rendering for delete button
 *    - Delete button only shown for direct permissions (not inherited)
 *    - Inherited permissions cannot be removed from this object (must be removed from parent)
 *    - Popconfirm dialog for delete confirmation with Japanese messages
 *    - Rationale: CMIS ACL model has direct and inherited permissions, only direct can be modified on this object
 *    - Implementation: Conditional rendering with && short-circuit operator in column render
 *    - Advantage: Prevents users from attempting invalid ACL operations, clear visual indication
 *    - Trade-off: Users cannot remove inherited permissions, may be confusing without explanation
 *    - Pattern: Permission-based action filtering with direct flag check
 *
 * 5. Checkbox.Group for Multiple Permission Selection (Lines 321-329):
 *    - Checkbox.Group instead of Select mode="multiple" for permission selection
 *    - Allows selecting multiple permissions: cmis:read, cmis:write, cmis:all
 *    - Space direction="vertical" for stacked checkbox layout
 *    - availablePermissions array (Lines 46-50) defines 3 CMIS standard permissions
 *    - Rationale: Better UX for permission selection, checkboxes more intuitive than multi-select dropdown
 *    - Implementation: <Checkbox.Group><Space direction="vertical">{availablePermissions.map(...)}</Space></Checkbox.Group>
 *    - Advantage: Clear visual feedback, all options visible at once, standard checkbox interaction
 *    - Trade-off: More vertical space required, limited to small number of permissions
 *    - Pattern: Checkbox group for multi-selection with vertical layout
 *
 * 6. Combined Principal Options Users + Groups (Lines 228-239):
 *    - principalOptions array combines users and groups into single option list
 *    - Each option has label (name + ID), value (ID), and icon (UserOutlined vs TeamOutlined)
 *    - showSearch filterOption for searchable dropdown
 *    - Unified principal selection (users and groups in same dropdown)
 *    - Rationale: Simplifies form UI, users don't need to switch between user and group selects
 *    - Implementation: [...users.map(...), ...groups.map(...)] array spread concatenation
 *    - Advantage: Single dropdown for both principal types, searchable with auto-filter
 *    - Trade-off: No visual separation between users and groups in dropdown (only icons distinguish)
 *    - Pattern: Merged option lists with icon-based type indicators
 *
 * 7. Direct Permission Flag Hardcoded on Creation (Lines 109-113):
 *    - direct: true hardcoded on new permission creation
 *    - Ensures newly added permissions are marked as direct (not inherited)
 *    - Consistent with ACL model: permissions added on this object are direct
 *    - Rationale: All permissions added via this UI are direct to the current object
 *    - Implementation: const newPermission: Permission = { ..., direct: true }
 *    - Advantage: Correct ACL semantics, newly added permissions can be deleted
 *    - Trade-off: Cannot add inherited permissions (but that's correct behavior)
 *    - Pattern: Hardcoded boolean flag for consistent ACL model semantics
 *
 * 8. Object Information Display Card (Lines 266-278):
 *    - Card size="small" title="オブジェクト情報" displays object metadata
 *    - Grid layout with 3 columns: ID, Type, Path
 *    - Contextual information about the object being managed
 *    - Helps users confirm they're editing the right object's ACL
 *    - Rationale: Users need context about which object's permissions they're managing
 *    - Implementation: display: grid gridTemplateColumns: repeat(3, 1fr) gap: 16
 *    - Advantage: Clear context, prevents accidental permission changes on wrong object
 *    - Trade-off: Additional vertical space, object metadata may be redundant if user navigated from document details
 *    - Pattern: Contextual information card for focused operation interfaces
 *
 * 9. Permission Array Display with Tag Components (Lines 179-187):
 *    - permissions is an array of strings (e.g., ["cmis:read", "cmis:write"])
 *    - Space wrap allows tags to wrap to multiple lines if many permissions
 *    - Tag color="blue" for all permissions (consistent color scheme)
 *    - Rationale: Permissions array needs readable display format, tags provide visual separation
 *    - Implementation: <Space wrap>{permissions.map(permission => <Tag color="blue">{permission}</Tag>)}</Space>
 *    - Advantage: Clear visual separation between permissions, wraps gracefully
 *    - Trade-off: May wrap to multiple lines if many permissions, no sorting or filtering
 *    - Pattern: Tag array rendering for multi-value properties
 *
 * 10. Navigation Integration with Back Button (Lines 246-251):
 *     - ArrowLeftOutlined icon with 戻る (Back) button
 *     - navigate(`/documents/${objectId}`) returns to document details page
 *     - Integration with DocumentManagement component (assumed route structure)
 *     - Breadcrumb-like navigation pattern for focused operation workflows
 *     - Rationale: Users need easy way to return to document after ACL changes
 *     - Implementation: Button with onClick={() => navigate(`/documents/${objectId}`)}
 *     - Advantage: Clear exit path, consistent with application navigation patterns
 *     - Trade-off: Assumes /documents/:objectId route exists, may break if route structure changes
 *     - Pattern: Back navigation for focused operation interfaces
 *
 * Expected Results:
 * - PermissionManagement displays ACL editor for single object with URL-based navigation
 * - Object information card shows ID, Type, Path in 3-column grid layout
 * - Table displays ACL permissions with 4 columns: principal (icon-based), permissions (Tag array), direct flag (Tag), actions (delete for direct only)
 * - Add permission button opens Modal form with principal select (users + groups combined) and permission checkboxes
 * - Principal column shows UserOutlined blue icon for users, TeamOutlined green icon for groups with "{name} ({id})" format
 * - Permissions column shows Tag array with blue color for each permission
 * - Direct flag column shows green Tag (直接) for direct permissions, orange Tag (継承) for inherited
 * - Actions column shows delete button with Popconfirm only for direct permissions (inherited permissions hidden)
 * - Modal form has principal select dropdown with showSearch and permission Checkbox.Group
 * - Back button navigates to /documents/{objectId} document details page
 * - HTTP error handling shows Japanese messages with detailed console debugging
 *
 * Performance Characteristics:
 * - Initial load: 4 separate API calls (getObject, getACL, getUsers, getGroups) executed sequentially
 * - ACL list loads once on mount, caches in state
 * - Users and groups load once on mount, cached for principal lookup
 * - Table renders all ACL permissions (no pagination, acceptable for typical ACL sizes <50 entries)
 * - Principal rendering: users.find() + groups.find() on every row render (acceptable for <100 users/groups)
 * - Modal form renders only when visible (conditional rendering)
 * - Add/remove permission operations reload full ACL via loadData()
 * - No debouncing or throttling on operations
 * - Memory usage proportional to ACL size + user count + group count
 *
 * Debugging Features:
 * - React DevTools: inspect object, acl, users, groups, loading, modalVisible state
 * - console.log('[ACL DEBUG] ...') for detailed API loading steps and success/failure
 * - console.error() logs for all API failures with error objects
 * - Network tab: GET /objects/:objectId, GET /acl/:objectId, GET /users, GET /groups, PUT /acl/:objectId
 * - Form validation errors shown inline below each field
 * - Ant Design message.success() and message.error() for operation feedback
 * - Principal lookup visible in table: icon + name + ID rendering
 * - Direct flag visible in table: Tag color distinction
 *
 * Known Limitations:
 * - Single object ACL management only, no bulk operations
 * - Cannot manage inherited permissions (must navigate to parent object)
 * - All 4 APIs must succeed for page to load (no partial loading)
 * - No pagination for ACL list, may have issues with >100 permissions
 * - Principal select may be slow with >1000 users/groups
 * - No sorting or filtering on ACL table
 * - Direct flag hardcoded to true on creation (correct behavior but not configurable)
 * - availablePermissions hardcoded to 3 CMIS standard permissions (no custom permissions)
 * - Error messages hard-coded in Japanese, no internationalization
 * - No principal grouping in dropdown (users and groups intermixed)
 * - Back navigation assumes /documents/:objectId route structure
 * - No confirmation when navigating away with unsaved changes (no unsaved state)
 *
 * Relationships to Other Components:
 * - Used by: Layout.tsx (assumed route /permissions/:objectId)
 * - Depends on: CMISService for getObject, getACL, setACL, getUsers, getGroups operations
 * - Depends on: AuthContext via useAuth() for handleAuthError callback
 * - Depends on: Ant Design Table, Modal, Form, Select, Checkbox, Button, Popconfirm, Tag, Card, Space, message components
 * - Depends on: react-router-dom useParams() and useNavigate() hooks
 * - Renders: ACL editing interface for admin/power users with permission management rights
 * - Integration: DocumentManagement component (assumed) navigates to /permissions/:objectId for ACL editing
 * - Navigation: Back button navigates to /documents/:objectId (DocumentViewer or DocumentManagement)
 * - Related: UserManagement and GroupManagement provide principal data via getUsers/getGroups
 *
 * Common Failure Scenarios:
 * - loadData fails: 401 authentication error, user not logged in or token expired
 * - loadData fails: 403 permission error, user lacks permission to view ACL
 * - loadData fails: 404 object not found, objectId invalid or object deleted
 * - getACL fails: 500 server error, backend or database issues
 * - getUsers/getGroups fails: API unavailable, no users/groups loaded (principal rendering shows raw IDs)
 * - handleAddPermission fails: duplicate principalId, ACL already contains entry
 * - handleAddPermission fails: invalid permission, server rejects unknown permission
 * - handleRemovePermission fails: ACL update rejected, may have validation errors
 * - setACL fails: concurrent modification, ACL changed by another user
 * - Network timeout: API calls hang, no timeout handling
 * - State updates async: setState may show stale ACL data
 * - Empty ACL: no permissions loaded (table shows empty state)
 * - objectId undefined: useParams() returns undefined, page breaks
 * - navigate fails: route doesn't exist, back button navigation breaks
 * - Modal form submit fails: required fields not filled, validation errors
 * - Principal select empty: no users/groups loaded, cannot add permissions
 * - Permission checkboxes unselected: form validation requires at least one permission
 */

import React, { useState, useEffect } from 'react';
import { 
  Card, 
  Table, 
  Button, 
  Space, 
  Modal, 
  Form, 
  Select, 
  message, 
  Popconfirm,
  Tag,
  Checkbox
} from 'antd';
import { 
  LockOutlined, 
  PlusOutlined, 
  DeleteOutlined,
  UserOutlined,
  TeamOutlined,
  ArrowLeftOutlined
} from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { CMISService } from '../../services/cmis';
import { CMISObject, ACL, Permission, User, Group } from '../../types/cmis';

interface PermissionManagementProps {
  repositoryId: string;
}

import { useAuth } from '../../contexts/AuthContext';
export const PermissionManagement: React.FC<PermissionManagementProps> = ({ repositoryId }) => {
  const { objectId } = useParams<{ objectId: string }>();
  const navigate = useNavigate();
  const [object, setObject] = useState<CMISObject | null>(null);
  const [acl, setACL] = useState<ACL | null>(null);
  const [users, setUsers] = useState<User[]>([]);
  const [groups, setGroups] = useState<Group[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalVisible, setModalVisible] = useState(false);
  const [isInherited, setIsInherited] = useState<boolean>(true);
  const [form] = Form.useForm();

  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

  const availablePermissions = [
    'cmis:read',
    'cmis:write',
    'cmis:all'
  ];

  useEffect(() => {
    if (objectId) {
      loadData();
    }
  }, [objectId, repositoryId]);

  const loadData = async () => {
    if (!objectId) return;

    try {
      // Load each API separately with detailed error handling
      const obj = await cmisService.getObject(repositoryId, objectId)
        .catch(err => {
          console.error('[ACL DEBUG] Failed to load object:', err);
          throw new Error(`オブジェクトの読み込みに失敗: ${err.message}`);
        });

      const aclData = await cmisService.getACL(repositoryId, objectId)
        .catch(err => {
          console.error('[ACL DEBUG] Failed to load ACL:', err);
          throw new Error(`ACLの読み込みに失敗: ${err.message}`);
        });

      const userList = await cmisService.getUsers(repositoryId)
        .catch(err => {
          console.error('[ACL DEBUG] Failed to load users:', err);
          throw new Error(`ユーザー一覧の読み込みに失敗: ${err.message}`);
        });

      const groupList = await cmisService.getGroups(repositoryId)
        .catch(err => {
          console.error('[ACL DEBUG] Failed to load groups:', err);
          throw new Error(`グループ一覧の読み込みに失敗: ${err.message}`);
        });

      setObject(obj);
      setACL(aclData);
      setUsers(userList);
      setGroups(groupList);

      const inheritanceStatus = aclData.aclInherited ?? true;
      setIsInherited(inheritanceStatus);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'データの読み込みに失敗しました';
      message.error(errorMessage);
      console.error('[ACL DEBUG] Load error:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleAddPermission = async (values: any) => {
    if (!acl || !objectId) return;
    
    try {
      const newPermission: Permission = {
        principalId: values.principalId,
        permissions: values.permissions,
        direct: true
      };
      
      const updatedACL: ACL = {
        ...acl,
        permissions: [...acl.permissions, newPermission]
      };
      
      await cmisService.setACL(repositoryId, objectId, updatedACL);
      message.success('権限を追加しました');
      setModalVisible(false);
      form.resetFields();
      loadData();
    } catch (error) {
      message.error('権限の追加に失敗しました');
    }
  };

  const handleRemovePermission = async (principalId: string) => {
    if (!acl || !objectId) return;
    
    try {
      const updatedACL: ACL = {
        ...acl,
        permissions: acl.permissions.filter(p => p.principalId !== principalId)
      };
      
      await cmisService.setACL(repositoryId, objectId, updatedACL);
      message.success('権限を削除しました');
      loadData();
    } catch (error) {
      message.error('権限の削除に失敗しました');
    }
  };

  const handleBreakInheritance = async () => {
    if (!acl || !objectId) return;

    Modal.confirm({
      title: 'ACL継承を切断しますか？',
      content: '親フォルダからの権限継承を解除します。この操作は元に戻せません。継承されている権限は直接権限として複製されます。',
      okText: '継承を切断',
      cancelText: 'キャンセル',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await cmisService.setACL(repositoryId, objectId, acl, { breakInheritance: true });
          message.success('ACL継承を切断しました');
          loadData();
        } catch (error) {
          message.error('ACL継承の切断に失敗しました');
          console.error('[ACL DEBUG] Break inheritance error:', error);
        }
      }
    });
  };

  const columns = [
    {
      title: 'プリンシパル',
      dataIndex: 'principalId',
      key: 'principalId',
      render: (principalId: string) => {
        const user = users.find(u => u.id === principalId);
        const group = groups.find(g => g.id === principalId);
        
        if (user) {
          return (
            <Space>
              <UserOutlined style={{ color: '#1890ff' }} />
              <span>{user.name} ({principalId})</span>
            </Space>
          );
        } else if (group) {
          return (
            <Space>
              <TeamOutlined style={{ color: '#52c41a' }} />
              <span>{group.name} ({principalId})</span>
            </Space>
          );
        } else {
          return principalId;
        }
      },
    },
    {
      title: '権限',
      dataIndex: 'permissions',
      key: 'permissions',
      render: (permissions: string[]) => (
        <Space wrap>
          {permissions.map(permission => (
            <Tag key={permission} color="blue">
              {permission}
            </Tag>
          ))}
        </Space>
      ),
    },
    {
      title: '直接権限',
      dataIndex: 'direct',
      key: 'direct',
      render: (direct: boolean) => (
        <Tag color={direct ? 'green' : 'orange'}>
          {direct ? '直接' : '継承'}
        </Tag>
      ),
    },
    {
      title: 'アクション',
      key: 'actions',
      width: 100,
      render: (_: any, record: Permission) => (
        record.direct && (
          <Popconfirm
            title="この権限を削除しますか？"
            onConfirm={() => handleRemovePermission(record.principalId)}
            okText="はい"
            cancelText="いいえ"
          >
            <Button 
              icon={<DeleteOutlined />} 
              size="small"
              danger
            >
              削除
            </Button>
          </Popconfirm>
        )
      ),
    },
  ];

  if (loading || !object || !acl) {
    return <div>読み込み中...</div>;
  }

  const principalOptions = [
    ...users.map(user => ({
      label: `${user.name} (${user.id})`,
      value: user.id,
      icon: <UserOutlined />
    })),
    ...groups.map(group => ({
      label: `${group.name} (${group.id})`,
      value: group.id,
      icon: <TeamOutlined />
    }))
  ];

  return (
    <Card>
      <Space direction="vertical" style={{ width: '100%' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Space>
            <Button 
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate(`/documents/${objectId}`)}
            >
              戻る
            </Button>
            <h2 style={{ margin: 0 }}>
              <LockOutlined /> 権限管理: {object.name}
            </h2>
          </Space>
          
          <Space>
            {isInherited && (
              <Button 
                type="default" 
                icon={<LockOutlined />}
                onClick={handleBreakInheritance}
                danger
              >
                継承を切る
              </Button>
            )}
            <Button 
              type="primary" 
              icon={<PlusOutlined />}
              onClick={() => setModalVisible(true)}
            >
              権限を追加
            </Button>
          </Space>
        </div>

        <Card size="small" title="オブジェクト情報">
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
            <div>
              <strong>ID:</strong> {object.id}
            </div>
            <div>
              <strong>タイプ:</strong> {object.objectType}
            </div>
            <div>
              <strong>パス:</strong> {object.path}
            </div>
          </div>
        </Card>

        <Table
          columns={columns}
          dataSource={acl.permissions}
          rowKey="principalId"
          pagination={false}
          size="small"
        />
      </Space>

      <Modal
        title="権限を追加"
        open={modalVisible}
        onCancel={() => setModalVisible(false)}
        footer={null}
        width={500}
        maskClosable={false}
      >
        <Form
          form={form}
          onFinish={handleAddPermission}
          layout="vertical"
        >
          <Form.Item
            name="principalId"
            label="ユーザー/グループ"
            rules={[{ required: true, message: 'ユーザーまたはグループを選択してください' }]}
          >
            <Select
              placeholder="ユーザーまたはグループを選択"
              showSearch
              filterOption={(input, option) =>
                (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
              }
              options={principalOptions}
            />
          </Form.Item>

          <Form.Item
            name="permissions"
            label="権限"
            rules={[{ required: true, message: '権限を選択してください' }]}
          >
            <Checkbox.Group>
              <Space direction="vertical">
                {availablePermissions.map(permission => (
                  <Checkbox key={permission} value={permission}>
                    {permission}
                  </Checkbox>
                ))}
              </Space>
            </Checkbox.Group>
          </Form.Item>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                追加
              </Button>
              <Button onClick={() => setModalVisible(false)}>
                キャンセル
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
};

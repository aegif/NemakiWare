/**
 * GroupManagement Component for NemakiWare React UI
 *
 * Group management component providing comprehensive group CRUD operations with member assignment:
 * - Group list display with Ant Design Table component (4 columns)
 * - Local search filtering (client-side) with multi-field matching (ID, name, members array)
 * - Group creation via Modal form with validation rules
 * - Group editing via Modal form (dual-mode: create vs edit)
 * - Group deletion with Popconfirm confirmation dialog
 * - Member management via Select multiple dropdown with user selection
 * - Comprehensive error handling with HTTP status code-based messaging (401, 403, 500)
 * - Group ID immutable after creation to maintain data integrity
 * - Member display with Tag components (truncation at 3 members with "+N more" overflow)
 * - User name fallback display in member select (name → firstName + lastName → user.id)
 * - Empty value display with dash (-) for missing optional fields
 * - Warning-level error handling for non-critical user list loading failures
 * - Japanese localized UI with detailed error messages
 *
 * Component Architecture:
 * GroupManagement (stateful group CRUD management)
 *   ├─ useState: groups[], users[], loading, modalVisible, editingGroup, searchText
 *   ├─ useEffect: loadGroups() + loadUsers() on repositoryId change
 *   ├─ CMISService: getGroups, createGroup, updateGroup, deleteGroup, getUsers
 *   ├─ Local Filtering: filteredGroups computed from groups.filter() with multi-field OR logic
 *   └─ Conditional Rendering:
 *       ├─ Card Container:
 *       │   ├─ Header: TeamOutlined + "グループ管理" title + "新規作成" button
 *       │   ├─ Input.Search: placeholder "グループを検索 (ID、名前、メンバー)"
 *       │   └─ Table: 4 columns (グループID, グループ名, メンバー, アクション)
 *       │       ├─ Column 1 (ID): Direct display (string)
 *       │       ├─ Column 2 (Name): render() with dash (-) fallback for empty
 *       │       ├─ Column 3 (Members): render() with Tag components + truncation at 3 + overflow "+N more"
 *       │       └─ Column 4 (Actions): EditOutlined button + Popconfirm DeleteOutlined button
 *       └─ Modal (dual-mode: create vs edit):
 *           └─ Form: 3 fields (id, name, members) + submit/cancel buttons
 *               ├─ id: required + pattern /^[a-zA-Z0-9_-]+$/ + disabled={!!editingGroup}
 *               ├─ name: required
 *               └─ members: Select mode="multiple" with user options (displayName fallback)
 *
 * Usage Examples:
 * ```typescript
 * // Layout.tsx - Admin menu integration (assumed)
 * <Route path="/admin/groups" element={<GroupManagement repositoryId={repositoryId} />} />
 *
 * // Example with bedroom repository
 * <GroupManagement repositoryId="bedroom" />
 * // Renders: Table with group list + search + create/edit/delete functionality
 *
 * // User Interaction Flow:
 * // 1. GroupManagement mounts → loadGroups() + loadUsers() fetch data
 * // 2. Groups displayed in Table with 4 columns
 * // 3. User types in search input → filteredGroups computed instantly (client-side)
 * // 4. User clicks "新規作成" → Modal opens with blank form
 * // 5. User fills form (ID: "developers", Name: "開発者", Members: select users)
 * // 6. User submits → createGroup() API call → success message → loadGroups() refresh
 * // 7. User clicks "編集" button → Modal opens pre-filled with group data
 * // 8. User modifies name/members → updateGroup() API call → success message → refresh
 * // 9. User clicks "削除" button → Popconfirm appears
 * // 10. User confirms → deleteGroup() API call → success message → refresh
 *
 * // Search Functionality Example:
 * // Search by ID: "admin" matches groups with "admin" in ID
 * // Search by name: "管理" matches groups with "管理" in name
 * // Search by member: "john" matches groups containing user "john" in members array
 *
 * // Member Display Example:
 * group.members = ["alice", "bob", "charlie"]
 *   → Displays: <Tag>alice</Tag> <Tag>bob</Tag> <Tag>charlie</Tag>
 * group.members = ["alice", "bob", "charlie", "dave", "eve"]
 *   → Displays: <Tag>alice</Tag> <Tag>bob</Tag> <Tag>charlie</Tag> <Tag>+2 more</Tag>
 * group.members = [] or null
 *   → Displays: "-"
 *
 * // User Name Fallback in Member Select:
 * user = { id: "john", name: "John Doe", firstName: null, lastName: null }
 *   → Option label: "John Doe (john)"
 * user = { id: "john", name: null, firstName: "John", lastName: "Doe" }
 *   → Option label: "John Doe (john)" (computed from firstName + lastName)
 * user = { id: "john", name: null, firstName: null, lastName: null }
 *   → Option label: "john (john)" (fallback to ID)
 * ```
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. Local Search Filtering Client-Side with Member Array Searching (Lines 168-176):
 *    - filteredGroups computed from groups array with multi-field OR matching
 *    - Searches ID, name, and members array (members?.some(member => ...))
 *    - members?.some() allows searching for specific member usernames within groups
 *    - toLowerCase() for case-insensitive search, instant feedback without server calls
 *    - Pattern: group.id.includes() || group.name?.includes() || group.members?.some()
 *    - Advantage: Instant member-based search, find all groups a user belongs to
 *    - Trade-off: Loads full group list in memory, may not scale beyond 500 groups
 *
 * 2. Comprehensive HTTP Status Code-Based Error Handling (Lines 47-79, 92-126, 134-159):
 *    - Same pattern as UserManagement: layered error handling with status code checks
 *    - 401 authentication error → "認証エラー: ログインし直してください"
 *    - 403 permission error → "権限エラー: グループ管理の権限がありません"
 *    - 500 server error → "サーバー側でエラーが発生しています" + error.details
 *    - Dual-layer approach: user-facing message.error() + developer console.error()
 *    - Context-specific messages for loadGroups, handleSubmit (create/update), handleDelete
 *    - Advantage: Balances user experience with developer debugging needs
 *
 * 3. Dual-Mode Modal Create vs Edit Pattern (Lines 128-132, 278-346):
 *    - Same pattern as UserManagement: single Modal component for both operations
 *    - editingGroup state determines mode (null=create, Group=edit)
 *    - Modal title changes: "グループ編集" vs "新規グループ作成"
 *    - form.setFieldsValue(editingGroup) pre-fills form on edit (Line 130)
 *    - handleEdit(record) sets editingGroup and shows modal (Lines 128-132)
 *    - handleCancel() clears editingGroup and hides modal (Lines 161-165)
 *    - Advantage: Reduces code duplication, single modal for both workflows
 *    - Pattern: Conditional rendering of form fields and submit button text based on mode
 *
 * 4. Group ID Immutable After Creation (Lines 297-300):
 *    - Input disabled={!!editingGroup} prevents editing group ID after creation
 *    - Group ID is primary key, changing it breaks data integrity and references
 *    - disabled property computed from editingGroup presence (truthy=edit mode)
 *    - Create mode allows ID input, edit mode shows read-only ID field
 *    - Prevents accidental primary key changes that could break ACL references
 *    - Ensures referential integrity for permission system and group membership
 *
 * 5. Member Display with Tag Components and Truncation at 3 (Lines 194-210):
 *    - render() function displays members as Tag components with UserOutlined icon
 *    - members.slice(0, 3).map() shows first 3 members only for UI space efficiency
 *    - Truncation pattern: first 3 members + "+N more" Tag for overflow
 *    - Tag color="green" for member tags, color="default" for overflow indicator
 *    - Space wrap allows tags to wrap to multiple lines if needed
 *    - Advantage: Compact visual representation, prevents table row overflow with many members
 *    - Trade-off: Cannot see all members in table, must open edit modal to see full list
 *
 * 6. User Name Fallback Display in Member Select Options (Lines 318-331):
 *    - Same pattern as UserManagement: computes displayName from name → firstName + lastName → user.id
 *    - if (!displayName || displayName.trim() === '') fallback to computed name
 *    - [user.firstName, user.lastName].filter(n => n && n.trim() !== '').join(' ')
 *    - Final fallback: displayName || user.id ensures non-empty label
 *    - Label format: "displayName (user.id)" provides both readable name and unique ID
 *    - Advantage: Flexible name formats, graceful degradation for missing data
 *    - Pattern: Multi-level fallback with filter() and join() for safe string construction
 *
 * 7. Empty Value Display with Dash (-) Placeholder (Lines 188, 196):
 *    - Group name column: name && name.trim() !== '' ? name : '-' ternary pattern
 *    - Members column: if (!members || members.length === 0) return '-' early return
 *    - Consistent empty state representation across optional fields
 *    - Better UX than showing blank cells or "null" text
 *    - Professional table appearance with standardized placeholder pattern
 *
 * 8. Popconfirm for Destructive Delete Operations (Lines 225-238):
 *    - Same pattern as UserManagement: Popconfirm wraps delete Button
 *    - Confirmation dialog: "このグループを削除しますか？"
 *    - onConfirm={() => handleDelete(record.id)} only executes on user confirmation
 *    - okText="はい" cancelText="いいえ" Japanese localization
 *    - Prevents accidental group deletions requiring explicit confirmation
 *    - Standard UI pattern for irreversible actions
 *
 * 9. Form Validation with Alphanumeric Pattern Matching (Lines 289-309):
 *    - Group ID: required + pattern /^[a-zA-Z0-9_-]+$/ alphanumeric + underscore + hyphen only
 *    - More restrictive than UserManagement (which allows all characters)
 *    - Enforces safe group ID format for CMIS API compatibility
 *    - Pattern prevents special characters that might cause API or URL encoding issues
 *    - Group name: required validation only (allows any characters)
 *    - Advantage: Prevents data integrity issues with special characters in group IDs
 *
 * 10. Warning-Level Error Handling for Non-Critical User List Loading (Lines 81-90):
 *     - loadUsers() failure uses console.warn() instead of message.error()
 *     - User list loading is non-critical for group management operations
 *     - Comment: "ユーザー読み込み失敗はグループ管理では警告レベル"
 *     - Allows group CRUD operations to continue even if user list unavailable
 *     - Graceful degradation: member select may be limited but group management still works
 *     - Advantage: Resilient UI that doesn't block group management due to user API failures
 *     - Pattern: Separate critical (loadGroups) vs non-critical (loadUsers) error handling
 *
 * Expected Results:
 * - GroupManagement displays group list in Ant Design Table with 4 columns
 * - Search input filters groups client-side instantly (ID, name, members matching)
 * - Create button opens modal with blank form for new group creation
 * - Edit button opens modal pre-filled with group data for editing
 * - Delete button shows Popconfirm then calls deleteGroup API on confirmation
 * - Group ID field disabled on edit mode (immutable primary key)
 * - Member column shows first 3 members as Tag components with UserOutlined icon
 * - Member overflow shows "+N more" Tag (e.g., "+5 more" for 8 total members)
 * - Member select dropdown shows users with fallback name display (name → firstName + lastName → ID)
 * - Empty group name or members display "-" placeholder
 * - Form validation enforces alphanumeric pattern for group ID
 * - HTTP error handling shows context-specific Japanese messages (401, 403, 500)
 * - User list loading failure shows console.warn() but allows group management to continue
 *
 * Performance Characteristics:
 * - Local search filtering: Instant, no network latency, client-side array.filter()
 * - filteredGroups computed on every render: Acceptable for <500 groups
 * - Group list loads once on mount: loadGroups() caches in state
 * - User list loads once on mount: loadUsers() caches in state (non-critical)
 * - Form modal renders only when visible: Conditional rendering reduces DOM nodes
 * - Table renders only filtered groups: Reduces table row count
 * - Member Tag truncation: Limits DOM nodes to 4 tags per row (3 members + overflow)
 * - Search input onChange triggers re-render: Acceptable for small datasets
 * - No debouncing on search input: Instant feedback
 * - Memory usage: Proportional to group count + user count, full lists in browser memory
 *
 * Debugging Features:
 * - React DevTools: Inspect groups, users, searchText, editingGroup, modalVisible state
 * - console.error() logs for all HTTP errors with error.status and error.details
 * - loadGroups, handleSubmit (create/update), handleDelete all log errors to console
 * - console.warn() logs for non-critical user list loading failures
 * - Network tab: Shows GET /groups, POST /groups, PUT /groups/:id, DELETE /groups/:id, GET /users requests
 * - Form validation errors shown inline below each field
 * - Ant Design message.success() and message.error() for operation feedback
 * - Browser localStorage may cache auth token: Inspect Application tab
 *
 * Known Limitations:
 * - Local search no server-side filtering: Limited to loaded groups, may not scale beyond 500 groups
 * - No pagination for group list: All groups loaded at once, performance issues with >500 groups
 * - No debouncing on search input: May cause excessive re-renders with large datasets
 * - Group ID validation client-side only: Server should also validate pattern
 * - Group ID cannot be changed after creation: May be inconvenient for typos
 * - Member display truncation at 3: Cannot see all members in table, must open edit modal
 * - No bulk operations: Can only create/edit/delete one group at a time
 * - Error messages hard-coded in Japanese: No internationalization support
 * - User list loading failure silent: Only console.warn(), no user notification
 * - Member select may be empty if user list fails: No fallback mechanism
 * - No group membership hierarchy: Flat group structure, no nested groups
 * - Delete operation no cascade delete handling: Groups with ACL references may fail to delete
 *
 * Relationships to Other Components:
 * - Used by: Layout.tsx admin menu item "グループ管理" (assumed)
 * - Depends on: CMISService for getGroups, createGroup, updateGroup, deleteGroup, getUsers operations
 * - Depends on: Ant Design Table, Modal, Form, Input, Select, Button, Popconfirm, Card, Tag, Space, message components
 * - Depends on: AuthContext via useAuth() for handleAuthError callback
 * - Renders: Group CRUD interface for admin users only, requires admin permissions
 * - Integration: Layout renders GroupManagement route for /admin/groups path (assumed)
 * - Related to: UserManagement.tsx for user list (getUsers shared API)
 * - Related to: PermissionManagement.tsx for ACL group references (assumed)
 *
 * Common Failure Scenarios:
 * - loadGroups fails 401 authentication error: User not logged in or token expired
 * - loadGroups fails 403 permission error: User lacks admin rights for group management
 * - loadGroups fails 500 server error: Backend or database issues
 * - createGroup fails duplicate group ID: Primary key violation
 * - createGroup fails invalid group ID format: Pattern validation error
 * - updateGroup fails group not found 404: Group may have been deleted by another user
 * - deleteGroup fails group in use: Foreign key constraint violation (ACL references)
 * - loadUsers fails silently: console.warn() only, member select may be empty
 * - Search filtering shows no results: searchText doesn't match any groups
 * - Group ID validation fails pattern: Must be alphanumeric + underscore + hyphen only
 * - Member select shows no options: loadUsers() failed or returned empty array
 * - Modal form submit fails required fields: Group ID or name not filled, validation errors
 * - Network timeout: API calls hang, no timeout handling configured
 * - State updates async: setState may show stale data, React batching delays
 * - Empty group list: loadGroups() returned empty array or failed silently
 * - Member Tag overflow calculation incorrect: members.length > 3 edge case
 */

import React, { useState, useEffect } from 'react';
import { 
  Table, 
  Button, 
  Space, 
  Modal, 
  Form, 
  Input, 
  message, 
  Popconfirm,
  Card,
  Tag,
  Select
} from 'antd';
import { 
  TeamOutlined, 
  PlusOutlined, 
  EditOutlined, 
  DeleteOutlined,
  UserOutlined
} from '@ant-design/icons';
import { CMISService } from '../../services/cmis';
import { Group, User } from '../../types/cmis';

interface GroupManagementProps {
  repositoryId: string;
}

import { useAuth } from '../../contexts/AuthContext';
export const GroupManagement: React.FC<GroupManagementProps> = ({ repositoryId }) => {
  const [groups, setGroups] = useState<Group[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingGroup, setEditingGroup] = useState<Group | null>(null);
  const [searchText, setSearchText] = useState('');
  const [form] = Form.useForm();

  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

  useEffect(() => {
    loadGroups();
    loadUsers();
  }, [repositoryId]);

  const loadGroups = async () => {
    setLoading(true);
    try {
      const groupList = await cmisService.getGroups(repositoryId);
      setGroups(groupList);
    } catch (error: any) {
      // Failed to load groups
      let errorMessage = 'グループの読み込みに失敗しました';
      
      if (error.status === 500) {
        errorMessage = 'サーバー側でエラーが発生しています';
        if (error.details) {
          errorMessage += `\n詳細: ${error.details}`;
        }
      } else if (error.status === 401) {
        errorMessage = '認証エラー: ログインし直してください';
      } else if (error.status === 403) {
        errorMessage = '権限エラー: グループ管理の権限がありません';
      } else if (error.message) {
        errorMessage = error.message;
      }
      
      message.error(errorMessage);
    } finally {
      setLoading(false);
    }
  };

  const loadUsers = async () => {
    try {
      const userList = await cmisService.getUsers(repositoryId);
      setUsers(userList);
    } catch (error: any) {
      // User loading failed - group management can continue without user list
    }
  };

  const handleSubmit = async (values: any) => {
    try {
      if (editingGroup) {
        await cmisService.updateGroup(repositoryId, editingGroup.id, values);
        message.success('グループを更新しました');
      } else {
        await cmisService.createGroup(repositoryId, values);
        message.success('グループを作成しました');
      }
      
      setModalVisible(false);
      setEditingGroup(null);
      form.resetFields();
      loadGroups();
    } catch (error: any) {
      // Failed to create/update group
      let errorMessage = editingGroup ? 'グループの更新に失敗しました' : 'グループの作成に失敗しました';
      
      if (error.status === 500) {
        errorMessage = 'サーバー側でエラーが発生しています';
        if (error.details) {
          errorMessage += `\n詳細: ${error.details}`;
        }
      } else if (error.status === 401) {
        errorMessage = '認証エラー: ログインし直してください';
      } else if (error.status === 403) {
        errorMessage = '権限エラー: この操作の権限がありません';
      } else if (error.message) {
        errorMessage = error.message;
      }
      
      message.error(errorMessage);
    }
  };

  const handleEdit = (group: Group) => {
    setEditingGroup(group);
    form.setFieldsValue(group);
    setModalVisible(true);
  };

  const handleDelete = async (groupId: string) => {
    try {
      await cmisService.deleteGroup(repositoryId, groupId);
      message.success('グループを削除しました');
      loadGroups();
    } catch (error: any) {
      // Failed to delete group
      let errorMessage = 'グループの削除に失敗しました';
      
      if (error.status === 500) {
        errorMessage = 'サーバー側でエラーが発生しています';
        if (error.details) {
          errorMessage += `\n詳細: ${error.details}`;
        }
      } else if (error.status === 401) {
        errorMessage = '認証エラー: ログインし直してください';
      } else if (error.status === 403) {
        errorMessage = '権限エラー: グループ削除の権限がありません';
      } else if (error.message) {
        errorMessage = error.message;
      }
      
      message.error(errorMessage);
    }
  };

  const handleCancel = () => {
    setModalVisible(false);
    setEditingGroup(null);
    form.resetFields();
  };

  // Filter groups based on search text
  const filteredGroups = groups.filter(group => {
    if (!searchText) return true;
    const searchLower = searchText.toLowerCase();
    return (
      group.id.toLowerCase().includes(searchLower) ||
      group.name?.toLowerCase().includes(searchLower) ||
      group.members?.some(member => member.toLowerCase().includes(searchLower))
    );
  });

  const columns = [
    {
      title: 'グループID',
      dataIndex: 'id',
      key: 'id',
    },
    {
      title: 'グループ名',
      dataIndex: 'name',
      key: 'name',
      render: (name: string) => name && name.trim() !== '' ? name : '-',
    },
    {
      title: 'メンバー',
      dataIndex: 'members',
      key: 'members',
      render: (members: string[]) => {
        if (!members || members.length === 0) {
          return '-';
        }
        return (
          <Space wrap>
            {members.slice(0, 3).map(member => (
              <Tag key={member} icon={<UserOutlined />} color="green">
                {member}
              </Tag>
            ))}
            {members.length > 3 && (
              <Tag color="default">+{members.length - 3} more</Tag>
            )}
          </Space>
        );
      },
    },
    {
      title: 'アクション',
      key: 'actions',
      width: 150,
      render: (_: any, record: Group) => (
        <Space>
          <Button 
            icon={<EditOutlined />} 
            size="small"
            onClick={() => handleEdit(record)}
          >
            編集
          </Button>
          <Popconfirm
            title="このグループを削除しますか？"
            onConfirm={() => handleDelete(record.id)}
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
        </Space>
      ),
    },
  ];

  return (
    <Card>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>
          <TeamOutlined /> グループ管理
        </h2>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setModalVisible(true)}
        >
          新規作成
        </Button>
      </div>

      <Input.Search
        placeholder="グループを検索 (ID、名前、メンバー)"
        allowClear
        value={searchText}
        onChange={(e) => setSearchText(e.target.value)}
        onSearch={(value) => setSearchText(value)}
        style={{ marginBottom: 16 }}
        className="ant-input-search"
      />

      <Table
        columns={columns}
        dataSource={filteredGroups}
        rowKey="id"
        loading={loading}
        pagination={{ pageSize: 20 }}
      />

      <Modal
        title={editingGroup ? 'グループ編集' : '新規グループ作成'}
        open={modalVisible}
        onCancel={handleCancel}
        footer={null}
        width={600}
        maskClosable={false}
      >
        <Form
          form={form}
          onFinish={handleSubmit}
          layout="vertical"
        >
          <Form.Item
            name="id"
            label="グループID"
            rules={[
              { required: true, message: 'グループIDを入力してください' },
              { pattern: /^[a-zA-Z0-9_-]+$/, message: '英数字、アンダースコア、ハイフンのみ使用可能です' }
            ]}
          >
            <Input 
              placeholder="グループIDを入力"
              disabled={!!editingGroup}
            />
          </Form.Item>

          <Form.Item
            name="name"
            label="グループ名"
            rules={[{ required: true, message: 'グループ名を入力してください' }]}
          >
            <Input placeholder="グループ名を入力" />
          </Form.Item>

          <Form.Item
            name="members"
            label="メンバー"
          >
            <Select
              mode="multiple"
              placeholder="メンバーを選択"
              options={users.map(user => {
                // nameがnullまたは空の場合、firstName + lastNameを使用
                let displayName = user.name;
                if (!displayName || displayName.trim() === '') {
                  const fullName = [user.firstName, user.lastName]
                    .filter(n => n && n.trim() !== '')
                    .join(' ');
                  displayName = fullName || user.id;
                }
                return {
                  label: `${displayName} (${user.id})`,
                  value: user.id
                };
              })}
            />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                {editingGroup ? '更新' : '作成'}
              </Button>
              <Button onClick={handleCancel}>
                キャンセル
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
};

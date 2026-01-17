/**
 * GroupManagement Component for NemakiWare React UI
 *
 * Group management component with nested group (parent-child) support:
 * - Group list display with Ant Design Table
 * - User members and group members (child groups) management
 * - Circular reference detection to prevent infinite loops
 * - Large member display (100+) with expandable modal
 * - Comprehensive error handling with HTTP status code-based messaging
 */

import React, { useState, useEffect, useMemo } from 'react';
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
  Select,
  Divider,
  List,
  Typography
} from 'antd';
import {
  TeamOutlined,
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  UserOutlined,
  UsergroupAddOutlined,
  ExclamationCircleOutlined
} from '@ant-design/icons';
import { CMISService } from '../../services/cmis';
import { Group, User } from '../../types/cmis';
import { useTranslation } from 'react-i18next';

interface GroupManagementProps {
  repositoryId: string;
}

import { useAuth } from '../../contexts/AuthContext';

/**
 * Detect circular reference in group hierarchy
 * @param groupId The group being edited
 * @param selectedGroupMembers The groups selected as members
 * @param allGroups All groups in the system
 * @returns Array of group IDs that would cause circular reference, or empty if safe
 */
function detectCircularReference(
  groupId: string,
  selectedGroupMembers: string[],
  allGroups: Group[]
): string[] {
  const circularGroups: string[] = [];

  // Build a map for quick lookup
  const groupMap = new Map<string, Group>();
  allGroups.forEach(g => groupMap.set(g.id, g));

  // For each selected group member, check if adding it would create a cycle
  for (const memberId of selectedGroupMembers) {
    // Check if the member group contains the current group (directly or indirectly)
    const visited = new Set<string>();
    const queue = [memberId];

    while (queue.length > 0) {
      const currentId = queue.shift()!;
      if (visited.has(currentId)) continue;
      visited.add(currentId);

      const currentGroup = groupMap.get(currentId);
      if (!currentGroup) continue;

      // Check if any of the current group's group members is the group being edited
      // This means: if we add 'memberId' to 'groupId', and memberId already contains groupId
      // (directly or through a chain), we have a cycle
      for (const childGroupId of currentGroup.groupMembers || []) {
        if (childGroupId === groupId) {
          circularGroups.push(memberId);
          break;
        }
        if (!visited.has(childGroupId)) {
          queue.push(childGroupId);
        }
      }
    }
  }

  return circularGroups;
}

export const GroupManagement: React.FC<GroupManagementProps> = ({ repositoryId }) => {
  const [groups, setGroups] = useState<Group[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingGroup, setEditingGroup] = useState<Group | null>(null);
  const [searchText, setSearchText] = useState('');
  const [membersModalVisible, setMembersModalVisible] = useState(false);
  const [selectedGroupForMembers, setSelectedGroupForMembers] = useState<Group | null>(null);
  const [form] = Form.useForm();
  const { t } = useTranslation();

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
      let errorMessage = t('groupManagement.messages.loadError');

      if (error.status === 500) {
        errorMessage = t('common.errors.serverError');
        if (error.details) {
          errorMessage += `\n${t('common.errors.details', { details: error.details })}`;
        }
      } else if (error.status === 401) {
        errorMessage = t('common.errors.authError');
      } else if (error.status === 403) {
        errorMessage = t('groupManagement.messages.permissionError');
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

  // Precompute which groups would cause circular references when editing
  const circularGroupIds = useMemo(() => {
    if (!editingGroup) {
      return new Set<string>();
    }

    const circularIds = new Set<string>();
    for (const group of groups) {
      if (group.id === editingGroup.id) continue;
      const isCircular = detectCircularReference(editingGroup.id, [group.id], groups).length > 0;
      if (isCircular) {
        circularIds.add(group.id);
      }
    }
    return circularIds;
  }, [groups, editingGroup]);

  // Get available groups for selection (exclude self and disable circular references)
  const getAvailableGroupsForSelection = useMemo(() => {
    if (!editingGroup) {
      // Creating new group - all groups available
      return groups.map(g => ({
        label: `${g.name || g.id} (${g.id})`,
        value: g.id
      }));
    }

    // Editing existing group - exclude self and mark circular as disabled
    return groups
      .filter(g => g.id !== editingGroup.id)
      .map(g => ({
        label: `${g.name || g.id} (${g.id})`,
        value: g.id,
        disabled: circularGroupIds.has(g.id)
      }));
  }, [groups, editingGroup, circularGroupIds]);

  const handleSubmit = async (values: any) => {
    try {
      // Check for circular references before submitting
      if (editingGroup && values.groupMembers && values.groupMembers.length > 0) {
        const circularGroups = detectCircularReference(
          editingGroup.id,
          values.groupMembers,
          groups
        );

        if (circularGroups.length > 0) {
          const groupNames = circularGroups
            .map(id => {
              const g = groups.find(g => g.id === id);
              return g ? `${g.name || g.id}` : id;
            })
            .join(', ');
          message.error(t('groupManagement.validation.circularReference', { groups: groupNames }));
          return;
        }
      }

      // Prepare data with separate user/group members
      const submitData = {
        ...values,
        userMembers: values.userMembers || [],
        groupMembers: values.groupMembers || []
      };

      if (editingGroup) {
        await cmisService.updateGroup(repositoryId, editingGroup.id, submitData);
        message.success(t('groupManagement.messages.updateSuccess'));
      } else {
        await cmisService.createGroup(repositoryId, submitData);
        message.success(t('groupManagement.messages.createSuccess'));
      }

      setModalVisible(false);
      setEditingGroup(null);
      form.resetFields();
      loadGroups();
    } catch (error: any) {
      let errorMessage = editingGroup ? t('groupManagement.messages.updateError') : t('groupManagement.messages.createError');

      if (error.status === 500) {
        errorMessage = t('common.errors.serverError');
        if (error.details) {
          errorMessage += `\n${t('common.errors.details', { details: error.details })}`;
        }
      } else if (error.status === 401) {
        errorMessage = t('common.errors.authError');
      } else if (error.status === 403) {
        errorMessage = t('common.errors.permissionError');
      } else if (error.message) {
        errorMessage = error.message;
      }

      message.error(errorMessage);
    }
  };

  const handleEdit = (group: Group) => {
    setEditingGroup(group);
    form.setFieldsValue({
      ...group,
      userMembers: group.userMembers || [],
      groupMembers: group.groupMembers || []
    });
    setModalVisible(true);
  };

  const handleDelete = async (groupId: string) => {
    try {
      await cmisService.deleteGroup(repositoryId, groupId);
      message.success(t('groupManagement.messages.deleteSuccess'));
      loadGroups();
    } catch (error: any) {
      let errorMessage = t('groupManagement.messages.deleteError');

      if (error.status === 500) {
        errorMessage = t('common.errors.serverError');
        if (error.details) {
          errorMessage += `\n${t('common.errors.details', { details: error.details })}`;
        }
      } else if (error.status === 401) {
        errorMessage = t('common.errors.authError');
      } else if (error.status === 403) {
        errorMessage = t('groupManagement.messages.deletePermissionError');
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

  const showMembersModal = (group: Group) => {
    setSelectedGroupForMembers(group);
    setMembersModalVisible(true);
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

  // Render member tags with truncation and expandable modal
  const renderMembers = (group: Group) => {
    const userMembers = group.userMembers || [];
    const groupMembers = group.groupMembers || [];
    const totalCount = userMembers.length + groupMembers.length;

    if (totalCount === 0) {
      return '-';
    }

    const DISPLAY_LIMIT = 3;
    const displayUsers = userMembers.slice(0, DISPLAY_LIMIT);
    const remainingUserSlots = Math.max(0, DISPLAY_LIMIT - displayUsers.length);
    const displayGroups = groupMembers.slice(0, remainingUserSlots);
    const displayedCount = displayUsers.length + displayGroups.length;
    const hiddenCount = totalCount - displayedCount;

    return (
      <Space wrap>
        {displayUsers.map(member => (
          <Tag key={`user-${member}`} icon={<UserOutlined />} color="green">
            {member}
          </Tag>
        ))}
        {displayGroups.map(member => (
          <Tag key={`group-${member}`} icon={<TeamOutlined />} color="blue">
            {member}
          </Tag>
        ))}
        {hiddenCount > 0 && (
          <Tag
            color="default"
            style={{ cursor: 'pointer' }}
            onClick={() => showMembersModal(group)}
          >
            +{hiddenCount} {t('common.more')}
          </Tag>
        )}
      </Space>
    );
  };

  const columns = [
    {
      title: t('groupManagement.columns.groupId'),
      dataIndex: 'id',
      key: 'id',
    },
    {
      title: t('groupManagement.columns.groupName'),
      dataIndex: 'name',
      key: 'name',
      render: (name: string) => name && name.trim() !== '' ? name : '-',
    },
    {
      title: t('groupManagement.columns.members'),
      key: 'members',
      render: (_: any, record: Group) => renderMembers(record),
    },
    {
      title: t('common.actions'),
      key: 'actions',
      width: 150,
      render: (_: any, record: Group) => (
        <Space>
          <Button
            icon={<EditOutlined />}
            size="small"
            onClick={() => handleEdit(record)}
          >
            {t('common.edit')}
          </Button>
          <Popconfirm
            title={t('groupManagement.confirmDelete')}
            onConfirm={() => handleDelete(record.id)}
            okText={t('common.yes')}
            cancelText={t('common.no')}
          >
            <Button
              icon={<DeleteOutlined />}
              size="small"
              danger
            >
              {t('common.delete')}
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  // Get user display name with fallback
  const getUserDisplayName = (user: User) => {
    let displayName = user.name;
    if (!displayName || displayName.trim() === '') {
      const fullName = [user.firstName, user.lastName]
        .filter(n => n && n.trim() !== '')
        .join(' ');
      displayName = fullName || user.id;
    }
    return displayName;
  };

  return (
    <Card>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>
          <TeamOutlined /> {t('groupManagement.title')}
        </h2>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setModalVisible(true)}
        >
          {t('common.create')}
        </Button>
      </div>

      <Input.Search
        placeholder={t('groupManagement.searchPlaceholder')}
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

      {/* Create/Edit Group Modal */}
      <Modal
        title={editingGroup ? t('groupManagement.editGroup') : t('groupManagement.createGroup')}
        open={modalVisible}
        onCancel={handleCancel}
        footer={null}
        width={700}
        maskClosable={false}
      >
        <Form
          form={form}
          onFinish={handleSubmit}
          layout="vertical"
        >
          <Form.Item
            name="id"
            label={t('groupManagement.columns.groupId')}
            rules={[
              { required: true, message: t('groupManagement.validation.groupIdRequired') },
              { pattern: /^[a-zA-Z0-9_-]+$/, message: t('common.validation.alphanumericOnly') }
            ]}
          >
            <Input
              placeholder={t('groupManagement.placeholders.groupId')}
              disabled={!!editingGroup}
            />
          </Form.Item>

          <Form.Item
            name="name"
            label={t('groupManagement.columns.groupName')}
            rules={[{ required: true, message: t('groupManagement.validation.groupNameRequired') }]}
          >
            <Input placeholder={t('groupManagement.placeholders.groupName')} />
          </Form.Item>

          <Form.Item
            name="description"
            label={t('groupManagement.description')}
          >
            <Input.TextArea
              placeholder={t('groupManagement.placeholders.description')}
              rows={3}
              maxLength={1000}
              showCount
            />
          </Form.Item>

          <Divider orientation="left">{t('groupManagement.memberSettings')}</Divider>

          <Form.Item
            name="userMembers"
            label={
              <Space>
                <UserOutlined />
                {t('groupManagement.userMembers')}
              </Space>
            }
            tooltip={t('groupManagement.userMembersTooltip')}
          >
            <Select
              mode="multiple"
              placeholder={t('groupManagement.placeholders.userMembers')}
              showSearch
              filterOption={(input, option) =>
                (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
              }
              options={users.map(user => ({
                label: `${getUserDisplayName(user)} (${user.id})`,
                value: user.id
              }))}
            />
          </Form.Item>

          <Form.Item
            name="groupMembers"
            label={
              <Space>
                <TeamOutlined />
                {t('groupManagement.groupMembers')}
              </Space>
            }
            tooltip={t('groupManagement.groupMembersTooltip')}
            extra={
              editingGroup && (
                <Typography.Text type="warning">
                  <ExclamationCircleOutlined /> {t('groupManagement.circularWarning')}
                </Typography.Text>
              )
            }
          >
            <Select
              mode="multiple"
              placeholder={t('groupManagement.placeholders.groupMembers')}
              showSearch
              filterOption={(input, option) =>
                (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
              }
              options={getAvailableGroupsForSelection}
            />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                {editingGroup ? t('common.update') : t('common.create')}
              </Button>
              <Button onClick={handleCancel}>
                {t('common.cancel')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      {/* Members Detail Modal (for viewing 100+ members) */}
      <Modal
        title={
          <Space>
            <TeamOutlined />
            {t('groupManagement.membersDetail', { groupName: selectedGroupForMembers?.name || selectedGroupForMembers?.id })}
          </Space>
        }
        open={membersModalVisible}
        onCancel={() => setMembersModalVisible(false)}
        footer={[
          <Button key="close" onClick={() => setMembersModalVisible(false)}>
            {t('common.close')}
          </Button>
        ]}
        width={600}
      >
        {selectedGroupForMembers && (
          <>
            {/* User Members Section */}
            <Typography.Title level={5}>
              <UserOutlined /> {t('groupManagement.userMembers')} ({selectedGroupForMembers.userMembers?.length || 0})
            </Typography.Title>
            {(selectedGroupForMembers.userMembers?.length || 0) > 0 ? (
              <List
                size="small"
                dataSource={selectedGroupForMembers.userMembers}
                style={{ maxHeight: 200, overflow: 'auto', marginBottom: 16 }}
                renderItem={(userId) => {
                  const user = users.find(u => u.id === userId);
                  return (
                    <List.Item>
                      <Tag icon={<UserOutlined />} color="green">
                        {user ? `${getUserDisplayName(user)} (${userId})` : userId}
                      </Tag>
                    </List.Item>
                  );
                }}
              />
            ) : (
              <Typography.Text type="secondary">{t('groupManagement.noUserMembers')}</Typography.Text>
            )}

            <Divider />

            {/* Group Members Section */}
            <Typography.Title level={5}>
              <TeamOutlined /> {t('groupManagement.groupMembers')} ({selectedGroupForMembers.groupMembers?.length || 0})
            </Typography.Title>
            {(selectedGroupForMembers.groupMembers?.length || 0) > 0 ? (
              <List
                size="small"
                dataSource={selectedGroupForMembers.groupMembers}
                style={{ maxHeight: 200, overflow: 'auto' }}
                renderItem={(groupId) => {
                  const group = groups.find(g => g.id === groupId);
                  return (
                    <List.Item>
                      <Tag icon={<TeamOutlined />} color="blue">
                        {group ? `${group.name || groupId} (${groupId})` : groupId}
                      </Tag>
                    </List.Item>
                  );
                }}
              />
            ) : (
              <Typography.Text type="secondary">{t('groupManagement.noGroupMembers')}</Typography.Text>
            )}
          </>
        )}
      </Modal>
    </Card>
  );
};

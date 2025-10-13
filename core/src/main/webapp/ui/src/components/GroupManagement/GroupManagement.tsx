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
  Select,
  Transfer
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
      console.error('GroupManagement: loadGroups error:', error);
      
      let errorMessage = 'グループの読み込みに失敗しました';
      
      if (error.status === 500) {
        errorMessage = 'サーバー側でエラーが発生しています';
        if (error.details) {
          errorMessage += `\n詳細: ${error.details}`;
        }
        console.error('Server error details:', {
          message: error.message,
          details: error.details,
          status: error.status
        });
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
      console.error('GroupManagement: loadUsers error:', error);
      // ユーザー読み込み失敗はグループ管理では警告レベル
      console.warn('ユーザーの読み込みに失敗しました。メンバー選択が制限される可能性があります。');
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
      console.error('GroupManagement: handleSubmit error:', error);
      
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
      console.error('GroupManagement: handleDelete error:', error);
      
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
    },
    {
      title: 'メンバー',
      dataIndex: 'members',
      key: 'members',
      render: (members: string[]) => (
        <Space wrap>
          {members?.slice(0, 3).map(member => (
            <Tag key={member} icon={<UserOutlined />} color="green">
              {member}
            </Tag>
          ))}
          {members?.length > 3 && (
            <Tag color="default">+{members.length - 3} more</Tag>
          )}
        </Space>
      ),
    },
    {
      title: 'アクション',
      key: 'actions',
      width: 150,
      render: (_, record: Group) => (
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
          新規グループ
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
              options={users.map(user => ({
                label: `${user.name} (${user.id})`,
                value: user.id
              }))}
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

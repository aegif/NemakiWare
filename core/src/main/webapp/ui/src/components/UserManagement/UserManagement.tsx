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
  UserOutlined,
  PlusOutlined,
  EditOutlined,
  DeleteOutlined
} from '@ant-design/icons';
import { CMISService } from '../../services/cmis';
import { User, Group } from '../../types/cmis';

interface UserManagementProps {
  repositoryId: string;
}

import { useAuth } from '../../contexts/AuthContext';
export const UserManagement: React.FC<UserManagementProps> = ({ repositoryId }) => {
  const [users, setUsers] = useState<User[]>([]);
  const [groups, setGroups] = useState<Group[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingUser, setEditingUser] = useState<User | null>(null);
  const [searchText, setSearchText] = useState('');
  const [form] = Form.useForm();

  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

  useEffect(() => {
    loadUsers();
    loadGroups();
  }, [repositoryId]);

  const loadUsers = async () => {
    setLoading(true);
    try {
      const userList = await cmisService.getUsers(repositoryId);
      setUsers(userList);
    } catch (error: any) {
      console.error('UserManagement: loadUsers error:', error);

      // エラーの詳細情報を構築
      let errorMessage = 'ユーザーの読み込みに失敗しました';

      if (error.status === 500) {
        // サーバー側でHTTP 500が返された場合（根本的な問題）
        errorMessage = 'サーバー側でエラーが発生しています';
        if (error.details) {
          errorMessage += `\n詳細: ${error.details}`;
        }
        // 開発者向けに詳細情報をコンソールに出力
        console.error('Server error details:', {
          message: error.message,
          details: error.details,
          status: error.status
        });
      } else if (error.status === 401) {
        errorMessage = '認証エラー: ログインし直してください';
      } else if (error.status === 403) {
        errorMessage = '権限エラー: ユーザー管理の権限がありません';
      } else if (error.message) {
        errorMessage = error.message;
      }

      message.error(errorMessage);
    } finally {
      setLoading(false);
    }
  };

  const loadGroups = async () => {
    try {
      const groupList = await cmisService.getGroups(repositoryId);
      setGroups(groupList);
    } catch (error: any) {
      console.error('UserManagement: loadGroups error:', error);
      // グループ読み込み失敗はユーザー管理では警告レベル
      console.warn('グループの読み込みに失敗しました。グループ選択が制限される可能性があります。');
    }
  };

  const handleSubmit = async (values: any) => {
    try {
      if (editingUser) {
        await cmisService.updateUser(repositoryId, editingUser.id, values);
        message.success('ユーザーを更新しました');
      } else {
        await cmisService.createUser(repositoryId, values);
        message.success('ユーザーを作成しました');
      }
      
      setModalVisible(false);
      setEditingUser(null);
      form.resetFields();
      loadUsers();
    } catch (error: any) {
      console.error('UserManagement: handleSubmit error:', error);
      
      let errorMessage = editingUser ? 'ユーザーの更新に失敗しました' : 'ユーザーの作成に失敗しました';
      
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

  const handleEdit = (user: User) => {
    setEditingUser(user);
    form.setFieldsValue(user);
    setModalVisible(true);
  };

  const handleDelete = async (userId: string) => {
    try {
      await cmisService.deleteUser(repositoryId, userId);
      message.success('ユーザーを削除しました');
      loadUsers();
    } catch (error: any) {
      console.error('UserManagement: handleDelete error:', error);
      
      let errorMessage = 'ユーザーの削除に失敗しました';
      
      if (error.status === 500) {
        errorMessage = 'サーバー側でエラーが発生しています';
        if (error.details) {
          errorMessage += `\n詳細: ${error.details}`;
        }
      } else if (error.status === 401) {
        errorMessage = '認証エラー: ログインし直してください';
      } else if (error.status === 403) {
        errorMessage = '権限エラー: ユーザー削除の権限がありません';
      } else if (error.message) {
        errorMessage = error.message;
      }
      
      message.error(errorMessage);
    }
  };

  const handleCancel = () => {
    setModalVisible(false);
    setEditingUser(null);
    form.resetFields();
  };

  // Filter users based on search text (includes firstName and lastName)
  const filteredUsers = users.filter(user => {
    if (!searchText) return true;
    const searchLower = searchText.toLowerCase();
    return (
      user.id.toLowerCase().includes(searchLower) ||
      user.name?.toLowerCase().includes(searchLower) ||
      user.firstName?.toLowerCase().includes(searchLower) ||
      user.lastName?.toLowerCase().includes(searchLower) ||
      user.email?.toLowerCase().includes(searchLower)
    );
  });

  const columns = [
    {
      title: 'ユーザーID',
      dataIndex: 'id',
      key: 'id',
    },
    {
      title: 'ユーザー名',
      dataIndex: 'name',
      key: 'name',
      render: (name: string, record: User) => {
        // nameがnullまたは空の場合、firstName + lastNameを表示
        if (!name || name.trim() === '') {
          const fullName = [record.firstName, record.lastName]
            .filter(n => n && n.trim() !== '')
            .join(' ');
          return fullName || '-';
        }
        return name;
      },
    },
    {
      title: '名',
      dataIndex: 'firstName',
      key: 'firstName',
      render: (firstName: string) => firstName && firstName.trim() !== '' ? firstName : '-',
    },
    {
      title: '姓',
      dataIndex: 'lastName',
      key: 'lastName',
      render: (lastName: string) => lastName && lastName.trim() !== '' ? lastName : '-',
    },
    {
      title: 'メールアドレス',
      dataIndex: 'email',
      key: 'email',
      render: (email: string) => email && email.trim() !== '' ? email : '-',
    },
    {
      title: '所属グループ',
      dataIndex: 'groups',
      key: 'groups',
      render: (groups: string[]) => {
        if (!groups || groups.length === 0) {
          return '-';
        }
        return (
          <Space wrap>
            {groups.map(group => (
              <Tag key={group} color="blue">{group}</Tag>
            ))}
          </Space>
        );
      },
    },
    {
      title: 'アクション',
      key: 'actions',
      width: 150,
      render: (_: any, record: User) => (
        <Space>
          <Button 
            icon={<EditOutlined />} 
            size="small"
            onClick={() => handleEdit(record)}
          >
            編集
          </Button>
          <Popconfirm
            title="このユーザーを削除しますか？"
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
          <UserOutlined /> ユーザー管理
        </h2>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setModalVisible(true)}
        >
          新規ユーザー
        </Button>
      </div>

      <Input.Search
        placeholder="ユーザーを検索 (ID、名前、名、姓、メールアドレス)"
        allowClear
        value={searchText}
        onChange={(e) => setSearchText(e.target.value)}
        onSearch={(value) => setSearchText(value)}
        style={{ marginBottom: 16 }}
        className="ant-input-search"
      />

      <Table
        columns={columns}
        dataSource={filteredUsers}
        rowKey="id"
        loading={loading}
        pagination={{ pageSize: 20 }}
      />

      <Modal
        title={editingUser ? 'ユーザー編集' : '新規ユーザー作成'}
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
            label="ユーザーID"
            rules={[
              { required: true, message: 'ユーザーIDを入力してください' },
              { pattern: /^[a-zA-Z0-9_-]+$/, message: '英数字、アンダースコア、ハイフンのみ使用可能です' }
            ]}
          >
            <Input 
              placeholder="ユーザーIDを入力"
              disabled={!!editingUser}
            />
          </Form.Item>

          <Form.Item
            name="name"
            label="ユーザー名"
            rules={[{ required: true, message: 'ユーザー名を入力してください' }]}
          >
            <Input placeholder="ユーザー名を入力" />
          </Form.Item>

          <Form.Item
            name="firstName"
            label="名"
          >
            <Input placeholder="名を入力" />
          </Form.Item>

          <Form.Item
            name="lastName"
            label="姓"
          >
            <Input placeholder="姓を入力" />
          </Form.Item>

          <Form.Item
            name="email"
            label="メールアドレス"
            rules={[
              { type: 'email', message: '正しいメールアドレスを入力してください' }
            ]}
          >
            <Input placeholder="メールアドレスを入力" />
          </Form.Item>

          {!editingUser && (
            <Form.Item
              name="password"
              label="パスワード"
              rules={[
                { required: true, message: 'パスワードを入力してください' },
                { min: 6, message: 'パスワードは6文字以上で入力してください' }
              ]}
            >
              <Input.Password placeholder="パスワードを入力" />
            </Form.Item>
          )}

          <Form.Item
            name="groups"
            label="所属グループ"
          >
            <Select
              mode="multiple"
              placeholder="グループを選択"
              options={groups.map(group => ({
                label: group.name || group.id,
                value: group.id
              }))}
            />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                {editingUser ? '更新' : '作成'}
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

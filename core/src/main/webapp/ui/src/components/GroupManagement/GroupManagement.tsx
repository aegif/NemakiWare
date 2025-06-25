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

export const GroupManagement: React.FC<GroupManagementProps> = ({ repositoryId }) => {
  const [groups, setGroups] = useState<Group[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingGroup, setEditingGroup] = useState<Group | null>(null);
  const [form] = Form.useForm();

  const cmisService = new CMISService();

  useEffect(() => {
    loadGroups();
    loadUsers();
  }, [repositoryId]);

  const loadGroups = async () => {
    setLoading(true);
    try {
      const groupList = await cmisService.getGroups(repositoryId);
      setGroups(groupList);
    } catch (error) {
      message.error('グループの読み込みに失敗しました');
    } finally {
      setLoading(false);
    }
  };

  const loadUsers = async () => {
    try {
      const userList = await cmisService.getUsers(repositoryId);
      setUsers(userList);
    } catch (error) {
      console.error('ユーザーの読み込みに失敗しました');
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
    } catch (error) {
      message.error(editingGroup ? 'グループの更新に失敗しました' : 'グループの作成に失敗しました');
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
    } catch (error) {
      message.error('グループの削除に失敗しました');
    }
  };

  const handleCancel = () => {
    setModalVisible(false);
    setEditingGroup(null);
    form.resetFields();
  };

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

      <Table
        columns={columns}
        dataSource={groups}
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

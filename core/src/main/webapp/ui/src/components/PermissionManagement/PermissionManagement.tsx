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
      const [obj, aclData, userList, groupList] = await Promise.all([
        cmisService.getObject(repositoryId, objectId),
        cmisService.getACL(repositoryId, objectId),
        cmisService.getUsers(repositoryId),
        cmisService.getGroups(repositoryId)
      ]);
      
      setObject(obj);
      setACL(aclData);
      setUsers(userList);
      setGroups(groupList);
    } catch (error) {
      message.error('データの読み込みに失敗しました');
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
      render: (_, record: Permission) => (
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
          
          <Button 
            type="primary" 
            icon={<PlusOutlined />}
            onClick={() => setModalVisible(true)}
          >
            権限を追加
          </Button>
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

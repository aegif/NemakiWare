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
  Select,
  Switch,
  InputNumber,
  Tabs
} from 'antd';
import { 
  FileOutlined, 
  PlusOutlined, 
  EditOutlined, 
  DeleteOutlined,
  SettingOutlined
} from '@ant-design/icons';
import { CMISService } from '../../services/cmis';
import { TypeDefinition, PropertyDefinition } from '../../types/cmis';

interface TypeManagementProps {
  repositoryId: string;
}

export const TypeManagement: React.FC<TypeManagementProps> = ({ repositoryId }) => {
  const [types, setTypes] = useState<TypeDefinition[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingType, setEditingType] = useState<TypeDefinition | null>(null);
  const [form] = Form.useForm();

  const cmisService = new CMISService();

  useEffect(() => {
    loadTypes();
  }, [repositoryId]);

  const loadTypes = async () => {
    setLoading(true);
    try {
      const typeList = await cmisService.getTypes(repositoryId);
      setTypes(typeList);
    } catch (error) {
      message.error('タイプの読み込みに失敗しました');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (values: any) => {
    try {
      if (editingType) {
        await cmisService.updateType(repositoryId, editingType.id, values);
        message.success('タイプを更新しました');
      } else {
        await cmisService.createType(repositoryId, values);
        message.success('タイプを作成しました');
      }
      
      setModalVisible(false);
      setEditingType(null);
      form.resetFields();
      loadTypes();
    } catch (error) {
      message.error(editingType ? 'タイプの更新に失敗しました' : 'タイプの作成に失敗しました');
    }
  };

  const handleEdit = (type: TypeDefinition) => {
    setEditingType(type);
    form.setFieldsValue(type);
    setModalVisible(true);
  };

  const handleDelete = async (typeId: string) => {
    try {
      await cmisService.deleteType(repositoryId, typeId);
      message.success('タイプを削除しました');
      loadTypes();
    } catch (error) {
      message.error('タイプの削除に失敗しました');
    }
  };

  const handleCancel = () => {
    setModalVisible(false);
    setEditingType(null);
    form.resetFields();
  };

  const columns = [
    {
      title: 'タイプID',
      dataIndex: 'id',
      key: 'id',
    },
    {
      title: '表示名',
      dataIndex: 'displayName',
      key: 'displayName',
    },
    {
      title: '説明',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
    },
    {
      title: 'ベースタイプ',
      dataIndex: 'baseTypeId',
      key: 'baseTypeId',
    },
    {
      title: '親タイプ',
      dataIndex: 'parentTypeId',
      key: 'parentTypeId',
    },
    {
      title: 'プロパティ数',
      dataIndex: 'propertyDefinitions',
      key: 'propertyCount',
      render: (propertyDefinitions: Record<string, PropertyDefinition>) => 
        Object.keys(propertyDefinitions || {}).length,
    },
    {
      title: 'アクション',
      key: 'actions',
      width: 200,
      render: (_, record: TypeDefinition) => (
        <Space>
          <Button
            icon={<EditOutlined />}
            size="small"
            onClick={() => handleEdit(record)}
            disabled={!record.deletable && record.id.startsWith('cmis:')}
            title={!record.deletable && record.id.startsWith('cmis:') ? '標準CMISタイプは編集できません' : ''}
          >
            編集
          </Button>
          {record.deletable !== false && !record.id.startsWith('cmis:') ? (
            <Popconfirm
              title="このタイプを削除しますか？"
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
          ) : (
            <Button
              icon={<DeleteOutlined />}
              size="small"
              disabled
              title="標準CMISタイプは削除できません"
            >
              削除
            </Button>
          )}
        </Space>
      ),
    },
  ];

  const PropertyDefinitionForm = () => (
    <Form.List name="properties">
      {(fields, { add, remove }) => (
        <>
          {fields.map(({ key, name, ...restField }) => (
            <Card key={key} size="small" style={{ marginBottom: 8 }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                <Form.Item
                  {...restField}
                  name={[name, 'id']}
                  label="プロパティID"
                  rules={[{ required: true, message: 'プロパティIDを入力してください' }]}
                >
                  <Input placeholder="プロパティID" />
                </Form.Item>
                
                <Form.Item
                  {...restField}
                  name={[name, 'displayName']}
                  label="表示名"
                  rules={[{ required: true, message: '表示名を入力してください' }]}
                >
                  <Input placeholder="表示名" />
                </Form.Item>
                
                <Form.Item
                  {...restField}
                  name={[name, 'propertyType']}
                  label="データ型"
                  rules={[{ required: true, message: 'データ型を選択してください' }]}
                >
                  <Select placeholder="データ型を選択">
                    <Select.Option value="string">文字列</Select.Option>
                    <Select.Option value="integer">整数</Select.Option>
                    <Select.Option value="decimal">小数</Select.Option>
                    <Select.Option value="boolean">真偽値</Select.Option>
                    <Select.Option value="datetime">日時</Select.Option>
                  </Select>
                </Form.Item>
                
                <Form.Item
                  {...restField}
                  name={[name, 'cardinality']}
                  label="多重度"
                  rules={[{ required: true, message: '多重度を選択してください' }]}
                >
                  <Select placeholder="多重度を選択">
                    <Select.Option value="single">単一</Select.Option>
                    <Select.Option value="multi">複数</Select.Option>
                  </Select>
                </Form.Item>
              </div>
              
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16 }}>
                <Form.Item
                  {...restField}
                  name={[name, 'required']}
                  label="必須"
                  valuePropName="checked"
                >
                  <Switch />
                </Form.Item>
                
                <Form.Item
                  {...restField}
                  name={[name, 'queryable']}
                  label="検索可能"
                  valuePropName="checked"
                >
                  <Switch />
                </Form.Item>
                
                <Form.Item
                  {...restField}
                  name={[name, 'updatable']}
                  label="更新可能"
                  valuePropName="checked"
                >
                  <Switch />
                </Form.Item>
                
                <Button 
                  type="link" 
                  danger 
                  onClick={() => remove(name)}
                >
                  削除
                </Button>
              </div>
              
              <Form.Item
                {...restField}
                name={[name, 'description']}
                label="説明"
              >
                <Input.TextArea rows={2} placeholder="プロパティの説明" />
              </Form.Item>
            </Card>
          ))}
          
          <Button 
            type="dashed" 
            onClick={() => add()} 
            block 
            icon={<PlusOutlined />}
          >
            プロパティを追加
          </Button>
        </>
      )}
    </Form.List>
  );

  const tabItems = [
    {
      key: 'basic',
      label: '基本情報',
      children: (
        <>
          <Form.Item
            name="id"
            label="タイプID"
            rules={[{ required: true, message: 'タイプIDを入力してください' }]}
          >
            <Input 
              placeholder="タイプIDを入力"
              disabled={!!editingType}
            />
          </Form.Item>

          <Form.Item
            name="displayName"
            label="表示名"
            rules={[{ required: true, message: '表示名を入力してください' }]}
          >
            <Input placeholder="表示名を入力" />
          </Form.Item>

          <Form.Item
            name="description"
            label="説明"
          >
            <Input.TextArea rows={3} placeholder="タイプの説明を入力" />
          </Form.Item>

          <Form.Item
            name="baseTypeId"
            label="ベースタイプ"
            rules={[{ required: true, message: 'ベースタイプを選択してください' }]}
          >
            <Select placeholder="ベースタイプを選択">
              <Select.Option value="cmis:document">ドキュメント</Select.Option>
              <Select.Option value="cmis:folder">フォルダ</Select.Option>
            </Select>
          </Form.Item>

          <Form.Item
            name="parentTypeId"
            label="親タイプ"
          >
            <Select placeholder="親タイプを選択" allowClear>
              {types.map(type => (
                <Select.Option key={type.id} value={type.id}>
                  {type.displayName}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
            <Form.Item
              name="creatable"
              label="作成可能"
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>

            <Form.Item
              name="fileable"
              label="ファイル可能"
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>

            <Form.Item
              name="queryable"
              label="検索可能"
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
          </div>
        </>
      ),
    },
    {
      key: 'properties',
      label: 'プロパティ定義',
      children: <PropertyDefinitionForm />,
    },
  ];

  return (
    <Card>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>
          <FileOutlined /> タイプ管理
        </h2>
        <Button 
          type="primary" 
          icon={<PlusOutlined />}
          onClick={() => setModalVisible(true)}
        >
          新規タイプ
        </Button>
      </div>

      <Table
        columns={columns}
        dataSource={types}
        rowKey="id"
        loading={loading}
        pagination={{ pageSize: 20 }}
      />

      <Modal
        title={editingType ? 'タイプ編集' : '新規タイプ作成'}
        open={modalVisible}
        onCancel={handleCancel}
        footer={null}
        width={800}
      >
        <Form
          form={form}
          onFinish={handleSubmit}
          layout="vertical"
        >
          <Tabs items={tabItems} />
          
          <Form.Item style={{ marginTop: 16 }}>
            <Space>
              <Button type="primary" htmlType="submit">
                {editingType ? '更新' : '作成'}
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

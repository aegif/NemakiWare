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
  Tabs,
  Upload,
  Alert
} from 'antd';
import {
  FileOutlined,
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ImportOutlined
} from '@ant-design/icons';
import type { UploadFile } from 'antd/es/upload/interface';
import { CMISService } from '../../services/cmis';
import { TypeDefinition, PropertyDefinition } from '../../types/cmis';

interface TypeManagementProps {
  repositoryId: string;
}

import { useAuth } from '../../contexts/AuthContext';
export const TypeManagement: React.FC<TypeManagementProps> = ({ repositoryId }) => {
  const [types, setTypes] = useState<TypeDefinition[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingType, setEditingType] = useState<TypeDefinition | null>(null);
  const [form] = Form.useForm();

  // Upload functionality states
  const [uploadModalVisible, setUploadModalVisible] = useState(false);
  const [uploadFileList, setUploadFileList] = useState<UploadFile[]>([]);
  const [conflictModalVisible, setConflictModalVisible] = useState(false);
  const [conflictTypes, setConflictTypes] = useState<string[]>([]);
  const [pendingTypeDefinition, setPendingTypeDefinition] = useState<any>(null);

  // JSON edit functionality states
  const [jsonEditModalVisible, setJsonEditModalVisible] = useState(false);
  const [editingTypeJson, setEditingTypeJson] = useState<string>('');
  const [originalTypeId, setOriginalTypeId] = useState<string>('');
  const [editConflictModalVisible, setEditConflictModalVisible] = useState(false);
  const [editBeforeAfter, setEditBeforeAfter] = useState<{ before: any; after: any } | null>(null);

  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

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

  // Upload functionality handlers
  const handleImportClick = () => {
    setUploadModalVisible(true);
    setUploadFileList([]);
  };

  const handleFileUpload = async () => {
    if (uploadFileList.length === 0) {
      message.warning('ファイルを選択してください');
      return;
    }

    const file = uploadFileList[0];
    const reader = new FileReader();

    reader.onload = async (e) => {
      try {
        const content = e.target?.result as string;
        const typeDef = JSON.parse(content);

        // Check for conflicts (type ID already exists)
        const existingType = types.find(t => t.id === typeDef.id);

        if (existingType) {
          // Show conflict modal
          setConflictTypes([typeDef.id]);
          setPendingTypeDefinition(typeDef);
          setConflictModalVisible(true);
        } else {
          // No conflict, create directly
          await performTypeUpload(typeDef);
        }
      } catch (error) {
        // Failed to parse file
        message.error('ファイルの解析に失敗しました');
      }
    };

    // CRITICAL FIX (2025-10-26): Handle both real browser File objects and Playwright test UploadFile objects
    // In real browsers, file.originFileObj is a File object (which extends Blob)
    // In Playwright tests, file.originFileObj may not be properly initialized
    // Use file as Blob directly first, fall back to originFileObj if needed
    const fileToRead = (file as any).originFileObj || (file as any);

    try {
      reader.readAsText(fileToRead);
    } catch (error) {
      // Failed to read file
      message.error('ファイルの読み込みに失敗しました');
    }
  };

  const performTypeUpload = async (typeDef: any, overwrite: boolean = false) => {
    try {
      await cmisService.createType(repositoryId, typeDef);
      message.success('型定義をインポートしました');
      setUploadModalVisible(false);
      setConflictModalVisible(false);
      setUploadFileList([]);
      setPendingTypeDefinition(null);
      loadTypes();
    } catch (error) {
      // Failed to create type definition
      message.error('型定義の作成に失敗しました: ' + (error instanceof Error ? error.message : String(error)));
    }
  };

  const handleConflictConfirm = async () => {
    if (pendingTypeDefinition) {
      await performTypeUpload(pendingTypeDefinition, true);
    }
  };

  const handleUploadCancel = () => {
    setUploadModalVisible(false);
    setUploadFileList([]);
  };

  const handleConflictCancel = () => {
    setConflictModalVisible(false);
    setPendingTypeDefinition(null);
    setUploadFileList([]);
  };

  // JSON edit functionality handlers
  const handleJsonEdit = (type: TypeDefinition) => {
    setOriginalTypeId(type.id);
    setEditingTypeJson(JSON.stringify(type, null, 2));
    setJsonEditModalVisible(true);
  };

  const handleJsonEditSave = async () => {
    try {
      const typeDef = JSON.parse(editingTypeJson);

      // Check if ID changed (conflict detection)
      if (typeDef.id !== originalTypeId) {
        const existingType = types.find(t => t.id === typeDef.id);
        if (existingType) {
          // Show edit conflict modal
          const originalType = types.find(t => t.id === originalTypeId);
          setEditBeforeAfter({
            before: originalType,
            after: typeDef
          });
          setEditConflictModalVisible(true);
          return;
        }
      }

      // No conflict, update directly
      await performTypeUpdate(originalTypeId, typeDef);
    } catch (error) {
      message.error('JSONの解析に失敗しました');
    }
  };

  const performTypeUpdate = async (typeId: string, typeDef: any) => {
    try {
      await cmisService.updateType(repositoryId, typeId, typeDef);
      message.success('型定義を更新しました');
      setJsonEditModalVisible(false);
      setEditConflictModalVisible(false);
      setEditingTypeJson('');
      setOriginalTypeId('');
      setEditBeforeAfter(null);
      loadTypes();
    } catch (error) {
      message.error('型定義の更新に失敗しました: ' + (error instanceof Error ? error.message : String(error)));
    }
  };

  const handleEditConflictConfirm = async () => {
    if (editBeforeAfter) {
      await performTypeUpdate(originalTypeId, editBeforeAfter.after);
    }
  };

  const handleJsonEditCancel = () => {
    setJsonEditModalVisible(false);
    setEditingTypeJson('');
    setOriginalTypeId('');
  };

  const handleEditConflictCancel = () => {
    setEditConflictModalVisible(false);
    setEditBeforeAfter(null);
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
      render: (_: any, record: TypeDefinition) => (
        <Space>
          <Button
            icon={<EditOutlined />}
            size="small"
            onClick={() => handleJsonEdit(record)}
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
        <Space>
          <Button
            icon={<ImportOutlined />}
            onClick={handleImportClick}
          >
            ファイルからインポート
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setModalVisible(true)}
          >
            新規タイプ
          </Button>
        </Space>
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
        maskClosable={false}
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

      {/* Upload modal */}
      <Modal
        title="型定義ファイルのインポート"
        open={uploadModalVisible}
        onOk={handleFileUpload}
        onCancel={handleUploadCancel}
        okText="インポート"
        cancelText="キャンセル"
        maskClosable={false}
      >
        <Upload.Dragger
          accept=".json"
          maxCount={1}
          fileList={uploadFileList}
          beforeUpload={(file) => {
            setUploadFileList([file as any]);
            return false; // Prevent auto upload
          }}
          onRemove={() => {
            setUploadFileList([]);
          }}
        >
          <p className="ant-upload-drag-icon">
            <FileOutlined />
          </p>
          <p className="ant-upload-text">クリックまたはドラッグしてJSONファイルをアップロード</p>
          <p className="ant-upload-hint">型定義のJSON形式ファイルを選択してください</p>
        </Upload.Dragger>
      </Modal>

      {/* Conflict confirmation modal */}
      <Modal
        title="型定義の競合確認"
        open={conflictModalVisible}
        onOk={handleConflictConfirm}
        onCancel={handleConflictCancel}
        okText="上書きして作成"
        cancelText="キャンセル"
        maskClosable={false}
      >
        <Alert
          message="以下のタイプIDは既に存在します"
          description={
            <ul>
              {conflictTypes.map(typeId => (
                <li key={typeId}>{typeId}</li>
              ))}
            </ul>
          }
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
        />
        {pendingTypeDefinition && (
          <>
            <p>アップロードしようとしている型定義:</p>
            <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 4, overflow: 'auto', maxHeight: 300 }}>
              {JSON.stringify(pendingTypeDefinition, null, 2)}
            </pre>
          </>
        )}
      </Modal>

      {/* JSON edit modal */}
      <Modal
        title="型定義の編集 (JSON)"
        open={jsonEditModalVisible}
        onOk={handleJsonEditSave}
        onCancel={handleJsonEditCancel}
        okText="保存"
        cancelText="キャンセル"
        width={800}
        maskClosable={false}
      >
        <Alert
          message="型定義をJSON形式で直接編集できます"
          description="IDを変更すると競合確認が表示されます"
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
        />
        <Input.TextArea
          value={editingTypeJson}
          onChange={(e) => setEditingTypeJson(e.target.value)}
          rows={20}
          style={{ fontFamily: 'monospace', fontSize: 12 }}
        />
      </Modal>

      {/* Edit conflict confirmation modal */}
      <Modal
        title="型定義の競合確認（編集）"
        open={editConflictModalVisible}
        onOk={handleEditConflictConfirm}
        onCancel={handleEditConflictCancel}
        okText="上書きして更新"
        cancelText="キャンセル"
        width={800}
        maskClosable={false}
      >
        <Alert
          message="タイプIDが変更されています"
          description="IDを変更すると既存のタイプとして保存されます"
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
        />
        {editBeforeAfter && (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <div>
              <h4>変更前</h4>
              <pre style={{ background: '#fff1f0', padding: 12, borderRadius: 4, overflow: 'auto', maxHeight: 300 }}>
                {JSON.stringify({
                  id: editBeforeAfter.before?.id,
                  displayName: editBeforeAfter.before?.displayName
                }, null, 2)}
              </pre>
            </div>
            <div>
              <h4>変更後</h4>
              <pre style={{ background: '#f6ffed', padding: 12, borderRadius: 4, overflow: 'auto', maxHeight: 300 }}>
                {JSON.stringify({
                  id: editBeforeAfter.after.id,
                  displayName: editBeforeAfter.after.displayName
                }, null, 2)}
              </pre>
            </div>
          </div>
        )}
      </Modal>
    </Card>
  );
};

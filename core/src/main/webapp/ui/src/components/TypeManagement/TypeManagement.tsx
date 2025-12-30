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
  ImportOutlined,
  FormOutlined
} from '@ant-design/icons';
import type { UploadFile } from 'antd/es/upload/interface';
import { useTranslation } from 'react-i18next';
import { CMISService } from '../../services/cmis';
import { TypeDefinition, PropertyDefinition } from '../../types/cmis';
import { TypeGUIEditor } from './TypeGUIEditor';

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

  // GUI editor functionality states
  const [guiEditorModalVisible, setGuiEditorModalVisible] = useState(false);
  const [guiEditorType, setGuiEditorType] = useState<TypeDefinition | null>(null);
  const [guiEditorIsEditing, setGuiEditorIsEditing] = useState(false);

  const { handleAuthError } = useAuth();
  const { t } = useTranslation();
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
      message.error(t('typeManagement.messages.loadError'));
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (values: any) => {
    try {
      if (editingType) {
        await cmisService.updateType(repositoryId, editingType.id, values);
        message.success(t('typeManagement.messages.updateSuccess'));
      } else {
        await cmisService.createType(repositoryId, values);
        message.success(t('typeManagement.messages.createSuccess'));
      }

      setModalVisible(false);
      setEditingType(null);
      form.resetFields();
      // CRITICAL FIX (2025-12-24): Await loadTypes() to ensure table is refreshed before control returns
      // This fixes the issue where success message appears but type doesn't appear in table immediately
      await loadTypes();
    } catch (error) {
      message.error(editingType ? t('typeManagement.messages.updateError') : t('typeManagement.messages.createError'));
    }
  };

  // Type editing is implemented via JSON editor (handleJsonEdit)
  // Form-based editing was replaced with JSON editing for flexibility

  const handleDelete = async (typeId: string) => {
    try {
      await cmisService.deleteType(repositoryId, typeId);
      // Note: Type deletion is a NemakiWare-specific operation that goes beyond CMIS standard compliance
      // Existing documents with this type will fall back to base type behavior
      message.success(t('typeManagement.messages.deleteSuccess'));
      // CRITICAL FIX (2025-12-24): Await loadTypes() to ensure table is refreshed after delete
      await loadTypes();
    } catch (error: any) {
      // Handle detailed error messages from backend
      // CMISService.deleteType() now attaches structured data to the error object
      let errorMessage = t('typeManagement.messages.deleteError');
      
      // Check for structured error data (subtypes array)
      if (error.subtypes && Array.isArray(error.subtypes) && error.subtypes.length > 0) {
        Modal.error({
          title: t('typeManagement.messages.cannotDelete'),
          content: (
            <div>
              <p>{t('typeManagement.messages.hasSubtypes')}</p>
              <p>{t('typeManagement.messages.deleteSubtypesFirst')}</p>
              <ul>
                {error.subtypes.map((subtype: string) => (
                  <li key={subtype}>{subtype}</li>
                ))}
              </ul>
            </div>
          ),
        });
        return;
      }
      
      // Check for structured error data (referencingRelationships array)
      if (error.referencingRelationships && Array.isArray(error.referencingRelationships) && error.referencingRelationships.length > 0) {
        Modal.error({
          title: t('typeManagement.messages.cannotDelete'),
          content: (
            <div>
              <p>{t('typeManagement.messages.referencedByRelationship')}</p>
              <p>{t('typeManagement.messages.updateRelationshipsFirst')}</p>
              <ul>
                {error.referencingRelationships.map((rel: string) => (
                  <li key={rel}>{rel}</li>
                ))}
              </ul>
            </div>
          ),
        });
        return;
      }
      
      // Use the error message if available
      if (error.message && error.message !== 'Network error during type deletion') {
        errorMessage = error.message;
      }
      
      message.error(errorMessage);
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
      message.warning(t('typeManagement.messages.selectFile'));
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
        message.error(t('typeManagement.messages.parseError'));
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
      message.error(t('typeManagement.messages.readError'));
    }
  };

  const performTypeUpload = async (typeDef: any, _overwrite: boolean = false) => {
    try {
      await cmisService.createType(repositoryId, typeDef);
      message.success(t('typeManagement.messages.importSuccess'));
      setUploadModalVisible(false);
      setConflictModalVisible(false);
      setUploadFileList([]);
      setPendingTypeDefinition(null);
      // CRITICAL FIX (2025-12-24): Await loadTypes() to ensure table is refreshed after import
      await loadTypes();
    } catch (error) {
      // Failed to create type definition
      message.error(t('typeManagement.messages.createTypeError') + ': ' + (error instanceof Error ? error.message : String(error)));
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
      message.error(t('typeManagement.messages.jsonParseError'));
    }
  };

  const performTypeUpdate = async (typeId: string, typeDef: any) => {
    try {
      await cmisService.updateType(repositoryId, typeId, typeDef);
      message.success(t('typeManagement.messages.updateTypeSuccess'));
      setJsonEditModalVisible(false);
      setEditConflictModalVisible(false);
      setEditingTypeJson('');
      setOriginalTypeId('');
      setEditBeforeAfter(null);
      // CRITICAL FIX (2025-12-24): Await loadTypes() to ensure table is refreshed after update
      await loadTypes();
    } catch (error) {
      message.error(t('typeManagement.messages.updateTypeError') + ': ' + (error instanceof Error ? error.message : String(error)));
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

  // GUI editor functionality handlers
  const handleOpenGUIEditor = (type: TypeDefinition | null = null, isEditing: boolean = false) => {
    setGuiEditorType(type);
    setGuiEditorIsEditing(isEditing);
    setGuiEditorModalVisible(true);
  };

  const handleGUIEditorSave = async (typeDefinition: any) => {
    try {
      if (guiEditorIsEditing && guiEditorType) {
        await cmisService.updateType(repositoryId, guiEditorType.id, typeDefinition);
        message.success(t('typeManagement.messages.updateSuccess'));
      } else {
        await cmisService.createType(repositoryId, typeDefinition);
        message.success(t('typeManagement.messages.createSuccess'));
      }
      setGuiEditorModalVisible(false);
      setGuiEditorType(null);
      setGuiEditorIsEditing(false);
      // CRITICAL FIX (2025-12-24): Await loadTypes() to ensure table is refreshed after GUI editor save
      await loadTypes();
    } catch (error) {
      message.error(guiEditorIsEditing ? t('typeManagement.messages.updateError') : t('typeManagement.messages.createError'));
    }
  };

  const handleGUIEditorCancel = () => {
    setGuiEditorModalVisible(false);
    setGuiEditorType(null);
    setGuiEditorIsEditing(false);
  };

  const columns = [
    {
      title: t('typeManagement.columns.typeId'),
      dataIndex: 'id',
      key: 'id',
    },
    {
      title: t('typeManagement.columns.displayName'),
      dataIndex: 'displayName',
      key: 'displayName',
    },
    {
      title: t('typeManagement.columns.description'),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
    },
    {
      title: t('typeManagement.columns.baseType'),
      dataIndex: 'baseTypeId',
      key: 'baseTypeId',
    },
    {
      title: t('typeManagement.columns.parentType'),
      dataIndex: 'parentTypeId',
      key: 'parentTypeId',
    },
    {
      title: t('typeManagement.columns.propertyCount'),
      dataIndex: 'propertyDefinitions',
      key: 'propertyCount',
      render: (propertyDefinitions: Record<string, PropertyDefinition>) => 
        Object.keys(propertyDefinitions || {}).length,
    },
    {
      title: t('common.actions'),
      key: 'actions',
      width: 280,
      render: (_: any, record: TypeDefinition) => (
        <Space>
          <Button
            icon={<FormOutlined />}
            size="small"
            onClick={() => handleOpenGUIEditor(record, true)}
            disabled={!record.deletable && record.id.startsWith('cmis:')}
            title={!record.deletable && record.id.startsWith('cmis:') ? t('typeManagement.cannotEditStandardType') : t('typeManagement.editWithGUI')}
          >
            {t('typeManagement.guiEdit')}
          </Button>
          <Button
            icon={<EditOutlined />}
            size="small"
            onClick={() => handleJsonEdit(record)}
            disabled={!record.deletable && record.id.startsWith('cmis:')}
            title={!record.deletable && record.id.startsWith('cmis:') ? t('typeManagement.cannotEditStandardType') : t('typeManagement.editWithJSON')}
          >
            JSON
          </Button>
          {record.deletable !== false && !record.id.startsWith('cmis:') ? (
            <Popconfirm
              title={t('typeManagement.confirmDelete')}
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
          ) : (
            <Button
              icon={<DeleteOutlined />}
              size="small"
              disabled
              title={t('typeManagement.cannotDeleteStandardType')}
            >
              {t('common.delete')}
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
                  label={t('typeManagement.propertyForm.propertyId')}
                  rules={[{ required: true, message: t('typeManagement.propertyForm.propertyIdRequired') }]}
                >
                  <Input placeholder={t('typeManagement.propertyForm.propertyId')} />
                </Form.Item>
                
                <Form.Item
                  {...restField}
                  name={[name, 'displayName']}
                  label={t('typeManagement.columns.displayName')}
                  rules={[{ required: true, message: t('typeManagement.propertyForm.displayNameRequired') }]}
                >
                  <Input placeholder={t('typeManagement.columns.displayName')} />
                </Form.Item>
                
                <Form.Item
                  {...restField}
                  name={[name, 'propertyType']}
                  label={t('typeManagement.propertyForm.dataType')}
                  rules={[{ required: true, message: t('typeManagement.propertyForm.dataTypeRequired') }]}
                >
                  <Select placeholder={t('typeManagement.propertyForm.selectDataType')}>
                    <Select.Option value="string">{t('typeManagement.dataTypes.string')}</Select.Option>
                    <Select.Option value="integer">{t('typeManagement.dataTypes.integer')}</Select.Option>
                    <Select.Option value="decimal">{t('typeManagement.dataTypes.decimal')}</Select.Option>
                    <Select.Option value="boolean">{t('typeManagement.dataTypes.boolean')}</Select.Option>
                    <Select.Option value="datetime">{t('typeManagement.dataTypes.datetime')}</Select.Option>
                  </Select>
                </Form.Item>
                
                <Form.Item
                  {...restField}
                  name={[name, 'cardinality']}
                  label={t('typeManagement.propertyForm.cardinality')}
                  rules={[{ required: true, message: t('typeManagement.propertyForm.cardinalityRequired') }]}
                >
                  <Select placeholder={t('typeManagement.propertyForm.selectCardinality')}>
                    <Select.Option value="single">{t('typeManagement.cardinality.single')}</Select.Option>
                    <Select.Option value="multi">{t('typeManagement.cardinality.multi')}</Select.Option>
                  </Select>
                </Form.Item>
              </div>
              
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16 }}>
                <Form.Item
                  {...restField}
                  name={[name, 'required']}
                  label={t('typeManagement.propertyForm.required')}
                  valuePropName="checked"
                >
                  <Switch />
                </Form.Item>
                
                <Form.Item
                  {...restField}
                  name={[name, 'queryable']}
                  label={t('typeManagement.propertyForm.queryable')}
                  valuePropName="checked"
                >
                  <Switch />
                </Form.Item>
                
                <Form.Item
                  {...restField}
                  name={[name, 'updatable']}
                  label={t('typeManagement.propertyForm.updatable')}
                  valuePropName="checked"
                >
                  <Switch />
                </Form.Item>
                
                <Button 
                  type="link" 
                  danger 
                  onClick={() => remove(name)}
                >
                  {t('common.delete')}
                </Button>
              </div>
              
              <Form.Item
                {...restField}
                name={[name, 'description']}
                label={t('typeManagement.columns.description')}
              >
                <Input.TextArea rows={2} placeholder={t('typeManagement.propertyForm.propertyDescription')} />
              </Form.Item>
            </Card>
          ))}
          
          <Button 
            type="dashed" 
            onClick={() => add()} 
            block 
            icon={<PlusOutlined />}
          >
            {t('typeManagement.addProperty')}
          </Button>
        </>
      )}
    </Form.List>
  );

  const tabItems = [
    {
      key: 'basic',
      label: t('typeManagement.tabs.basicInfo'),
      children: (
        <>
          <Form.Item
            name="id"
            label={t('typeManagement.columns.typeId')}
            rules={[{ required: true, message: t('typeManagement.form.typeIdRequired') }]}
          >
            <Input 
              placeholder={t('typeManagement.form.enterTypeId')}
              disabled={!!editingType}
            />
          </Form.Item>

          <Form.Item
            name="displayName"
            label={t('typeManagement.columns.displayName')}
            rules={[{ required: true, message: t('typeManagement.propertyForm.displayNameRequired') }]}
          >
            <Input placeholder={t('typeManagement.form.enterDisplayName')} />
          </Form.Item>

          <Form.Item
            name="description"
            label={t('typeManagement.columns.description')}
          >
            <Input.TextArea rows={3} placeholder={t('typeManagement.form.enterDescription')} />
          </Form.Item>

          <Form.Item
            name="baseTypeId"
            label={t('typeManagement.columns.baseType')}
            rules={[{ required: true, message: t('typeManagement.form.baseTypeRequired') }]}
          >
            <Select placeholder={t('typeManagement.form.selectBaseType')}>
              <Select.Option value="cmis:document">{t('typeManagement.baseTypes.document')}</Select.Option>
              <Select.Option value="cmis:folder">{t('typeManagement.baseTypes.folder')}</Select.Option>
            </Select>
          </Form.Item>

          <Form.Item
            name="parentTypeId"
            label={t('typeManagement.columns.parentType')}
          >
            <Select placeholder={t('typeManagement.form.selectParentType')} allowClear>
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
              label={t('typeManagement.form.creatable')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>

            <Form.Item
              name="fileable"
              label={t('typeManagement.form.fileable')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>

            <Form.Item
              name="queryable"
              label={t('typeManagement.propertyForm.queryable')}
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
      label: t('typeManagement.tabs.propertyDefinitions'),
      children: <PropertyDefinitionForm />,
    },
  ];

  return (
    <Card>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>
          <FileOutlined /> {t('typeManagement.title')}
        </h2>
        <Space>
          <Button
            icon={<ImportOutlined />}
            onClick={handleImportClick}
          >
            {t('typeManagement.importFromFile')}
          </Button>
          <Button
            icon={<FormOutlined />}
            onClick={() => handleOpenGUIEditor(null, false)}
          >
            {t('typeManagement.createWithGUI')}
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setModalVisible(true)}
          >
            {t('typeManagement.newType')}
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
        title={editingType ? t('typeManagement.modal.editType') : t('typeManagement.modal.createType')}
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
                {editingType ? t('common.update') : t('common.create')}
              </Button>
              <Button onClick={handleCancel}>
                {t('common.cancel')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      {/* Upload modal */}
      <Modal
        title={t('typeManagement.modal.importTypeDefinition')}
        open={uploadModalVisible}
        onOk={handleFileUpload}
        onCancel={handleUploadCancel}
        okText={t('common.import')}
        cancelText={t('common.cancel')}
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
          <p className="ant-upload-text">{t('typeManagement.upload.dragText')}</p>
          <p className="ant-upload-hint">{t('typeManagement.upload.hint')}</p>
        </Upload.Dragger>
      </Modal>

      {/* Conflict confirmation modal */}
      <Modal
        title={t('typeManagement.modal.conflictConfirmation')}
        open={conflictModalVisible}
        onOk={handleConflictConfirm}
        onCancel={handleConflictCancel}
        okText={t('typeManagement.modal.overwriteAndCreate')}
        cancelText={t('common.cancel')}
        maskClosable={false}
      >
        <Alert
          message={t('typeManagement.conflict.typeIdExists')}
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
            <p>{t('typeManagement.conflict.uploadingDefinition')}</p>
            <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 4, overflow: 'auto', maxHeight: 300 }}>
              {JSON.stringify(pendingTypeDefinition, null, 2)}
            </pre>
          </>
        )}
      </Modal>

      {/* JSON edit modal */}
      <Modal
        title={t('typeManagement.modal.editTypeJson')}
        open={jsonEditModalVisible}
        onOk={handleJsonEditSave}
        onCancel={handleJsonEditCancel}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        width={800}
        maskClosable={false}
      >
        <Alert
          message={t('typeManagement.jsonEdit.canEditDirectly')}
          description={t('typeManagement.jsonEdit.idChangeWarning')}
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
        title={t('typeManagement.modal.editConflictConfirmation')}
        open={editConflictModalVisible}
        onOk={handleEditConflictConfirm}
        onCancel={handleEditConflictCancel}
        okText={t('typeManagement.modal.overwriteAndUpdate')}
        cancelText={t('common.cancel')}
        width={800}
        maskClosable={false}
      >
        <Alert
          message={t('typeManagement.editConflict.typeIdChanged')}
          description={t('typeManagement.editConflict.willSaveAsExisting')}
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
        />
        {editBeforeAfter && (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <div>
              <h4>{t('typeManagement.editConflict.before')}</h4>
              <pre style={{ background: '#fff1f0', padding: 12, borderRadius: 4, overflow: 'auto', maxHeight: 300 }}>
                {JSON.stringify({
                  id: editBeforeAfter.before?.id,
                  displayName: editBeforeAfter.before?.displayName
                }, null, 2)}
              </pre>
            </div>
            <div>
              <h4>{t('typeManagement.editConflict.after')}</h4>
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

      {/* GUI Editor modal */}
      <Modal
        title={guiEditorIsEditing ? t('typeManagement.modal.editTypeGUI') : t('typeManagement.modal.createTypeGUI')}
        open={guiEditorModalVisible}
        onCancel={handleGUIEditorCancel}
        footer={null}
        width={1000}
        maskClosable={false}
        destroyOnClose
      >
        <TypeGUIEditor
          initialValue={guiEditorType}
          existingTypes={types}
          onSave={handleGUIEditorSave}
          onCancel={handleGUIEditorCancel}
          isEditing={guiEditorIsEditing}
        />
      </Modal>
    </Card>
  );
};

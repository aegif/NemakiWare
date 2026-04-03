import { useEffect, useState, useCallback } from 'react';
import { Table, Button, Modal, Form, Input, Select, Switch, Space, Tag, App, Popconfirm } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  ConnectorDefinition,
  listConnectors,
  createConnector,
  updateConnector,
  deleteConnector,
} from '../../services/externalIngest';

const ARCHETYPE_OPTIONS = [
  'FILE_SHARE', 'COMPOUND_NOTE', 'CHAT_CONTEXT', 'BUSINESS_RECORD', 'MESSAGE_CONTEXT',
];

export function ConnectorManagementTab() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [connectors, setConnectors] = useState<ConnectorDefinition[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<ConnectorDefinition | null>(null);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setConnectors(await listConnectors());
    } catch (err) {
      message.error(t('connectorManagement.loadError'));
    } finally {
      setLoading(false);
    }
  }, [message, t]);

  useEffect(() => { load(); }, [load]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ enabled: true, sourceArchetype: 'FILE_SHARE' });
    setModalOpen(true);
  };

  const openEdit = (record: ConnectorDefinition) => {
    setEditing(record);
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editing) {
        // Merge form values with existing fields to preserve API-only properties
        const merged = { ...editing, ...values };
        await updateConnector(editing.connectorId, merged);
        message.success(t('connectorManagement.updateSuccess'));
      } else {
        await createConnector(values);
        message.success(t('connectorManagement.createSuccess'));
      }
      setModalOpen(false);
      load();
    } catch (err) {
      const detail = err instanceof Error ? err.message : '';
      message.error(detail || t('connectorManagement.saveError'));
    }
  };

  const handleDelete = async (connectorId: string) => {
    try {
      await deleteConnector(connectorId);
      message.success(t('connectorManagement.deleteSuccess'));
      load();
    } catch {
      message.error(t('connectorManagement.deleteError'));
    }
  };

  const columns = [
    {
      title: t('connectorManagement.columns.connectorId'),
      dataIndex: 'connectorId',
      key: 'connectorId',
    },
    {
      title: t('connectorManagement.columns.displayName'),
      dataIndex: 'displayName',
      key: 'displayName',
    },
    {
      title: t('connectorManagement.columns.archetype'),
      dataIndex: 'sourceArchetype',
      key: 'sourceArchetype',
      render: (v: string) => <Tag>{v}</Tag>,
    },
    {
      title: t('connectorManagement.columns.sourceSystem'),
      dataIndex: 'sourceSystem',
      key: 'sourceSystem',
    },
    {
      title: t('connectorManagement.columns.enabled'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 80,
      render: (v: boolean) => <Tag color={v ? 'green' : 'default'}>{v ? t('common.on') : t('common.off')}</Tag>,
    },
    {
      title: t('connectorManagement.columns.actions'),
      key: 'actions',
      width: 120,
      render: (_: unknown, record: ConnectorDefinition) => (
        <Space size="small">
          <Button icon={<EditOutlined />} size="small" onClick={() => openEdit(record)} />
          <Popconfirm title={t('connectorManagement.deleteConfirm')} onConfirm={() => handleDelete(record.connectorId)}>
            <Button icon={<DeleteOutlined />} size="small" danger />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          {t('connectorManagement.create')}
        </Button>
      </Space>

      <Table
        columns={columns}
        dataSource={connectors}
        rowKey="connectorId"
        loading={loading}
        pagination={false}
        size="small"
        bordered
      />

      <Modal
        title={editing ? t('connectorManagement.editTitle') : t('connectorManagement.createTitle')}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        okText={editing ? t('common.save') : t('common.create')}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="connectorId" label={t('connectorManagement.form.connectorId')}
            rules={[{ required: true }]} >
            <Input disabled={!!editing} />
          </Form.Item>
          <Form.Item name="displayName" label={t('connectorManagement.form.displayName')}>
            <Input />
          </Form.Item>
          <Form.Item name="sourceArchetype" label={t('connectorManagement.form.archetype')}
            rules={[{ required: true }]}>
            <Select options={ARCHETYPE_OPTIONS.map(a => ({ value: a, label: a }))} />
          </Form.Item>
          <Form.Item name="sourceSystem" label={t('connectorManagement.form.sourceSystem')}
            rules={[{ required: true }]}>
            <Input placeholder="google_drive, notion, imap, ..." />
          </Form.Item>
          <Form.Item name="authType" label={t('connectorManagement.form.authType')}>
            <Select allowClear options={[
              { value: 'oauth2', label: t('connectorManagement.authTypes.oauth2') },
              { value: 'api_key', label: t('connectorManagement.authTypes.apiKey') },
              { value: 'service_account', label: t('connectorManagement.authTypes.serviceAccount') },
              { value: 'none', label: t('connectorManagement.authTypes.none') },
            ]} />
          </Form.Item>
          <Form.Item name="endpoint" label={t('connectorManagement.form.endpoint')}>
            <Input placeholder="https://..." />
          </Form.Item>
          <Form.Item name="tenantId" label={t('connectorManagement.form.tenantId')}>
            <Input />
          </Form.Item>
          <Form.Item name="enabled" label={t('connectorManagement.form.enabled')} valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

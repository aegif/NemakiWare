import { useEffect, useState, useCallback } from 'react';
import { Table, Button, Modal, Form, Input, Select, Switch, Space, Tag, App, Popconfirm, Typography } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  ConnectorDefinition,
  AdapterDescriptor,
  listConnectors,
  createConnector,
  updateConnector,
  deleteConnector,
  fetchAdapterRegistry,
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
  const [adapterRegistry, setAdapterRegistry] = useState<AdapterDescriptor[]>([]);

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

  // Load adapter registry for sourceSystem dropdown
  useEffect(() => {
    fetchAdapterRegistry().then(setAdapterRegistry).catch(() => {});
  }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ enabled: true, sourceArchetype: 'FILE_SHARE' });
    setModalOpen(true);
  };

  const openEdit = (record: ConnectorDefinition) => {
    setEditing(record);
    // Clear masked secret placeholders so the form shows empty (not "[configured]")
    const formValues = { ...record };
    if (formValues.credentialRef === '[configured]') formValues.credentialRef = undefined;
    if (formValues.webhookSecret === '[configured]') formValues.webhookSecret = undefined;
    form.setFieldsValue(formValues);
    setModalOpen(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editing) {
        // Filter out undefined AND empty strings for secret fields to avoid overwriting
        const definedValues = Object.fromEntries(
          Object.entries(values).filter(([k, v]) => {
            if (v === undefined) return false;
            // Don't send empty secret fields — backend preserves existing values
            if ((k === 'credentialRef' || k === 'webhookSecret') && v === '') return false;
            return true;
          })
        );
        const merged = { ...editing, ...definedValues };
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
      title: t('connectorManagement.columns.webhookUrl'),
      key: 'webhookUrl',
      ellipsis: true,
      render: (_: unknown, record: ConnectorDefinition) => {
        const url = `${window.location.origin}/core/api/v1/ingest-webhook/${record.connectorId}`;
        return <Typography.Text copyable={{ text: url }} style={{ fontSize: 11 }}>{url}</Typography.Text>;
      },
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
          <Popconfirm title={t('connectorManagement.deleteConfirm')}
            okText={t('common.delete')} cancelText={t('common.cancel')}
            onConfirm={() => handleDelete(record.connectorId)}>
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
            <Select
              showSearch
              placeholder={t('connectorManagement.form.sourceSystemHint')}
              options={adapterRegistry.map(a => ({
                value: a.sourceSystem,
                label: `${a.displayName} (${a.sourceSystem})`,
              }))}
              onChange={(val: string) => {
                const adapter = adapterRegistry.find(a => a.sourceSystem === val);
                if (adapter) {
                  form.setFieldsValue({ sourceArchetype: adapter.archetype });
                }
              }}
            />
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
          <Form.Item name="webhookSecret" label={t('connectorManagement.form.webhookSecret')}
            extra={t('connectorManagement.form.webhookSecretHint')}>
            <Input.Password placeholder={t('connectorManagement.form.webhookSecretPlaceholder')} />
          </Form.Item>
          <Form.Item name="enabled" label={t('connectorManagement.form.enabled')} valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

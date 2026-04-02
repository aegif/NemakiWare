import { useEffect, useState, useCallback } from 'react';
import { Table, Button, Modal, Form, Input, Select, Switch, Space, Tag, App, Popconfirm, Alert } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  ImportProfileDefinition,
  listProfiles,
  createProfile,
  updateProfile,
  deleteProfile,
} from '../../services/externalIngest';

const ARCHETYPE_OPTIONS = [
  'FILE_SHARE', 'COMPOUND_NOTE', 'CHAT_CONTEXT', 'BUSINESS_RECORD', 'MESSAGE_CONTEXT',
];

// Options are built inside component to access t()


interface Props {
  repositoryId: string;
}

export function ImportProfileManagementTab({ repositoryId }: Props) {
  const { t } = useTranslation();
  const { message } = App.useApp();

  const DEDUPE_OPTIONS = [
    { value: 'skip_if_same_version', label: t('importProfileManagement.dedupePolicies.skip') },
    { value: 'create_new_version', label: t('importProfileManagement.dedupePolicies.newVersion') },
    { value: 'replace', label: t('importProfileManagement.dedupePolicies.replace') },
  ];
  const VERSIONING_OPTIONS = [
    { value: 'major', label: t('importProfileManagement.versioningPolicies.major') },
    { value: 'minor', label: t('importProfileManagement.versioningPolicies.minor') },
    { value: 'none', label: t('importProfileManagement.versioningPolicies.none') },
  ];
  const [profiles, setProfiles] = useState<ImportProfileDefinition[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<ImportProfileDefinition | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setProfiles(await listProfiles(repositoryId));
    } catch (err) {
      message.error(t('importProfileManagement.loadError'));
    } finally {
      setLoading(false);
    }
  }, [repositoryId, message, t]);

  useEffect(() => { load(); }, [load]);

  const openCreate = () => {
    setEditing(null);
    setWarnings([]);
    form.resetFields();
    form.setFieldsValue({
      repositoryId,
      enabled: true,
      dedupePolicy: 'create_new_version',
      versioningPolicy: 'major',
      defaultObjectTypeId: 'cmis:document',
    });
    setModalOpen(true);
  };

  const openEdit = async (record: ImportProfileDefinition) => {
    setEditing(record);
    form.setFieldsValue(record);
    setModalOpen(true);
    // Fetch warnings from backend
    try {
      const { warnings: w } = await import('../../services/externalIngest').then(m => m.getProfile(record.profileId));
      setWarnings(w || []);
    } catch {
      setWarnings([]);
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      values.repositoryId = repositoryId;
      let result;
      if (editing) {
        result = await updateProfile(editing.profileId, values);
        message.success(t('importProfileManagement.updateSuccess'));
      } else {
        result = await createProfile(values);
        message.success(t('importProfileManagement.createSuccess'));
      }
      if (result.warnings && result.warnings.length > 0) {
        setWarnings(result.warnings);
      } else {
        setModalOpen(false);
        setWarnings([]);
      }
      load();
    } catch (err) {
      const detail = err instanceof Error ? err.message : '';
      message.error(detail || t('importProfileManagement.saveError'));
    }
  };

  const handleDelete = async (profileId: string) => {
    try {
      await deleteProfile(profileId);
      message.success(t('importProfileManagement.deleteSuccess'));
      load();
    } catch {
      message.error(t('importProfileManagement.deleteError'));
    }
  };

  const columns = [
    {
      title: t('importProfileManagement.columns.profileId'),
      dataIndex: 'profileId',
      key: 'profileId',
    },
    {
      title: t('importProfileManagement.columns.displayName'),
      dataIndex: 'displayName',
      key: 'displayName',
    },
    {
      title: t('importProfileManagement.columns.targetFolder'),
      key: 'targetFolder',
      render: (_: unknown, record: ImportProfileDefinition) =>
        record.targetFolderId || record.targetFolderPath || '-',
    },
    {
      title: t('importProfileManagement.columns.dedupePolicy'),
      dataIndex: 'dedupePolicy',
      key: 'dedupePolicy',
      render: (v: string) => <Tag>{v || 'create_new_version'}</Tag>,
    },
    {
      title: t('importProfileManagement.columns.enabled'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 80,
      render: (v: boolean) => <Tag color={v ? 'green' : 'default'}>{v ? t('common.on') : t('common.off')}</Tag>,
    },
    {
      title: t('importProfileManagement.columns.actions'),
      key: 'actions',
      width: 120,
      render: (_: unknown, record: ImportProfileDefinition) => (
        <Space size="small">
          <Button icon={<EditOutlined />} size="small" onClick={() => openEdit(record)} />
          <Popconfirm title={t('importProfileManagement.deleteConfirm')} onConfirm={() => handleDelete(record.profileId)}>
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
          {t('importProfileManagement.create')}
        </Button>
      </Space>

      <Table
        columns={columns}
        dataSource={profiles}
        rowKey="profileId"
        loading={loading}
        pagination={false}
        size="small"
        bordered
      />

      <Modal
        title={editing ? t('importProfileManagement.editTitle') : t('importProfileManagement.createTitle')}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => { setModalOpen(false); setWarnings([]); }}
        okText={editing ? t('common.save') : t('common.create')}
        destroyOnClose
        width={600}
      >
        {warnings.length > 0 && (
          <Alert
            type="warning"
            showIcon
            message={t('importProfileManagement.phase2Warning')}
            description={warnings.map((w, i) => <div key={i}>{w}</div>)}
            style={{ marginBottom: 16 }}
          />
        )}
        <Form form={form} layout="vertical">
          <Form.Item name="profileId" label={t('importProfileManagement.form.profileId')}
            rules={[{ required: true }]}>
            <Input disabled={!!editing} />
          </Form.Item>
          <Form.Item name="displayName" label={t('importProfileManagement.form.displayName')}>
            <Input />
          </Form.Item>
          <Form.Item name="targetFolderId" label={t('importProfileManagement.form.targetFolderId')}>
            <Input placeholder="e.g. abc123def456" />
          </Form.Item>
          <Form.Item name="targetFolderPath" label={t('importProfileManagement.form.targetFolderPath')}
            extra={t('importProfileManagement.form.targetFolderHint')}>
            <Input placeholder="e.g. /Sites/Invoices" />
          </Form.Item>
          <Form.Item name="defaultObjectTypeId" label={t('importProfileManagement.form.objectType')}>
            <Input placeholder="cmis:document" />
          </Form.Item>
          <Form.Item name="allowedArchetypes" label={t('importProfileManagement.form.allowedArchetypes')}>
            <Select mode="multiple" allowClear
              options={ARCHETYPE_OPTIONS.map(a => ({ value: a, label: a }))} />
          </Form.Item>
          <Form.Item name="secondaryTypeIds" label={t('importProfileManagement.form.secondaryTypeIds')}
            extra={t('importProfileManagement.form.secondaryTypeIdsHint')}>
            <Select mode="tags" allowClear placeholder="e.g. nemaki:cloudDriveMetadata" />
          </Form.Item>
          <Form.Item name="dedupePolicy" label={t('importProfileManagement.form.dedupePolicy')}>
            <Select options={DEDUPE_OPTIONS} />
          </Form.Item>
          <Form.Item name="versioningPolicy" label={t('importProfileManagement.form.versioningPolicy')}>
            <Select options={VERSIONING_OPTIONS} />
          </Form.Item>
          <Form.Item name="enabled" label={t('importProfileManagement.form.enabled')} valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

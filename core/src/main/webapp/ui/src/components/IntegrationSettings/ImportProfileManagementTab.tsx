import { useEffect, useState, useCallback } from 'react';
import { Table, Button, Modal, Form, Input, Select, Switch, Space, Tag, App, Popconfirm, Alert, Tooltip } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import {
  ImportProfileDefinition,
  AdapterDescriptor,
  listProfiles,
  createProfile,
  updateProfile,
  deleteProfile,
  getProfile,
  listConnectors,
  listConnectorSummary,
  fetchAdapterRegistry,
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
  const { authToken } = useAuth();
  const isAdmin = authToken?.isAdmin === true;

  const DEDUPE_OPTIONS = [
    { value: 'skip_if_same_version', label: t('importProfileManagement.dedupePolicies.skip') },
    { value: 'create_new_version', label: t('importProfileManagement.dedupePolicies.newVersion') },
    { value: 'replace', label: t('importProfileManagement.dedupePolicies.replace') },
    { value: 'create_new_if_parent_context_changed', label: t('importProfileManagement.dedupePolicies.parentContextChanged') },
    { value: 'replace_relationships_on_resync', label: t('importProfileManagement.dedupePolicies.replaceRelationships') },
  ];
  const DEDUPE_MATCH_OPTIONS = [
    { value: 'source_id', label: t('importProfileManagement.dedupeMatchBy.sourceId', 'ソースID（デフォルト）') },
    { value: 'filename', label: t('importProfileManagement.dedupeMatchBy.filename', 'ファイル名') },
    { value: 'source_id_or_filename', label: t('importProfileManagement.dedupeMatchBy.sourceIdOrFilename', 'ソースID → ファイル名（フォールバック）') },
  ];
  const VERSIONING_OPTIONS = [
    { value: 'major', label: t('importProfileManagement.versioningPolicies.major') },
    { value: 'minor', label: t('importProfileManagement.versioningPolicies.minor') },
    { value: 'none', label: t('importProfileManagement.versioningPolicies.none') },
  ];
  const UPDATE_POLICY_OPTIONS = [
    { value: 'version_up_on_content_change', label: t('importProfileManagement.updatePolicies.versionUp') },
    { value: 'always_version_up', label: t('importProfileManagement.updatePolicies.alwaysVersionUp') },
    { value: 'update_metadata_only', label: t('importProfileManagement.updatePolicies.metadataOnly') },
  ];
  const RELATIONSHIP_POLICY_OPTIONS = [
    { value: '', label: t('importProfileManagement.relationshipPolicies.none') },
    { value: 'direct', label: t('importProfileManagement.relationshipPolicies.direct') },
  ];
  const ACL_SYNC_OPTIONS = [
    { value: 'inherit_from_folder', label: t('importProfileManagement.aclSyncPolicies.inheritFromFolder') },
    { value: 'copy_from_source', label: t('importProfileManagement.aclSyncPolicies.copyFromSource') },
    { value: 'none', label: t('importProfileManagement.aclSyncPolicies.none') },
  ];
  const [profiles, setProfiles] = useState<ImportProfileDefinition[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<ImportProfileDefinition | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [form] = Form.useForm();
  const [adapterRegistry, setAdapterRegistry] = useState<AdapterDescriptor[]>([]);
  const [connectorMap, setConnectorMap] = useState<Record<string, string>>({}); // connectorId → sourceSystem
  const [selectedAdapter, setSelectedAdapter] = useState<AdapterDescriptor | null>(null);
  const [jsonMode, setJsonMode] = useState(false);
  const schedulerEnabled = Form.useWatch('schedulerEnabled', form) ?? false;
  const formTargetFolderId: string | undefined = Form.useWatch('targetFolderId', form);

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

  // Load adapter registry and connector map for structured schedulerParams form.
  // Admins read the full list; non-admins fall back to the folder-scoped
  // summary endpoint when a target folder ID is known (loaded lazily on
  // form open via the helper below — see refreshConnectorMapForFolder).
  useEffect(() => {
    fetchAdapterRegistry().then(setAdapterRegistry).catch(() => {});
    if (isAdmin) {
      listConnectors().then(conns => {
        const map: Record<string, string> = {};
        for (const c of conns) map[c.connectorId] = c.sourceSystem;
        setConnectorMap(map);
      }).catch(() => {});
    }
  }, [isAdmin]);

  /**
   * For non-admins: fetch the connector summary for the given folder. The
   * summary endpoint enforces cmis:all on targetFolderId server-side and
   * returns only delegated connectors. We swap the picker's source-of-truth
   * map without merging into admin's full map (admin doesn't need this).
   */
  const refreshConnectorMapForFolder = useCallback(async (targetFolderId?: string) => {
    if (isAdmin || !targetFolderId) return;
    try {
      const summaries = await listConnectorSummary(repositoryId, targetFolderId);
      const map: Record<string, string> = {};
      for (const s of summaries) if (s.sourceSystem) map[s.connectorId] = s.sourceSystem;
      setConnectorMap(map);
    } catch {
      // Folder not delegated yet, or no connectors delegated — keep the
      // map empty so the picker is correctly empty.
      setConnectorMap({});
    }
  }, [isAdmin, repositoryId]);

  // Re-resolve the delegated connector picker whenever a non-admin user
  // changes the targetFolderId. Watch via Form.useWatch (debounced by
  // React's render cycle, not per-keystroke fetch).
  useEffect(() => {
    if (!modalOpen || isAdmin || !formTargetFolderId || formTargetFolderId.trim().length === 0) return;
    refreshConnectorMapForFolder(formTargetFolderId);
  }, [formTargetFolderId, modalOpen, isAdmin, refreshConnectorMapForFolder]);

  const openCreate = () => {
    setEditing(null);
    setWarnings([]);
    setSelectedAdapter(null);
    setJsonMode(false);
    form.resetFields();
    form.setFieldsValue({
      repositoryId,
      enabled: true,
      dedupePolicy: 'skip_if_same_version',
      dedupeMatchBy: 'source_id',
      versioningPolicy: 'major',
      defaultObjectTypeId: 'cmis:document',
    });
    setModalOpen(true);
  };

  const openEdit = async (record: ImportProfileDefinition) => {
    setEditing(record);
    setJsonMode(false);
    // Reset form fully before applying record values to avoid stale fields
    form.resetFields();
    // Non-admin: refresh the connector picker against the profile's bound
    // folder so the dropdown only shows connectors still delegated to them.
    await refreshConnectorMapForFolder(record.targetFolderId);
    // Resolve adapter from connector
    const sourceSystem = record.defaultConnectorId ? connectorMap[record.defaultConnectorId] : null;
    const desc = sourceSystem ? adapterRegistry.find(a => a.sourceSystem === sourceSystem) : null;
    setSelectedAdapter(desc || null);
    const formValues: Record<string, unknown> = {
      ...record,
      schedulerParams: record.schedulerParams ? JSON.stringify(record.schedulerParams, null, 2) : '',
    };
    // Populate structured fields if adapter is known
    if (desc && record.schedulerParams) {
      formValues._schedulerParamsFields = record.schedulerParams;
    }
    form.setFieldsValue(formValues);
    setModalOpen(true);
    // Fetch warnings from backend
    try {
      const { warnings: w } = await getProfile(record.profileId);
      setWarnings(w || []);
    } catch {
      setWarnings([]);
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      values.repositoryId = repositoryId;
      // Build schedulerParams from structured fields or JSON string
      if (!jsonMode && values._schedulerParamsFields) {
        const fields = values._schedulerParamsFields as Record<string, string>;
        const params: Record<string, string> = {};
        for (const [k, v] of Object.entries(fields)) {
          if (v != null && String(v).trim()) params[k] = String(v).trim();
        }
        values.schedulerParams = Object.keys(params).length > 0 ? params : undefined;
        delete values._schedulerParamsFields;
      } else if (typeof values.schedulerParams === 'string' && values.schedulerParams.trim()) {
        try {
          values.schedulerParams = JSON.parse(values.schedulerParams);
        } catch {
          message.error(t('importProfileManagement.schedulerParamsInvalid'));
          return;
        }
      } else {
        values.schedulerParams = undefined;
      }
      delete values._schedulerParamsFields;
      // Parse retentionDays from string to number
      if (values.retentionDays != null && values.retentionDays !== '') {
        values.retentionDays = Number(values.retentionDays);
      } else {
        values.retentionDays = undefined;
      }
      let result;
      if (editing) {
        // Filter out undefined values to avoid overwriting existing fields
        const definedValues = Object.fromEntries(
          Object.entries(values).filter(([, v]) => v !== undefined)
        );
        const merged = { ...editing, ...definedValues };
        result = await updateProfile(editing.profileId, merged);
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
      render: (v: string) => <Tag>{v || 'skip_if_same_version'}</Tag>,
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
          <Popconfirm title={t('importProfileManagement.deleteConfirm')}
            okText={t('common.delete')} cancelText={t('common.cancel')}
            onConfirm={() => handleDelete(record.profileId)}>
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
            <Input placeholder={t('importProfileManagement.form.targetFolderIdHint')} />
          </Form.Item>
          <Form.Item name="targetFolderPath" label={t('importProfileManagement.form.targetFolderPath')}
            extra={t('importProfileManagement.form.targetFolderHint')}>
            <Input placeholder={t('importProfileManagement.form.targetFolderPathHint')} />
          </Form.Item>
          <Form.Item name="defaultObjectTypeId" label={t('importProfileManagement.form.objectType')}>
            <Input placeholder="cmis:document" />
          </Form.Item>
          <Form.Item name="allowedArchetypes" label={t('importProfileManagement.form.allowedArchetypes')}>
            <Select mode="multiple" allowClear
              options={ARCHETYPE_OPTIONS.map(a => ({ value: a, label: a }))} />
          </Form.Item>
          <Form.Item name="allowedConnectorIds" label={t('importProfileManagement.form.allowedConnectorIds')}
            extra={!isAdmin && Object.keys(connectorMap).length === 0
              ? t('importProfileManagement.form.noDelegatedConnectors', { defaultValue: 'No connectors are delegated to you for this folder. Ask an admin to delegate one.' })
              : t('importProfileManagement.form.allowedConnectorIdsHint')}>
            {/*
              Options are derived from connectorMap so the user picks
              from a known set rather than free-typing IDs the backend
              will reject. Admin uses listConnectors() (full list);
              non-admin uses listConnectorSummary() (delegated only).
              Non-admin gets mode="multiple" (no free tag entry); admin
              keeps mode="tags" so they can paste in IDs that aren't yet
              in their map (rare but useful for quick edits).
            */}
            <Select
              mode={isAdmin ? 'tags' : 'multiple'}
              allowClear
              placeholder={t('importProfileManagement.form.allowedConnectorIdsHint')}
              options={Object.keys(connectorMap).map(cid => ({
                value: cid,
                label: `${cid} (${connectorMap[cid]})`,
              }))} />
          </Form.Item>
          <Form.Item name="defaultConnectorId" label={t('importProfileManagement.form.defaultConnectorId')}>
            <Select
              allowClear
              showSearch
              placeholder={t('importProfileManagement.form.defaultConnectorId')}
              options={Object.keys(connectorMap).map(cid => ({
                value: cid,
                label: `${cid} (${connectorMap[cid]})`,
              }))}
              onChange={(connId?: string) => {
                if (!connId) { setSelectedAdapter(null); return; }
                const sourceSystem = connectorMap[connId];
                const desc = sourceSystem ? adapterRegistry.find(a => a.sourceSystem === sourceSystem) : null;
                setSelectedAdapter(desc || null);
              }} />
          </Form.Item>
          <Form.Item name="secondaryTypeIds" label={t('importProfileManagement.form.secondaryTypeIds')}
            extra={t('importProfileManagement.form.secondaryTypeIdsHint')}>
            <Select mode="tags" allowClear placeholder={t('importProfileManagement.form.secondaryTypeIdsPlaceholder')} />
          </Form.Item>
          <Form.Item name="dedupePolicy" label={t('importProfileManagement.form.dedupePolicy')}>
            <Select options={DEDUPE_OPTIONS} />
          </Form.Item>
          <Form.Item name="dedupeMatchBy" label={t('importProfileManagement.form.dedupeMatchBy', '同一文書の判定方法')}
            extra={t('importProfileManagement.form.dedupeMatchByHint', 'チャット添付ファイルなど外部IDが不安定なソースでは「ファイル名」を選択してください')}>
            <Select options={DEDUPE_MATCH_OPTIONS} />
          </Form.Item>
          <Form.Item name="versioningPolicy" label={t('importProfileManagement.form.versioningPolicy')}>
            <Select options={VERSIONING_OPTIONS} />
          </Form.Item>
          <Form.Item name="updatePolicy" label={t('importProfileManagement.form.updatePolicy')}>
            <Select allowClear options={UPDATE_POLICY_OPTIONS} />
          </Form.Item>
          <Form.Item name="relationshipPolicy" label={t('importProfileManagement.form.relationshipPolicy')}>
            <Select allowClear options={RELATIONSHIP_POLICY_OPTIONS} />
          </Form.Item>
          <Form.Item name="retentionDays" label={t('importProfileManagement.form.retentionDays')}
            extra={t('importProfileManagement.form.retentionDaysHint')}>
            <Input type="number" min={1} />
          </Form.Item>
          <Form.Item name="aclSyncPolicy" label={t('importProfileManagement.form.aclSyncPolicy')}>
            <Select options={ACL_SYNC_OPTIONS} />
          </Form.Item>
          <Form.Item name="schedulerEnabled" label={t('importProfileManagement.form.schedulerEnabled')}
            valuePropName="checked"
            extra={!isAdmin
              ? t('importProfileManagement.form.schedulerAdminOnly', { defaultValue: 'Scheduled ingestion is admin-only in this release.' })
              : t('importProfileManagement.form.schedulerEnabledHint')}>
            {isAdmin
              ? <Switch />
              : <Tooltip title={t('importProfileManagement.form.schedulerAdminOnly', { defaultValue: 'Scheduled ingestion is admin-only in this release.' })}>
                  <Switch disabled />
                </Tooltip>}
          </Form.Item>
          <Form.Item label={t('importProfileManagement.form.schedulerParams', 'スケジューラパラメータ')}
            extra={<><a onClick={() => setJsonMode(!jsonMode)}>{jsonMode ? t('importProfileManagement.form.switchToStructured', '構造化入力に切替') : t('importProfileManagement.form.switchToJson', 'JSON入力に切替')}</a>
              {selectedAdapter && <span style={{marginLeft:8,color:'#888'}}>{selectedAdapter.displayName}: {selectedAdapter.paramsExample}</span>}
            </>}>
            {jsonMode ? (
              <Form.Item name="schedulerParams" noStyle>
                <Input.TextArea rows={3} placeholder={selectedAdapter?.paramsExample || '{"key": "value"}'} />
              </Form.Item>
            ) : selectedAdapter ? (
              <Space direction="vertical" style={{width:'100%'}}>
                {[...selectedAdapter.requiredParams, ...selectedAdapter.optionalParams]
                  .filter(k => k !== 'limit')
                  .map(key => (
                    <Form.Item key={key} name={['_schedulerParamsFields', key]}
                      label={key}
                      rules={selectedAdapter.requiredParams.includes(key)
                        ? [{required: schedulerEnabled, message: `${key} is required when scheduler is enabled`}]
                        : []}
                      style={{marginBottom:4}}>
                      <Input placeholder={key} />
                    </Form.Item>
                  ))}
              </Space>
            ) : (
              <Form.Item name="schedulerParams" noStyle>
                <Input.TextArea rows={3} placeholder={t('importProfileManagement.form.schedulerParamsHint', 'コネクタを選択するとフィールドが表示されます')} />
              </Form.Item>
            )}
          </Form.Item>
          <Form.Item name="defaultClassification" label={t('importProfileManagement.form.defaultClassification')}
            extra={t('importProfileManagement.form.defaultClassificationHint')}>
            <Input placeholder={t('importProfileManagement.form.defaultClassificationPlaceholder')} />
          </Form.Item>
          <Form.Item name="preserveOriginalEml" label={t('importProfileManagement.form.preserveOriginalEml')}
            valuePropName="checked" extra={t('importProfileManagement.form.preserveOriginalEmlHint')}>
            <Switch />
          </Form.Item>
          <Form.Item name="defaultProfile" label={t('importProfileManagement.form.defaultProfile')}
            valuePropName="checked"
            extra={!isAdmin
              ? t('importProfileManagement.form.defaultProfileAdminOnly', { defaultValue: 'Repository default profile is admin-only.' })
              : t('importProfileManagement.form.defaultProfileHint')}>
            {isAdmin
              ? <Switch />
              : <Tooltip title={t('importProfileManagement.form.defaultProfileAdminOnly', { defaultValue: 'Repository default profile is admin-only.' })}>
                  <Switch disabled />
                </Tooltip>}
          </Form.Item>
          <Form.Item name="enabled" label={t('importProfileManagement.form.enabled')} valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

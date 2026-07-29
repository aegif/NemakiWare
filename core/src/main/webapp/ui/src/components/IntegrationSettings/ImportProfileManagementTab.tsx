import { useEffect, useState, useCallback } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Select, Switch, Space, Tag, App, Popconfirm, Alert, Tooltip } from 'antd';
import { RowActionTooltip } from '../common/RowActionTooltip';
import { PlusOutlined, EditOutlined, DeleteOutlined, FolderOpenOutlined } from '@ant-design/icons';
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
import { FolderPickerModal } from './FolderPickerModal';

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
  // V4 (RC5 ext): filter toggle — show only profiles the scheduler
  // auto-disabled (enabled=false + lastAutoDisabledAt set). Lets admins
  // triage scheduler shutdowns without scanning every profile row.
  const [onlyAutoDisabled, setOnlyAutoDisabled] = useState(false);
  // V6 (RC5.1): additional "within last N days" window for the
  // auto-disabled filter. 0 = all (V4 default). > 0 narrows to recent
  // events so fresh scheduler shutdowns aren't drowned out by legacy
  // ones that haven't been cleaned up yet.
  const [autoDisabledDays, setAutoDisabledDays] = useState<number>(0);
  // H3 (RC5.2): swap the preset Select for an InputNumber so admins
  // can pick any positive number of days, then snap back to the
  // preset Select on "Done". Reset to false whenever the underlying
  // count drops to 0 so the UI doesn't get stuck in custom mode with
  // no profiles to filter.
  const [customDaysMode, setCustomDaysMode] = useState<boolean>(false);
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
  // For non-admins: derived from the connector summary call result. We use
  // it as a proxy for "do I hold cmis:all on this folder" — the summary
  // endpoint short-circuits on a 403 if the caller doesn't.
  const [folderAccessState, setFolderAccessState] = useState<'unknown' | 'ok' | 'denied' | 'unresolved'>('unknown');
  const [folderPickerOpen, setFolderPickerOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      // W1 (RC5.3): when the V6 window is active AND the user has
      // turned on the "only auto-disabled" filter, push the filter
      // down to the server via `autoDisabledSince`. This keeps the
      // payload small for large profile lists (legacy auto-disables
      // never sent). The client still re-applies V6/G1 logic against
      // the returned list — server is best-effort, client is final.
      const since = (onlyAutoDisabled && autoDisabledDays > 0)
        ? new Date(Date.now() - autoDisabledDays * 24 * 60 * 60 * 1000).toISOString()
        : undefined;
      setProfiles(await listProfiles({ repositoryId, autoDisabledSince: since }));
    } catch (err) {
      message.error(t('importProfileManagement.loadError'));
    } finally {
      setLoading(false);
    }
  }, [repositoryId, message, t, onlyAutoDisabled, autoDisabledDays]);

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
   *
   * Side effect: also drives the {@link folderAccessState} indicator so
   * the user gets immediate feedback on whether their entered folder ID
   * is one they actually hold {@code cmis:all} on. The summary endpoint
   * returns 403 in that case, which we surface as "denied" rather than
   * the silent "no connectors visible" the original behaviour gave.
   *
   * <p>Returns the freshly-resolved map. Callers that need to act on the
   * result in the same tick (e.g. {@link openEdit} resolving the adapter
   * descriptor) must use the return value — reading {@link connectorMap}
   * via closure after this call resolves yields the stale, pre-update
   * value because {@code setConnectorMap} is async.
   */
  const refreshConnectorMapForFolder = useCallback(async (targetFolderId?: string): Promise<Record<string, string>> => {
    if (isAdmin || !targetFolderId) return {};
    try {
      const summaries = await listConnectorSummary(repositoryId, targetFolderId);
      const map: Record<string, string> = {};
      for (const s of summaries) if (s.sourceSystem) map[s.connectorId] = s.sourceSystem;
      setConnectorMap(map);
      setFolderAccessState('ok');
      return map;
    } catch (err) {
      // The summary helper throws a generic Error; the underlying status
      // (403 vs 400 vs 500) isn't propagated. We treat any failure as
      // "denied/unresolved" — the resulting empty connector list makes
      // the cause obvious in either case.
      setConnectorMap({});
      setFolderAccessState(err instanceof Error && err.message.includes('400') ? 'unresolved' : 'denied');
      return {};
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
    setFolderAccessState('unknown');
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
    // We MUST use the returned map (not connectorMap from closure) — the
    // setConnectorMap call inside refreshConnectorMapForFolder hasn't been
    // committed by React yet at this tick, so reading the state hook here
    // would resolve to the stale pre-update value and selectedAdapter
    // would be wrong on first open of a non-admin edit.
    const freshMap = await refreshConnectorMapForFolder(record.targetFolderId);
    const lookupMap = isAdmin ? connectorMap : freshMap;
    // Resolve adapter from connector
    const sourceSystem = record.defaultConnectorId ? lookupMap[record.defaultConnectorId] : null;
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
      key: 'enabled',
      width: 160,
      render: (_: unknown, record: ImportProfileDefinition) => (
        <Space direction="vertical" size={2}>
          <Tag color={record.enabled ? 'green' : 'default'}>
            {record.enabled ? t('common.on') : t('common.off')}
          </Tag>
          {/* V1 (RC5 ext): badge a profile that the scheduler auto-disabled
              so admins can spot it without comparing timestamps. The
              tooltip shows the reason (e.g. CREATOR_USER_INACTIVE) so
              they know whether re-enabling is safe. */}
          {!record.enabled && record.lastAutoDisabledAt && (
            <RowActionTooltip title={record.lastAutoDisabledReason || t('importProfileManagement.autoDisabledHint')}>
              <Tag color="orange" style={{ fontSize: 10 }}>
                {t('importProfileManagement.autoDisabledBadge')}
              </Tag>
            </RowActionTooltip>
          )}
        </Space>
      ),
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

  // V4: derived list — filter to auto-disabled when toggle is on, otherwise pass-through
  // V6 (RC5.1): with `autoDisabledDays > 0`, narrow further to events
  // whose lastAutoDisabledAt falls within that window. Reason: ops
  // teams investigating an incident want fresh events surfaced; legacy
  // auto-disables that nobody has cleaned up create noise.
  const isAutoDisabled = (p: ImportProfileDefinition) =>
    !p.enabled && !!p.lastAutoDisabledAt;
  const isWithinDays = (p: ImportProfileDefinition, days: number) => {
    if (days <= 0 || !p.lastAutoDisabledAt) return true;
    const t = Date.parse(p.lastAutoDisabledAt);
    if (Number.isNaN(t)) return false;        // malformed timestamp → fail-shut (exclude)
    const cutoff = Date.now() - days * 24 * 60 * 60 * 1000;
    return t >= cutoff;
  };
  const autoDisabledCount = profiles.filter(isAutoDisabled).length;
  const autoDisabledRecentCount = profiles.filter(p =>
    isAutoDisabled(p) && isWithinDays(p, autoDisabledDays)).length;
  const visibleProfiles = onlyAutoDisabled
    ? profiles.filter(p => isAutoDisabled(p) && isWithinDays(p, autoDisabledDays))
    : profiles;

  // G1 (RC5.1): when the filter Switch unmounts (count drops to 0
  // because everything's been re-enabled), the React state
  // `onlyAutoDisabled` would otherwise stay `true`. The Switch is
  // gone, so the admin can't toggle it back without a page refresh,
  // and `visibleProfiles` would silently filter to an empty table
  // until then. Reset it here so the table reverts to the full list
  // automatically.
  // H3 (RC5.2): same trick for customDaysMode — the InputNumber
  // unmounts with the filter row, so reset the mode flag to keep
  // the Select-only state consistent on re-mount.
  useEffect(() => {
    if (autoDisabledCount === 0) {
      if (onlyAutoDisabled) setOnlyAutoDisabled(false);
      if (customDaysMode) setCustomDaysMode(false);
    }
  }, [autoDisabledCount, onlyAutoDisabled, customDaysMode]);

  return (
    <>
      <Space style={{ marginBottom: 16 }} wrap>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          {t('importProfileManagement.create')}
        </Button>
        {/* V4: filter visible only when at least one auto-disabled
            profile exists, so the UI stays clean when nothing's wrong.
            V6 (RC5.1): days-window selector appears alongside. */}
        {autoDisabledCount > 0 && (
          <Space size={4}>
            <Switch
              size="small"
              checked={onlyAutoDisabled}
              onChange={setOnlyAutoDisabled}
            />
            <span>{t('importProfileManagement.autoDisabledFilter')}</span>
            <Tag color="orange">
              {autoDisabledDays > 0
                ? `${autoDisabledRecentCount}/${autoDisabledCount}`
                : autoDisabledCount}
            </Tag>
            {/* H3 (RC5.2): "Custom..." option swaps the Select to
                InputNumber so admins can investigate incident windows
                that don't fit the preset list (e.g. a 14-day rolling
                check). Selecting any preset (or clearing the custom
                value) snaps back to the Select. */}
            {customDaysMode ? (
              <Space size={2}>
                <InputNumber
                  size="small"
                  autoFocus
                  min={1}
                  max={9999}
                  value={autoDisabledDays > 0 ? autoDisabledDays : undefined}
                  onChange={(v) => setAutoDisabledDays(typeof v === 'number' && v > 0 ? v : 0)}
                  addonAfter="d"
                  style={{ width: 100 }}
                />
                <Button
                  size="small"
                  onClick={() => { setCustomDaysMode(false); setAutoDisabledDays(0); }}
                >
                  {t('importProfileManagement.autoDisabledWindowDone', { defaultValue: 'Done' })}
                </Button>
              </Space>
            ) : (
              <Select
                size="small"
                value={autoDisabledDays}
                onChange={(v) => {
                  if (v === -1) {       // sentinel for "Custom..."
                    setCustomDaysMode(true);
                    setAutoDisabledDays(0);
                  } else {
                    setAutoDisabledDays(v as number);
                  }
                }}
                style={{ minWidth: 130 }}
                options={[
                  { value: 0, label: t('importProfileManagement.autoDisabledWindowAll') },
                  { value: 1, label: t('importProfileManagement.autoDisabledWindow1d') },
                  { value: 7, label: t('importProfileManagement.autoDisabledWindow7d') },
                  { value: 30, label: t('importProfileManagement.autoDisabledWindow30d') },
                  { value: -1, label: t('importProfileManagement.autoDisabledWindowCustom') },
                ]}
              />
            )}
          </Space>
        )}
      </Space>

      {/* V4: banner — informational, only shows when there are auto-disabled
          profiles. Encourages reading the reason before re-enabling.
          V6: when window is active, banner switches to recent-count phrasing. */}
      {autoDisabledCount > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={
            autoDisabledDays > 0
              ? t('importProfileManagement.autoDisabledBannerRecent', {
                  count: autoDisabledRecentCount,
                  days: autoDisabledDays,
                  total: autoDisabledCount,
                })
              : t('importProfileManagement.autoDisabledBanner', { count: autoDisabledCount })
          }
        />
      )}

      <Table
        columns={columns}
        dataSource={visibleProfiles}
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
          <Form.Item name="targetFolderId" label={t('importProfileManagement.form.targetFolderId')}
            rules={!isAdmin ? [{ required: true, message: t('importProfileManagement.form.targetFolderIdRequiredDelegated', { defaultValue: '委譲プロファイルでは folder ID が必須です（path 指定は管理者専用）' }) }] : []}
            extra={!isAdmin && formTargetFolderId
              ? folderAccessState === 'ok'
                ? <span style={{ color: '#52c41a' }}>
                    {t('importProfileManagement.form.folderAccessOk', { defaultValue: '✓ このフォルダの管理権限があります' })}
                  </span>
                : folderAccessState === 'denied'
                  ? <span style={{ color: '#ff4d4f' }}>
                      {t('importProfileManagement.form.folderAccessDenied', { defaultValue: '✗ このフォルダに cmis:all 権限がありません' })}
                    </span>
                  : folderAccessState === 'unresolved'
                    ? <span style={{ color: '#faad14' }}>
                        {t('importProfileManagement.form.folderUnresolved', { defaultValue: '? フォルダ ID を解決できません' })}
                      </span>
                    : null
              : undefined}>
            {/*
              Compact Input + Browse button. The button opens
              FolderPickerModal which probes /summary on selection — so
              non-admins picking via the tree get the same cmis:all
              gate as if they had typed an ID. Admin gets the picker too
              (informational only — they can pick any folder).
            */}
            <Input
              placeholder={t('importProfileManagement.form.targetFolderIdHint')}
              addonAfter={
                <Tooltip title={t('importProfileManagement.form.browseFolder', { defaultValue: 'フォルダを参照' })}>
                  <Button
                    type="link"
                    size="small"
                    icon={<FolderOpenOutlined />}
                    onClick={() => setFolderPickerOpen(true)}
                    style={{ padding: 0, height: 'auto', lineHeight: 1 }}
                  />
                </Tooltip>
              }
            />
          </Form.Item>
          {/*
            targetFolderPath: admin only. Non-admin delegated profiles must
            pin a folder ID (also normalised server-side on POST) so the
            picker / summary endpoint / cache key all agree on a single
            resolved identifier. Letting non-admin enter only a path would
            leave the in-form connector picker empty (it watches
            targetFolderId) and confuse the audit trail. Admin keeps the
            path option for legacy / scripted workflows.
          */}
          {isAdmin ? (
            <Form.Item name="targetFolderPath" label={t('importProfileManagement.form.targetFolderPath')}
              extra={t('importProfileManagement.form.targetFolderHint')}>
              <Input placeholder={t('importProfileManagement.form.targetFolderPathHint')} />
            </Form.Item>
          ) : null}
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

      {/*
        FolderPicker is rendered as a sibling so it survives the parent
        Modal's destroyOnClose. The selected ID is written directly into
        the form via form.setFieldValue, which triggers the existing
        targetFolderId watcher and refreshes the connector summary in
        the same render cycle.
      */}
      <FolderPickerModal
        open={folderPickerOpen}
        repositoryId={repositoryId}
        currentFolderId={formTargetFolderId}
        onSelect={(folderId) => {
          form.setFieldValue('targetFolderId', folderId);
          setFolderPickerOpen(false);
        }}
        onCancel={() => setFolderPickerOpen(false)}
      />
    </>
  );
}

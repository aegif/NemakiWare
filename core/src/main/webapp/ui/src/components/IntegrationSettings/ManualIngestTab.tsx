import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Form, Select, Input, Upload, Button, Alert, Space, Typography, App, Switch } from 'antd';
import { UploadOutlined, SendOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { AuthService } from '../../services/auth';
import { parseJsonResponseBody } from '../../services/http/jsonFetch';
import {
  ConnectorDefinition,
  ImportProfileDefinition,
  listConnectors,
  listConnectorSummary,
  listProfiles,
} from '../../services/externalIngest';

const { Text } = Typography;

interface Props {
  repositoryId: string;
}

interface IngestResult {
  requestId?: string;
  objectId?: string;
  versionLabel?: string;
  isNewVersion?: boolean;
  skipped?: boolean;
  skipReason?: string;
  lineageEventId?: string;
  errors?: string[];
  warnings?: string[];
  dryRun?: boolean;
}

function defaultObjectType(archetype?: string): string {
  switch (archetype) {
    case 'FILE_SHARE': return 'file';
    case 'COMPOUND_NOTE': return 'page';
    case 'BUSINESS_RECORD': return 'record';
    case 'CHAT_CONTEXT': return 'message';
    case 'MESSAGE_CONTEXT': return 'message';
    default: return 'file';
  }
}

export function ManualIngestTab({ repositoryId }: Props) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const { authToken } = useAuth();
  const isAdmin = authToken?.isAdmin === true;

  const [connectors, setConnectors] = useState<ConnectorDefinition[]>([]);
  const [profiles, setProfiles] = useState<ImportProfileDefinition[]>([]);
  const [selectedConnectorId, setSelectedConnectorId] = useState<string | undefined>();
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<IngestResult | null>(null);
  const [form] = Form.useForm();
  const watchedProfileId: string | undefined = Form.useWatch('profileId', form);

  // Loader. Admin pulls full connector + profile lists. Non-admin pulls
  // ONLY the delegated profile list — admin endpoints would 403 and break
  // the whole UI. Connectors for non-admin are loaded lazily after a
  // profile is chosen, via the folder-scoped /connectors/summary endpoint.
  const load = useCallback(async () => {
    try {
      if (isAdmin) {
        const [c, p] = await Promise.all([listConnectors(), listProfiles(repositoryId)]);
        setConnectors(c.filter(x => x.enabled));
        setProfiles(p.filter(x => x.enabled));
      } else {
        const p = await listProfiles(repositoryId);
        setProfiles(p.filter(x => x.enabled));
        setConnectors([]);
      }
    } catch {
      message.error(t('manualIngest.loadError'));
    }
  }, [repositoryId, message, t, isAdmin]);

  useEffect(() => { load(); }, [load]);

  // Non-admin: when a profile is picked, derive the available connectors
  // from /connectors/summary against that profile's target folder, and
  // intersect with profile.allowedConnectorIds (which the backend already
  // narrowed to delegated connectors). Result is what the user is allowed
  // to actually invoke.
  useEffect(() => {
    if (isAdmin) return;
    if (!watchedProfileId) {
      setConnectors([]);
      setSelectedConnectorId(undefined);
      form.setFieldValue('connectorId', undefined);
      return;
    }
    const profile = profiles.find(p => p.profileId === watchedProfileId);
    if (!profile?.targetFolderId) {
      setConnectors([]);
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const summaries = await listConnectorSummary(repositoryId, profile.targetFolderId!);
        if (cancelled) return;
        const allowedSet = profile.allowedConnectorIds && profile.allowedConnectorIds.length > 0
          ? new Set(profile.allowedConnectorIds) : null;
        const visible: ConnectorDefinition[] = summaries
          .filter(s => !allowedSet || allowedSet.has(s.connectorId))
          .map(s => ({
            connectorId: s.connectorId,
            displayName: s.displayName,
            sourceArchetype: s.sourceArchetype || 'FILE_SHARE',
            sourceSystem: s.sourceSystem || '',
            adapterKind: s.adapterKind,
            enabled: true,
          }));
        setConnectors(visible);
        // If the previous connector is no longer in the list, clear it.
        const currentConn = form.getFieldValue('connectorId');
        if (currentConn && !visible.some(c => c.connectorId === currentConn)) {
          form.setFieldValue('connectorId', undefined);
          setSelectedConnectorId(undefined);
        }
      } catch {
        if (!cancelled) setConnectors([]);
      }
    })();
    return () => { cancelled = true; };
  }, [isAdmin, watchedProfileId, profiles, repositoryId, form]);

  // Derive selected connector's archetype for conditional fields + profile filtering
  const selectedConnector = useMemo(
    () => connectors.find(c => c.connectorId === selectedConnectorId),
    [connectors, selectedConnectorId]
  );
  const selectedArchetype = selectedConnector?.sourceArchetype;

  // Filter profiles to those compatible with the selected connector.
  // For non-admin, the picker order is reversed (profile first), so this
  // memo is mostly relevant in the admin flow.
  const compatibleProfiles = useMemo(() => {
    if (!selectedConnector) return profiles;
    return profiles.filter(p => {
      if (p.allowedConnectorIds && p.allowedConnectorIds.length > 0
          && !p.allowedConnectorIds.includes(selectedConnector.connectorId)) {
        return false;
      }
      if (p.allowedArchetypes && p.allowedArchetypes.length > 0
          && !p.allowedArchetypes.includes(selectedConnector.sourceArchetype)) {
        return false;
      }
      return true;
    });
  }, [profiles, selectedConnector]);

  const handleConnectorChange = (connectorId: string) => {
    setSelectedConnectorId(connectorId);
    if (!isAdmin) return;
    // Admin flow: recompute compatibility against the NEW connector
    // (not the stale memo). Non-admin flow chooses profile first so this
    // back-direction reset is unnecessary.
    const newConnector = connectors.find(c => c.connectorId === connectorId);
    const currentProfile = form.getFieldValue('profileId');
    if (currentProfile && newConnector) {
      const stillValid = profiles.some(p => {
        if (p.profileId !== currentProfile) return false;
        if (p.allowedConnectorIds?.length && !p.allowedConnectorIds.includes(connectorId)) return false;
        if (p.allowedArchetypes?.length && !p.allowedArchetypes.includes(newConnector.sourceArchetype)) return false;
        return true;
      });
      if (!stillValid) {
        form.setFieldValue('profileId', undefined);
      }
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      setResult(null);

      const authService = AuthService.getInstance();
      const headers = authService.getAuthHeaders();

      // Build metadata from archetype-specific fields
      const metadata: Record<string, string> = {};
      if (values.channelId) metadata.channelId = values.channelId;
      if (values.mailboxId) metadata.mailboxId = values.mailboxId;
      if (values.messageStableId) metadata.messageStableId = values.messageStableId;

      const formData = new FormData();
      const requestJson = JSON.stringify({
        profileId: values.profileId,
        connectorId: values.connectorId,
        sourceObjectId: values.sourceObjectId,
        sourceObjectType: values.sourceObjectType || defaultObjectType(selectedArchetype),
        sourceUrl: values.sourceUrl,
        dryRun: values.dryRun || false,
        executionMode: 'manual',
        metadata: Object.keys(metadata).length > 0 ? metadata : undefined,
      });
      formData.append('request', new Blob([requestJson], { type: 'application/json' }), 'request.json');

      if (values.file && values.file.length > 0) {
        const fileObj = values.file[0]?.originFileObj ?? values.file[0];
        if (fileObj) formData.append('content', fileObj);
      }

      const res = await fetch(`/core/api/v1/repo/${encodeURIComponent(repositoryId)}/ingest`, {
        method: 'POST',
        headers: { ...headers },
        body: formData,
      });

      let body: IngestResult;
      try {
        body = (await parseJsonResponseBody(res, 'manualIngest.execute')) as unknown as IngestResult;
      } catch {
        setResult({ errors: [`HTTP ${res.status}: ${res.statusText}`] });
        message.error(t('manualIngest.error'));
        return;
      }
      setResult(body);

      if (res.ok && !body.errors?.length) {
        if (body.skipped) {
          message.info(t('manualIngest.skipped'));
        } else if (body.dryRun) {
          message.info(t('manualIngest.dryRunComplete'));
        } else {
          message.success(t('manualIngest.success'));
        }
      } else {
        message.error(body.errors?.[0] || t('manualIngest.error'));
      }
    } catch (err) {
      const detail = err instanceof Error ? err.message : '';
      message.error(detail || t('manualIngest.error'));
    } finally {
      setSubmitting(false);
    }
  };

  // Picker order: admin sees [connector → profile]; non-admin sees
  // [profile → connector] because connectors are derived from the
  // chosen profile's folder scope.
  const profileSelector = (
    <Form.Item name="profileId" label={t('manualIngest.form.profile')}
      rules={[{ required: true }]}>
      <Select placeholder={t('manualIngest.form.selectProfile')}
        options={(isAdmin ? compatibleProfiles : profiles).map(p => ({
          value: p.profileId,
          label: `${p.displayName || p.profileId}`,
        }))} />
    </Form.Item>
  );
  const connectorSelector = (
    <Form.Item name="connectorId" label={t('manualIngest.form.connector')}
      rules={[{ required: true }]}
      extra={!isAdmin && !watchedProfileId
        ? t('manualIngest.form.selectProfileFirst', { defaultValue: 'Select a profile first.' })
        : undefined}>
      <Select placeholder={t('manualIngest.form.selectConnector')}
        onChange={handleConnectorChange}
        disabled={!isAdmin && (!watchedProfileId || connectors.length === 0)}
        options={connectors.map(c => ({
          value: c.connectorId,
          label: `${c.displayName || c.connectorId} (${c.sourceSystem})`,
        }))} />
    </Form.Item>
  );

  return (
    <Card title={t('manualIngest.title')}>
      <Form form={form} layout="vertical" style={{ maxWidth: 600 }}>
        {isAdmin ? (
          <>
            {connectorSelector}
            {profileSelector}
          </>
        ) : (
          <>
            {profileSelector}
            {connectorSelector}
          </>
        )}

        <Form.Item name="sourceObjectId" label={t('manualIngest.form.sourceObjectId')}
          rules={[{ required: true }]}>
          <Input placeholder={t('manualIngest.form.sourceObjectIdHint')} />
        </Form.Item>

        <Form.Item name="sourceObjectType" label={t('manualIngest.form.sourceObjectType')}>
          <Input placeholder={t('manualIngest.form.sourceObjectTypeHint')} />
        </Form.Item>

        <Form.Item name="sourceUrl" label={t('manualIngest.form.sourceUrl')}>
          <Input placeholder={t('manualIngest.form.sourceUrlHint')} />
        </Form.Item>

        {/* Archetype-specific fields */}
        {selectedArchetype === 'CHAT_CONTEXT' && (
          <Form.Item name="channelId" label={t('manualIngest.form.channelId')}
            rules={[{ required: true, message: t('manualIngest.form.channelIdRequired') }]}>
            <Input placeholder={t('manualIngest.form.channelIdHint')} />
          </Form.Item>
        )}

        {selectedArchetype === 'MESSAGE_CONTEXT' && (
          <>
            <Form.Item name="mailboxId" label={t('manualIngest.form.mailboxId')}
              rules={[{ required: true, message: t('manualIngest.form.mailboxIdRequired') }]}>
              <Input placeholder={t('manualIngest.form.mailboxIdHint')} />
            </Form.Item>
            <Form.Item name="messageStableId" label={t('manualIngest.form.messageStableId')}
              rules={[{ required: true, message: t('manualIngest.form.messageStableIdRequired') }]}>
              <Input placeholder={t('manualIngest.form.messageStableIdHint')} />
            </Form.Item>
          </>
        )}

        <Form.Item name="file" label={t('manualIngest.form.file')} valuePropName="fileList"
          getValueFromEvent={(e) => Array.isArray(e) ? e : e?.fileList}>
          <Upload beforeUpload={() => false} maxCount={1}>
            <Button icon={<UploadOutlined />}>{t('manualIngest.form.selectFile')}</Button>
          </Upload>
        </Form.Item>

        <Form.Item name="dryRun" label={t('manualIngest.form.dryRun')} valuePropName="checked">
          <Switch />
        </Form.Item>

        <Form.Item>
          <Button type="primary" icon={<SendOutlined />} onClick={handleSubmit}
            loading={submitting}>
            {t('manualIngest.form.execute')}
          </Button>
        </Form.Item>
      </Form>

      {result && (
        <Space direction="vertical" style={{ width: '100%', marginTop: 16 }}>
          {result.errors && result.errors.length > 0 && (
            <Alert type="error" showIcon
              message={t('manualIngest.resultError')}
              description={result.errors.join('\n')} />
          )}
          {result.skipped && (
            <Alert type="info" showIcon
              message={t('manualIngest.resultSkipped')}
              description={
                <Space direction="vertical">
                  <Text>{result.skipReason}</Text>
                  {result.objectId && <Text><strong>{t('manualIngest.existingObjectId')}:</strong> {result.objectId}</Text>}
                </Space>
              } />
          )}
          {result.dryRun && !result.errors?.length && (
            <Alert type="info" showIcon message={t('manualIngest.resultDryRun')} />
          )}
          {result.objectId && !result.dryRun && !result.skipped && (
            <Alert type="success" showIcon
              message={t('manualIngest.resultSuccess')}
              description={
                <Space direction="vertical">
                  <Text><strong>{t('manualIngest.resultObjectId')}:</strong> {result.objectId}</Text>
                  {result.isNewVersion && <Text>{t('manualIngest.newVersion')}</Text>}
                  {result.lineageEventId && <Text><strong>{t('manualIngest.resultLineage')}:</strong> {result.lineageEventId}</Text>}
                </Space>
              } />
          )}
          {result.warnings && result.warnings.length > 0 && (
            <Alert type="warning" showIcon
              message={t('manualIngest.resultWarnings')}
              description={result.warnings.join('\n')} />
          )}
        </Space>
      )}
    </Card>
  );
}

/**
 * Admin → Evidence and time-stamping (design docs/design/anchor-scheduler.md §6).
 *
 * Sets WHEN the evidence ledger is sealed and anchored on its own, per ledger domain (this
 * repository, or record-content — the domain an exported package's anchor comes from). WHERE
 * anchors go (TSA / OpenTimestamps URLs) is shown, never edited: those are start-up system
 * properties, and the POST to them carries no SSRF check.
 *
 * "Could not read" is never shown as zero or as "disabled": an unreadable count reads
 * "not readable", and unreadable settings keep the form closed.
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  InputNumber,
  Select,
  Space,
  Spin,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import { ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { withDetail } from '../../i18n/withDetail';
import {
  AnchorSchedule,
  RECORD_CONTENT_DOMAIN,
  SCHEDULE_KEYS,
  ScheduleRefused,
  checkpointAndAnchorNow,
  getAnchorSchedule,
  getAnchorStatus,
  updateAnchorSchedule,
} from '../../services/anchorSchedule';

const { Text, Paragraph } = Typography;

interface Props {
  repositoryId: string;
}

interface FormValues {
  enabled: boolean;
  intervalMinutes: number | null;
  maxUnanchoredEntries: number | null;
  minIntervalMinutes: number | null;
  upgradeIntervalMinutes: number | null;
  retryUnsettledIntervalMinutes: number | null;
}

const FIELD_OF_KEY: Record<string, keyof FormValues> = {
  [SCHEDULE_KEYS.enabled]: 'enabled',
  [SCHEDULE_KEYS.intervalMinutes]: 'intervalMinutes',
  [SCHEDULE_KEYS.maxUnanchoredEntries]: 'maxUnanchoredEntries',
  [SCHEDULE_KEYS.minIntervalMinutes]: 'minIntervalMinutes',
  [SCHEDULE_KEYS.upgradeIntervalMinutes]: 'upgradeIntervalMinutes',
  [SCHEDULE_KEYS.retryUnsettledIntervalMinutes]: 'retryUnsettledIntervalMinutes',
};

function toNumber(raw: string | null | undefined): number | null {
  if (raw == null || raw.trim() === '') {
    return null;
  }
  const n = Number(raw.trim());
  return Number.isInteger(n) ? n : null;
}

export const EvidenceAnchoring: React.FC<Props> = ({ repositoryId }) => {
  const { t } = useTranslation();
  const [form] = Form.useForm<FormValues>();
  const [domain, setDomain] = useState<string>(repositoryId);
  const [schedule, setSchedule] = useState<AnchorSchedule | null>(null);
  const [status, setStatus] = useState<Record<string, unknown> | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [running, setRunning] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [runResult, setRunResult] = useState<{ httpStatus: number; body: Record<string, unknown> } | null>(null);
  const enabled = Form.useWatch('enabled', form);
  const interval = Form.useWatch('intervalMinutes', form);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const [s, st] = await Promise.all([getAnchorSchedule(domain), getAnchorStatus(domain).catch(() => null)]);
      setSchedule(s);
      setStatus(st);
      const raw = s.settings;
      if (raw) {
        form.setFieldsValue({
          enabled: (raw[SCHEDULE_KEYS.enabled] || '').trim().toLowerCase() === 'true',
          intervalMinutes: toNumber(raw[SCHEDULE_KEYS.intervalMinutes]),
          maxUnanchoredEntries: toNumber(raw[SCHEDULE_KEYS.maxUnanchoredEntries]),
          minIntervalMinutes: toNumber(raw[SCHEDULE_KEYS.minIntervalMinutes]),
          upgradeIntervalMinutes: toNumber(raw[SCHEDULE_KEYS.upgradeIntervalMinutes]),
          retryUnsettledIntervalMinutes: toNumber(raw[SCHEDULE_KEYS.retryUnsettledIntervalMinutes]),
        });
      }
    } catch (e) {
      setSchedule(null);
      setLoadError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [domain, form]);

  useEffect(() => {
    load();
  }, [load]);

  const configuredRungs = useMemo(() => (schedule?.rungs || []).filter((r) => r.configured), [schedule]);
  // The one rule the screen enforces before the server does: enabled needs an interval.
  const intervalMissing = enabled === true && (interval === null || interval === undefined);

  const save = async () => {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const str = (v: number | null | undefined) => (v === null || v === undefined ? '' : String(v));
    setSaving(true);
    try {
      const saved = await updateAnchorSchedule(domain, {
        [SCHEDULE_KEYS.enabled]: values.enabled ? 'true' : 'false',
        [SCHEDULE_KEYS.intervalMinutes]: str(values.intervalMinutes),
        [SCHEDULE_KEYS.maxUnanchoredEntries]: str(values.maxUnanchoredEntries),
        [SCHEDULE_KEYS.minIntervalMinutes]: str(values.minIntervalMinutes),
        [SCHEDULE_KEYS.upgradeIntervalMinutes]: str(values.upgradeIntervalMinutes),
        [SCHEDULE_KEYS.retryUnsettledIntervalMinutes]: str(values.retryUnsettledIntervalMinutes),
      });
      setSchedule(saved);
      message.success(t('evidenceAnchoring.saved'));
    } catch (e) {
      if (e instanceof ScheduleRefused && Object.keys(e.errors).length > 0) {
        form.setFields(
          Object.entries(e.errors)
            .filter(([key]) => FIELD_OF_KEY[key])
            .map(([key, why]) => ({ name: FIELD_OF_KEY[key], errors: [why] }))
        );
      }
      message.error(withDetail(t('evidenceAnchoring.saveFailed'), e));
    } finally {
      setSaving(false);
    }
  };

  const runNow = async () => {
    setRunning(true);
    try {
      setRunResult(await checkpointAndAnchorNow(domain));
      await load();
    } catch (e) {
      message.error(withDetail(t('evidenceAnchoring.runFailed'), e));
    } finally {
      setRunning(false);
    }
  };

  const unanchored = schedule?.unanchored;
  const runtime = schedule?.runtime;
  const receipts = (status?.receipts as Array<Record<string, unknown>> | null | undefined) ?? null;

  return (
    <div style={{ padding: 24 }} data-testid="evidence-anchoring-page">
      <h2>{t('evidenceAnchoring.title')}</h2>
      <Paragraph type="secondary">{t('evidenceAnchoring.intro')}</Paragraph>

      <Space style={{ marginBottom: 16 }}>
        <Text strong>{t('evidenceAnchoring.domain')}</Text>
        <Select
          data-testid="anchor-domain-select"
          value={domain}
          style={{ minWidth: 320 }}
          onChange={(v) => {
            setRunResult(null);
            setDomain(v);
          }}
          options={[
            { value: repositoryId, label: t('evidenceAnchoring.domainRepository', { repositoryId }) },
            { value: RECORD_CONTENT_DOMAIN, label: t('evidenceAnchoring.domainRecordContent') },
          ]}
        />
        <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
          {t('common.refresh')}
        </Button>
      </Space>

      {loadError && <Alert type="error" showIcon message={t('evidenceAnchoring.loadFailed')} description={loadError} />}

      <Spin spinning={loading}>
        {schedule && (
          <Space direction="vertical" style={{ width: '100%' }} size="large">
            {configuredRungs.length === 0 && (
              <Alert
                data-testid="anchor-no-rung"
                type="warning"
                showIcon
                message={t('evidenceAnchoring.noRungTitle')}
                description={t('evidenceAnchoring.noRungDescription')}
              />
            )}

            <Card title={t('evidenceAnchoring.scheduleCard')}>
              {schedule.settings === null ? (
                <Alert
                  data-testid="anchor-settings-unreadable"
                  type="error"
                  showIcon
                  message={t('evidenceAnchoring.settingsUnreadable')}
                  description={schedule.settingsUnavailable}
                />
              ) : (
                <Form form={form} layout="vertical" style={{ maxWidth: 560 }}>
                  <Form.Item name="enabled" label={t('evidenceAnchoring.enabled')} valuePropName="checked">
                    <Switch data-testid="anchor-schedule-enabled" />
                  </Form.Item>
                  <Form.Item
                    name="intervalMinutes"
                    label={t('evidenceAnchoring.intervalMinutes')}
                    extra={t('evidenceAnchoring.intervalHint')}
                    rules={[{ type: 'integer', min: 5, message: t('evidenceAnchoring.atLeast', { n: 5 }) }]}
                  >
                    <InputNumber data-testid="anchor-interval" min={1} precision={0} style={{ width: 200 }} />
                  </Form.Item>
                  <Form.Item
                    name="maxUnanchoredEntries"
                    label={t('evidenceAnchoring.maxUnanchoredEntries')}
                    extra={t('evidenceAnchoring.maxHint')}
                    rules={[{ type: 'integer', min: 1, message: t('evidenceAnchoring.atLeast', { n: 1 }) }]}
                  >
                    <InputNumber data-testid="anchor-max" min={1} precision={0} style={{ width: 200 }} />
                  </Form.Item>
                  <Form.Item
                    name="minIntervalMinutes"
                    label={t('evidenceAnchoring.minIntervalMinutes')}
                    extra={t('evidenceAnchoring.minIntervalHint')}
                    rules={[{ type: 'integer', min: 1, message: t('evidenceAnchoring.atLeast', { n: 1 }) }]}
                  >
                    <InputNumber data-testid="anchor-min-interval" min={1} precision={0} style={{ width: 200 }} />
                  </Form.Item>
                  <Form.Item
                    name="upgradeIntervalMinutes"
                    label={t('evidenceAnchoring.upgradeIntervalMinutes')}
                    extra={t('evidenceAnchoring.upgradeHint')}
                    rules={[{ type: 'integer', min: 5, message: t('evidenceAnchoring.atLeast', { n: 5 }) }]}
                  >
                    <InputNumber data-testid="anchor-upgrade-interval" min={1} precision={0} style={{ width: 200 }} />
                  </Form.Item>
                  <Form.Item
                    name="retryUnsettledIntervalMinutes"
                    label={t('evidenceAnchoring.retryUnsettledIntervalMinutes')}
                    extra={t('evidenceAnchoring.retryHint')}
                    rules={[{ type: 'integer', min: 60, message: t('evidenceAnchoring.atLeast', { n: 60 }) }]}
                  >
                    <InputNumber data-testid="anchor-retry-interval" min={1} precision={0} style={{ width: 200 }} />
                  </Form.Item>
                  <Space direction="vertical">
                    <Button
                      type="primary"
                      data-testid="anchor-save"
                      onClick={save}
                      loading={saving}
                      disabled={intervalMissing}
                    >
                      {t('common.save')}
                    </Button>
                    {intervalMissing && (
                      <Text type="danger" data-testid="anchor-save-disabled-reason">
                        {t('evidenceAnchoring.intervalRequired')}
                      </Text>
                    )}
                  </Space>
                </Form>
              )}
              {schedule.errors && Object.keys(schedule.errors).length > 0 && (
                <Alert
                  style={{ marginTop: 16 }}
                  type="warning"
                  showIcon
                  message={t('evidenceAnchoring.effectiveInvalid')}
                  description={Object.entries(schedule.errors).map(([k, v]) => `${k}: ${v}`).join(' / ')}
                />
              )}
            </Card>

            <Card title={t('evidenceAnchoring.stateCard')}>
              <Descriptions column={1} size="small" bordered>
                <Descriptions.Item label={t('evidenceAnchoring.unanchoredCount')}>
                  <span data-testid="anchor-unanchored">
                    {unanchored?.status === 'OK' ? (
                      <>
                        {unanchored.count}
                        {unanchored.countIsLowerBound ? ` ${t('evidenceAnchoring.orMore')}` : ''}
                      </>
                    ) : (
                      <Text type="danger">
                        {withDetail(t('evidenceAnchoring.notReadable'), unanchored?.reason)}
                      </Text>
                    )}
                  </span>
                </Descriptions.Item>
                {unanchored?.status === 'OK' && (
                  <>
                    <Descriptions.Item label={t('evidenceAnchoring.oldestUnanchored')}>
                      {unanchored.oldestAt ?? (unanchored.oldestAtUnreadable
                        ? `${t('evidenceAnchoring.notReadable')} — ${unanchored.oldestAtUnreadable}` : '—')}
                    </Descriptions.Item>
                    <Descriptions.Item label={t('evidenceAnchoring.unsealedCount')}>
                      {unanchored.unsealedCount}
                    </Descriptions.Item>
                    <Descriptions.Item label={t('evidenceAnchoring.lastSealAt')}>{unanchored.lastSealAt ?? '—'}</Descriptions.Item>
                  </>
                )}
                <Descriptions.Item label={t('evidenceAnchoring.lastOutcome')}>
                  {runtime?.lastOutcome ? (
                    <>
                      <Tag>{runtime.lastOutcome}</Tag> {runtime.lastOutcomeAt} {runtime.lastReason}
                    </>
                  ) : (
                    t('evidenceAnchoring.noTickYet')
                  )}
                </Descriptions.Item>
                {runtime?.lastUpgradeOutcome && (
                  <Descriptions.Item label={t('evidenceAnchoring.lastUpgrade')}>
                    {runtime.lastUpgradeAt} {runtime.lastUpgradeOutcome}
                  </Descriptions.Item>
                )}
                {runtime?.lastRetryOutcome && (
                  <Descriptions.Item label={t('evidenceAnchoring.lastRetry')}>
                    {runtime.lastRetryAt} {runtime.lastRetryOutcome}
                  </Descriptions.Item>
                )}
                <Descriptions.Item label={t('evidenceAnchoring.leader')}>
                  {runtime?.leader === null || runtime?.leader === undefined
                    ? t('evidenceAnchoring.leaderUnknown')
                    : runtime.leader ? t('evidenceAnchoring.isLeader') : t('evidenceAnchoring.notLeader')}
                  {runtime?.nodeId ? ` (${t('evidenceAnchoring.nodeLabel', { nodeId: runtime.nodeId })})` : ''}
                </Descriptions.Item>
              </Descriptions>
              {runtime && !runtime.leaderElectionEnabled && (
                <Alert style={{ marginTop: 12 }} type="info" showIcon message={t('evidenceAnchoring.electionOff')} />
              )}
              {receipts !== null && receipts.length > 0 && (
                <Table
                  style={{ marginTop: 12 }}
                  size="small"
                  pagination={false}
                  rowKey={(r) => String(r.rung)}
                  dataSource={receipts}
                  columns={[
                    { title: t('evidenceAnchoring.rung'), dataIndex: 'rung' },
                    { title: t('evidenceAnchoring.receiptStatus'), dataIndex: 'status' },
                    { title: t('evidenceAnchoring.anchoredAt'), dataIndex: 'anchoredAt' },
                    // A FAILED row read the same whether the rung was asked and failed or refused its
                    // configuration and was never asked (c45). /status marks the second with the
                    // product's own refusal and never sends a stored reason, which can be free text
                    // holding a destination's user:password (c46).
                    { title: t('evidenceAnchoring.notAsked'), dataIndex: 'notAsked' },
                  ]}
                />
              )}
              <Paragraph type="secondary" style={{ marginTop: 12 }}>
                {withDetail(t('evidenceAnchoring.scheduleLimitsLead'), schedule.scheduleLimits)}
              </Paragraph>
            </Card>

            <Card title={t('evidenceAnchoring.destinationsCard')}>
              <Descriptions column={1} size="small" bordered>
                <Descriptions.Item label={t('evidenceAnchoring.tsaUrl')}>
                  {schedule.destinations.tsaUrl ?? t('evidenceAnchoring.rungOff')}
                </Descriptions.Item>
                <Descriptions.Item label={t('evidenceAnchoring.policyOid')}>
                  {schedule.destinations.policyOid ?? '—'}
                </Descriptions.Item>
                <Descriptions.Item label={t('evidenceAnchoring.trustAnchor')}>
                  {schedule.destinations.trustAnchorConfigured
                    ? t('evidenceAnchoring.trustAnchorSet') : t('evidenceAnchoring.trustAnchorNotSet')}
                </Descriptions.Item>
                <Descriptions.Item label={t('evidenceAnchoring.otsUrl')}>
                  {schedule.destinations.otsSidecarUrl ?? t('evidenceAnchoring.otsOff')}
                </Descriptions.Item>
              </Descriptions>
              <Paragraph type="secondary" style={{ marginTop: 12 }}>{t('evidenceAnchoring.destinationsReadOnly')}</Paragraph>
            </Card>

            <Card title={t('evidenceAnchoring.runNowCard')}>
              <Paragraph>{t('evidenceAnchoring.runNowDescription')}</Paragraph>
              <Button icon={<ThunderboltOutlined />} onClick={runNow} loading={running} data-testid="anchor-run-now">
                {t('evidenceAnchoring.runNow')}
              </Button>
              {runResult && (
                <pre data-testid="anchor-run-result" style={{ marginTop: 12, maxHeight: 320, overflow: 'auto', background: '#fafafa', padding: 12 }}>
                  {`HTTP ${runResult.httpStatus}\n${JSON.stringify(runResult.body, null, 2)}`}
                </pre>
              )}
            </Card>
          </Space>
        )}
      </Spin>
    </div>
  );
};

export default EvidenceAnchoring;

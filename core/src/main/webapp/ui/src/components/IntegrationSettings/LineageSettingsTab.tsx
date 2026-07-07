import { useMemo } from 'react';
import { Form, Button, Spin, Alert, App } from 'antd';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useSettingsTab } from './useSettingsTab';
import { SettingsFormFields } from './SettingsFormFields';
import { getLineageSettings, updateLineageSettings } from '../../services/integrationSettings';

const MODE_OPTIONS = [
  { value: 'disabled', labelKey: 'integrationSettings.lineage.modeDisabled' },
  { value: 'direct', labelKey: 'integrationSettings.lineage.modeDirect' },
  { value: 'journaled', labelKey: 'integrationSettings.lineage.modeJournaled' },
];

export function LineageSettingsTab() {
  const { t } = useTranslation();
  const { message } = App.useApp();

  const {
    sources,
    overridable,
    formValues,
    loading,
    saving,
    hasChanges,
    handleSave,
    updateField,
  } = useSettingsTab({
    fetchSettings: getLineageSettings,
    saveSettings: updateLineageSettings,
  });

  const mode = formValues['lineage.mode'] || 'disabled';

  const fields = useMemo(() => [
    { key: 'lineage.mode', labelKey: 'integrationSettings.lineage.mode', type: 'select' as const, options: MODE_OPTIONS, helpKey: 'integrationSettings.lineage.modeHelp' },
    { key: 'lineage.targets', labelKey: 'integrationSettings.lineage.targets', type: 'text' as const, placeholder: 'purview, atlas, dataplex', helpKey: 'integrationSettings.lineage.targetsHelp' },
    { key: 'lineage.retention.days', labelKey: 'integrationSettings.lineage.retentionDays', type: 'text' as const, placeholder: '90', helpKey: 'integrationSettings.lineage.retentionDaysHelp' },
    { key: 'lineage.capture.version-events', labelKey: 'integrationSettings.lineage.captureVersionEvents', type: 'boolean' as const },
    { key: 'lineage.capture.generic-relationships', labelKey: 'integrationSettings.lineage.captureGenericRelationships', type: 'boolean' as const },
    { key: 'lineage.purge.cron', labelKey: 'integrationSettings.lineage.purgeCron', type: 'text' as const, helpKey: 'integrationSettings.lineage.purgeCronHelp' },
    { key: 'lineage.snapshot.max-name-length', labelKey: 'integrationSettings.lineage.snapshotMaxNameLength', type: 'text' as const, placeholder: '512' },
    { key: 'lineage.snapshot.max-path-length', labelKey: 'integrationSettings.lineage.snapshotMaxPathLength', type: 'text' as const, placeholder: '2048' },
    { key: 'lineage.snapshot.capture-path', labelKey: 'integrationSettings.lineage.snapshotCapturePath', type: 'boolean' as const },
    { key: 'lineage.backlog.max-retry-count', labelKey: 'integrationSettings.lineage.backlogMaxRetryCount', type: 'text' as const, placeholder: '5', helpKey: 'integrationSettings.lineage.backlogMaxRetryCountHelp' },
    { key: 'lineage.backlog.max-retry-age-hours', labelKey: 'integrationSettings.lineage.backlogMaxRetryAgeHours', type: 'text' as const, placeholder: '72', helpKey: 'integrationSettings.lineage.backlogMaxRetryAgeHoursHelp' },
    { key: 'lineage.backlog.max-docs', labelKey: 'integrationSettings.lineage.backlogMaxDocs', type: 'text' as const, placeholder: '10000', helpKey: 'integrationSettings.lineage.backlogMaxDocsHelp' },
    { key: 'lineage.backlog.max-size-mb', labelKey: 'integrationSettings.lineage.backlogMaxSizeMb', type: 'text' as const, placeholder: '100', helpKey: 'integrationSettings.lineage.backlogMaxSizeMbHelp' },
    { key: 'lineage.projection.poll-interval-seconds', labelKey: 'integrationSettings.lineage.projectionPollInterval', type: 'text' as const, placeholder: '10', helpKey: 'integrationSettings.lineage.projectionPollIntervalHelp' },
    { key: 'lineage.projection.batch-size', labelKey: 'integrationSettings.lineage.projectionBatchSize', type: 'text' as const, placeholder: '50', helpKey: 'integrationSettings.lineage.projectionBatchSizeHelp' },
    { key: 'lineage.projection.stale-threshold-minutes', labelKey: 'integrationSettings.lineage.projectionStaleThreshold', type: 'text' as const, placeholder: '5', helpKey: 'integrationSettings.lineage.projectionStaleThresholdHelp' },
    { key: 'lineage.leader-election.enabled', labelKey: 'integrationSettings.lineage.leaderElectionEnabled', type: 'boolean' as const, helpKey: 'integrationSettings.lineage.leaderElectionEnabledHelp' },
    { key: 'lineage.leader-election.heartbeat-seconds', labelKey: 'integrationSettings.lineage.leaderHeartbeatSeconds', type: 'text' as const, placeholder: '15', helpKey: 'integrationSettings.lineage.leaderHeartbeatSecondsHelp' },
    { key: 'lineage.leader-election.ttl-seconds', labelKey: 'integrationSettings.lineage.leaderTtlSeconds', type: 'text' as const, placeholder: '60', helpKey: 'integrationSettings.lineage.leaderTtlSecondsHelp' },
  ], []);

  const onSave = async () => {
    const success = await handleSave();
    if (success) {
      message.success(t('integrationSettings.saveSuccess'));
    } else {
      message.error(t('integrationSettings.saveError'));
    }
  };

  if (loading) return <Spin />;

  return (
    <Form layout="vertical" style={{ maxWidth: 600 }}>
      <Alert
        message={
          mode === 'disabled'
            ? t('integrationSettings.lineage.disabledNotice')
            : mode === 'journaled'
              ? t('integrationSettings.lineage.journaledNotice')
              : t('integrationSettings.lineage.directNotice')
        }
        type={mode === 'disabled' ? 'warning' : 'info'}
        showIcon
        style={{ marginBottom: 16 }}
      />

      {mode === 'journaled' && (
        <Alert
          message={<>
            {t('integrationSettings.lineage.journalLinkNotice')}{' '}
            <Link to="/lineage-journal">{t('integrationSettings.lineage.journalLinkLabel')}</Link>
          </>}
          type="info"
          style={{ marginBottom: 16 }}
        />
      )}

      <SettingsFormFields
        fields={fields}
        formValues={formValues}
        sources={sources}
        overridable={overridable}
        onFieldChange={updateField}
      />

      <Form.Item>
        <Button type="primary" onClick={onSave} loading={saving} disabled={!hasChanges}>
          {t('integrationSettings.save')}
        </Button>
      </Form.Item>
    </Form>
  );
}

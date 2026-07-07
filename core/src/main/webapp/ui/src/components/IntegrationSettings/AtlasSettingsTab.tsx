import { useMemo } from 'react';
import { Form, Button, Space, Spin, Alert, App, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { useSettingsTab } from './useSettingsTab';
import { SettingsFormFields } from './SettingsFormFields';
import { getAtlasSettings, updateAtlasSettings, testAtlasConnection } from '../../services/integrationSettings';

export function AtlasSettingsTab() {
  const { t } = useTranslation();
  const { message } = App.useApp();

  const {
    sources,
    overridable,
    formValues,
    loading,
    saving,
    testing,
    testResult,
    saveWarning,
    hasChanges,
    handleSave,
    handleTestConnection,
    updateField,
  } = useSettingsTab({
    fetchSettings: getAtlasSettings,
    saveSettings: updateAtlasSettings,
    testConnection: testAtlasConnection,
  });

  const fields = useMemo(() => [
    { key: 'atlas.enabled', labelKey: 'integrationSettings.atlas.enabled', type: 'boolean' as const },
    { key: 'atlas.endpoint', labelKey: 'integrationSettings.atlas.endpoint', type: 'text' as const, placeholder: 'http://localhost:21000', helpKey: 'integrationSettings.atlas.endpointHelp' },
    { key: 'atlas.username', labelKey: 'integrationSettings.atlas.username', type: 'text' as const },
    { key: 'atlas.password', labelKey: 'integrationSettings.atlas.password', type: 'password' as const, sensitive: true },
    { key: 'atlas.collection', labelKey: 'integrationSettings.atlas.collection', type: 'text' as const, placeholder: 'NemakiWare' },
    { key: 'atlas.sync.cron', labelKey: 'integrationSettings.atlas.syncCron', type: 'text' as const, helpKey: 'integrationSettings.atlas.syncCronHelp' },
  ], []);

  const onSave = async () => {
    const success = await handleSave();
    if (success) {
      message.success(t('integrationSettings.saveSuccess'));
    } else {
      message.error(t('integrationSettings.saveError'));
    }
  };

  const isEnabled = formValues['atlas.enabled'] === 'true';

  if (loading) return <Spin />;

  return (
    <Form layout="vertical" style={{ maxWidth: 600 }}>
      <Alert
        message={
          <Space>
            <Tag color="blue">Beta</Tag>
            {t('integrationSettings.atlas.notice')}
          </Space>
        }
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
      />

      {!isEnabled && (
        <Alert
          message={t('integrationSettings.atlas.disabledNotice')}
          type="warning"
          showIcon
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

      {saveWarning && (
        <Alert
          message={t('integrationSettings.dualBackendWarning')}
          description={t('integrationSettings.dualBackendWarningDescription')}
          type="warning"
          showIcon
          closable
          style={{ marginBottom: 16 }}
        />
      )}

      {testResult && (
        <Alert
          message={testResult.status === 'success'
            ? t('integrationSettings.connectionSuccess')
            : testResult.status === 'disabled'
              ? t('integrationSettings.connectionDisabled')
              : t('integrationSettings.connectionFailure')}
          description={testResult.message}
          type={testResult.status === 'success' ? 'success' : testResult.status === 'disabled' ? 'warning' : 'error'}
          showIcon
          closable
          style={{ marginBottom: 16 }}
        />
      )}

      <Form.Item>
        <Space>
          <Button type="primary" onClick={onSave} loading={saving} disabled={!hasChanges}>
            {t('integrationSettings.save')}
          </Button>
          <Button onClick={handleTestConnection} loading={testing}>
            {t('integrationSettings.atlas.testConnection')}
          </Button>
        </Space>
      </Form.Item>
    </Form>
  );
}

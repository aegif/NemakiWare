import { useMemo } from 'react';
import { Form, Button, Space, Spin, Alert, App, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { useSettingsTab } from './useSettingsTab';
import { SettingsFormFields } from './SettingsFormFields';
import { getPurviewSettings, updatePurviewSettings, testPurviewConnection } from '../../services/integrationSettings';

const BASE_PATH_OPTIONS = [
  { value: 'datamap/api/atlas/v2', labelKey: 'integrationSettings.purview.basePathPurview' },
  { value: 'catalog/api/atlas/v2', labelKey: 'integrationSettings.purview.basePathCatalog' },
];

export function PurviewSettingsTab() {
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
    fetchSettings: getPurviewSettings,
    saveSettings: updatePurviewSettings,
    testConnection: testPurviewConnection,
  });

  const fields = useMemo(() => [
    { key: 'purview.enabled', labelKey: 'integrationSettings.purview.enabled', type: 'boolean' as const },
    { key: 'purview.endpoint', labelKey: 'integrationSettings.purview.endpoint', type: 'text' as const, placeholder: 'https://account.purview.azure.com' },
    { key: 'purview.atlas.base-path', labelKey: 'integrationSettings.purview.atlasBasePath', type: 'select' as const, options: BASE_PATH_OPTIONS },
    { key: 'purview.tenant.id', labelKey: 'integrationSettings.purview.tenantId', type: 'text' as const },
    { key: 'purview.client.id', labelKey: 'integrationSettings.purview.clientId', type: 'text' as const },
    { key: 'purview.client.secret', labelKey: 'integrationSettings.purview.clientSecret', type: 'password' as const, sensitive: true },
    { key: 'purview.collection', labelKey: 'integrationSettings.purview.collection', type: 'text' as const, placeholder: 'default' },
    { key: 'purview.sync.cron', labelKey: 'integrationSettings.purview.syncCron', type: 'text' as const, helpKey: 'integrationSettings.purview.syncCronHelp' },
  ], []);

  const onSave = async () => {
    const success = await handleSave();
    if (success) {
      message.success(t('integrationSettings.saveSuccess'));
    } else {
      message.error(t('integrationSettings.saveError'));
    }
  };

  const isEnabled = formValues['purview.enabled'] === 'true';

  if (loading) return <Spin />;

  return (
    <Form layout="vertical" style={{ maxWidth: 600 }}>
      <Alert
        message={
          <Space>
            <Tag color="blue">Beta</Tag>
            {isEnabled
              ? t('integrationSettings.purview.betaNotice')
              : t('integrationSettings.purview.disabledNotice')}
          </Space>
        }
        type={isEnabled ? 'info' : 'warning'}
        showIcon
        style={{ marginBottom: 16 }}
      />

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
            {t('integrationSettings.testConnection')}
          </Button>
        </Space>
      </Form.Item>
    </Form>
  );
}

import { Form, Button, Space, Spin, Alert, App } from 'antd';
import { useTranslation } from 'react-i18next';
import { useSettingsTab } from './useSettingsTab';
import { SettingsFormFields } from './SettingsFormFields';
import { getPurviewSettings, updatePurviewSettings, testPurviewConnection } from '../../services/integrationSettings';

const FIELDS = [
  { key: 'purview.enabled', labelKey: 'integrationSettings.purview.enabled', type: 'boolean' as const },
  { key: 'purview.endpoint', labelKey: 'integrationSettings.purview.endpoint', type: 'text' as const },
  { key: 'purview.tenant.id', labelKey: 'integrationSettings.purview.tenantId', type: 'text' as const },
  { key: 'purview.client.id', labelKey: 'integrationSettings.purview.clientId', type: 'text' as const },
  { key: 'purview.client.secret', labelKey: 'integrationSettings.purview.clientSecret', type: 'password' as const, sensitive: true },
  { key: 'purview.collection', labelKey: 'integrationSettings.purview.collection', type: 'text' as const },
];

export function PurviewSettingsTab() {
  const { t } = useTranslation();
  const { message } = App.useApp();

  const {
    sources,
    formValues,
    loading,
    saving,
    testing,
    testResult,
    hasChanges,
    handleSave,
    handleTestConnection,
    updateField,
  } = useSettingsTab({
    fetchSettings: getPurviewSettings,
    saveSettings: updatePurviewSettings,
    testConnection: testPurviewConnection,
  });

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
      <SettingsFormFields
        fields={FIELDS}
        formValues={formValues}
        sources={sources}
        onFieldChange={updateField}
      />

      {testResult && (
        <Alert
          message={testResult.status === 'success'
            ? t('integrationSettings.connectionSuccess')
            : t('integrationSettings.connectionFailure')}
          description={testResult.message}
          type={testResult.status === 'success' ? 'success' : 'error'}
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

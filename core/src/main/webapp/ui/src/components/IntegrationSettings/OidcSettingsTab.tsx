import { Form, Button, Space, Spin, Alert, App } from 'antd';
import { useTranslation } from 'react-i18next';
import { useSettingsTab } from './useSettingsTab';
import { SettingsFormFields } from './SettingsFormFields';
import { getOidcSettings, updateOidcSettings, testOidcConnection } from '../../services/integrationSettings';

const FIELDS = [
  { key: 'sso.oidc.enabled', labelKey: 'integrationSettings.oidc.enabled', type: 'boolean' as const },
  { key: 'oidc.issuer', labelKey: 'integrationSettings.oidc.issuer', type: 'text' as const },
  { key: 'oidc.clientId', labelKey: 'integrationSettings.oidc.clientId', type: 'text' as const },
];

export function OidcSettingsTab() {
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
    fetchSettings: getOidcSettings,
    saveSettings: updateOidcSettings,
    testConnection: testOidcConnection,
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

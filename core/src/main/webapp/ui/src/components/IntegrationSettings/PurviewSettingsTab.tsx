import { useMemo } from 'react';
import { Form, Button, Space, Spin, Alert, App, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { useSettingsTab } from './useSettingsTab';
import { SettingsFormFields } from './SettingsFormFields';
import { getPurviewSettings, updatePurviewSettings, testPurviewConnection } from '../../services/integrationSettings';

const AUTH_TYPE_OPTIONS = [
  { value: 'oauth2', labelKey: 'integrationSettings.purview.authTypeOAuth2' },
  { value: 'basic', labelKey: 'integrationSettings.purview.authTypeBasic' },
];

const BASE_PATH_OPTIONS = [
  { value: 'datamap/api/atlas/v2', labelKey: 'integrationSettings.purview.basePathPurview' },
  { value: 'catalog/api/atlas/v2', labelKey: 'integrationSettings.purview.basePathCatalog' },
  { value: 'api/atlas/v2', labelKey: 'integrationSettings.purview.basePathAtlas' },
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

  const authType = formValues['purview.auth.type'] || 'oauth2';

  const fields = useMemo(() => {
    const common = [
      { key: 'purview.enabled', labelKey: 'integrationSettings.purview.enabled', type: 'boolean' as const },
      { key: 'purview.auth.type', labelKey: 'integrationSettings.purview.authType', type: 'select' as const, options: AUTH_TYPE_OPTIONS },
      { key: 'purview.endpoint', labelKey: 'integrationSettings.purview.endpoint', type: 'text' as const, placeholder: 'http://atlas-manual:21000' },
      { key: 'purview.atlas.base-path', labelKey: 'integrationSettings.purview.atlasBasePath', type: 'select' as const, options: BASE_PATH_OPTIONS },
    ];

    const oauth2Fields = [
      { key: 'purview.tenant.id', labelKey: 'integrationSettings.purview.tenantId', type: 'text' as const },
      { key: 'purview.client.id', labelKey: 'integrationSettings.purview.clientId', type: 'text' as const },
      { key: 'purview.client.secret', labelKey: 'integrationSettings.purview.clientSecret', type: 'password' as const, sensitive: true },
    ];

    const basicFields = [
      { key: 'purview.basic.username', labelKey: 'integrationSettings.purview.basicUsername', type: 'text' as const },
      { key: 'purview.basic.password', labelKey: 'integrationSettings.purview.basicPassword', type: 'password' as const, sensitive: true },
    ];

    const authFields = authType === 'basic' ? basicFields : oauth2Fields;

    return [
      ...common,
      ...authFields,
      { key: 'purview.collection', labelKey: 'integrationSettings.purview.collection', type: 'text' as const, placeholder: 'default' },
      { key: 'purview.sync.cron', labelKey: 'integrationSettings.purview.syncCron', type: 'text' as const, helpKey: 'integrationSettings.purview.syncCronHelp' },
    ];
  }, [authType]);

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
        onFieldChange={updateField}
      />

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

import { useMemo } from 'react';
import { Form, Button, Spin, Alert, App } from 'antd';
import { useTranslation } from 'react-i18next';
import { useSettingsTab } from './useSettingsTab';
import { SettingsFormFields } from './SettingsFormFields';
import { getDataplexSettings, updateDataplexSettings } from '../../services/integrationSettings';

export function DataplexSettingsTab() {
  const { t } = useTranslation();
  const { message } = App.useApp();

  const {
    sources,
    formValues,
    loading,
    saving,
    hasChanges,
    handleSave,
    updateField,
  } = useSettingsTab({
    fetchSettings: getDataplexSettings,
    saveSettings: updateDataplexSettings,
  });

  const fields = useMemo(() => [
    { key: 'dataplex.enabled', labelKey: 'integrationSettings.dataplex.enabled', type: 'boolean' as const },
    { key: 'dataplex.project-id', labelKey: 'integrationSettings.dataplex.projectId', type: 'text' as const, placeholder: 'my-project-123', helpKey: 'integrationSettings.dataplex.projectIdHelp' },
    { key: 'dataplex.location', labelKey: 'integrationSettings.dataplex.location', type: 'text' as const, placeholder: 'us-central1', helpKey: 'integrationSettings.dataplex.locationHelp' },
    { key: 'dataplex.credentials-file', labelKey: 'integrationSettings.dataplex.credentialsFile', type: 'text' as const, helpKey: 'integrationSettings.dataplex.credentialsFileHelp' },
  ], []);

  const onSave = async () => {
    const success = await handleSave();
    if (success) {
      message.success(t('integrationSettings.saveSuccess'));
    } else {
      message.error(t('integrationSettings.saveError'));
    }
  };

  const isEnabled = formValues['dataplex.enabled'] === 'true';

  if (loading) return <Spin />;

  return (
    <Form layout="vertical" style={{ maxWidth: 600 }}>
      <Alert
        message={t('integrationSettings.dataplex.notice')}
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
      />

      {!isEnabled && (
        <Alert
          message={t('integrationSettings.dataplex.enabled')}
          description={t('integrationSettings.dataplex.title')}
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      <SettingsFormFields
        fields={fields}
        formValues={formValues}
        sources={sources}
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

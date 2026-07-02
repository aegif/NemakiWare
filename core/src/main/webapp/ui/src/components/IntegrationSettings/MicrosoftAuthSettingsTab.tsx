import { Form, Button, Spin, App } from 'antd';
import { useTranslation } from 'react-i18next';
import { useSettingsTab } from './useSettingsTab';
import { SettingsFormFields } from './SettingsFormFields';
import { getMicrosoftAuthSettings, updateMicrosoftAuthSettings } from '../../services/integrationSettings';

const FIELDS = [
  { key: 'cloud.auth.microsoft.enabled', labelKey: 'integrationSettings.microsoft.enabled', type: 'boolean' as const },
  { key: 'cloud.auth.microsoft.clientId', labelKey: 'integrationSettings.microsoft.clientId', type: 'text' as const },
  { key: 'cloud.auth.microsoft.tenantId', labelKey: 'integrationSettings.microsoft.tenantId', type: 'text' as const },
];

export function MicrosoftAuthSettingsTab() {
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
    fetchSettings: getMicrosoftAuthSettings,
    saveSettings: updateMicrosoftAuthSettings,
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

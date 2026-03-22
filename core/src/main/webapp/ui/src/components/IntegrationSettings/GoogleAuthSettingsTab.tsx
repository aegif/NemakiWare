import { Form, Button, Spin, App } from 'antd';
import { useTranslation } from 'react-i18next';
import { useSettingsTab } from './useSettingsTab';
import { SettingsFormFields } from './SettingsFormFields';
import { getGoogleAuthSettings, updateGoogleAuthSettings } from '../../services/integrationSettings';

const FIELDS = [
  { key: 'cloud.auth.google.enabled', labelKey: 'integrationSettings.google.enabled', type: 'boolean' as const },
  { key: 'cloud.auth.google.clientId', labelKey: 'integrationSettings.google.clientId', type: 'text' as const },
];

export function GoogleAuthSettingsTab() {
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
    fetchSettings: getGoogleAuthSettings,
    saveSettings: updateGoogleAuthSettings,
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
      <Form.Item>
        <Button type="primary" onClick={onSave} loading={saving} disabled={!hasChanges}>
          {t('integrationSettings.save')}
        </Button>
      </Form.Item>
    </Form>
  );
}

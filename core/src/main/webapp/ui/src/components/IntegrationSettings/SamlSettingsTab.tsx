import { Form, Button, Spin, App } from 'antd';
import { useTranslation } from 'react-i18next';
import { useSettingsTab } from './useSettingsTab';
import { SettingsFormFields } from './SettingsFormFields';
import { getSamlSettings, updateSamlSettings } from '../../services/integrationSettings';

const FIELDS = [
  { key: 'sso.saml.enabled', labelKey: 'integrationSettings.saml.enabled', type: 'boolean' as const },
  { key: 'saml.idp.sso.url', labelKey: 'integrationSettings.saml.idpSsoUrl', type: 'text' as const },
  { key: 'saml.sp.entity.id', labelKey: 'integrationSettings.saml.spEntityId', type: 'text' as const },
  { key: 'saml.idp.certificate', labelKey: 'integrationSettings.saml.idpCertificate', type: 'password' as const, sensitive: true },
  { key: 'saml.slo.url', labelKey: 'integrationSettings.saml.sloUrl', type: 'text' as const },
  { key: 'saml.attribute.mapping', labelKey: 'integrationSettings.saml.attributeMapping', type: 'textarea' as const },
];

export function SamlSettingsTab() {
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
    fetchSettings: getSamlSettings,
    saveSettings: updateSamlSettings,
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

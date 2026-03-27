import { useMemo } from 'react';
import { Form, Button, Space, Spin, Alert, App } from 'antd';
import { useTranslation } from 'react-i18next';
import { useSettingsTab } from './useSettingsTab';
import { SettingsFormFields } from './SettingsFormFields';
import { getDirectorySyncSettings, updateDirectorySyncSettings } from '../../services/integrationSettings';
import type { FieldDef } from './SettingsFormFields';

export function DirectorySyncSettingsTab() {
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
    fetchSettings: getDirectorySyncSettings,
    saveSettings: updateDirectorySyncSettings,
  });

  const fields: FieldDef[] = useMemo(() => [
    { key: 'directory.sync.schedule.enabled', labelKey: 'integrationSettings.directorySync.ldapScheduleEnabled', type: 'boolean' as const },
    { key: 'directory.sync.schedule.cron', labelKey: 'integrationSettings.directorySync.ldapScheduleCron', type: 'text' as const, helpKey: 'integrationSettings.directorySync.cronHelp' },
    { key: 'cloud.directory.sync.enabled', labelKey: 'integrationSettings.directorySync.cloudEnabled', type: 'boolean' as const },
    { key: 'cloud.directory.sync.cron', labelKey: 'integrationSettings.directorySync.cloudCron', type: 'text' as const, helpKey: 'integrationSettings.directorySync.cronHelp' },
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
        message={t('integrationSettings.directorySync.notice')}
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
      />

      <SettingsFormFields
        fields={fields}
        formValues={formValues}
        sources={sources}
        onFieldChange={updateField}
      />

      <Form.Item>
        <Space>
          <Button type="primary" onClick={onSave} loading={saving} disabled={!hasChanges}>
            {t('integrationSettings.save')}
          </Button>
        </Space>
      </Form.Item>
    </Form>
  );
}

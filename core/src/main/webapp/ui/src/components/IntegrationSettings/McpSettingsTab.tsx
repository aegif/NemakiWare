import { Form, Switch, Button, Spin, Alert, Descriptions, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useSettingsTab } from './useSettingsTab';
import { getMcpSettings, updateMcpSettings } from '../../services/integrationSettings';

const { Paragraph } = Typography;

export function McpSettingsTab() {
  const { t } = useTranslation();
  const {
    formValues,
    loading,
    saving,
    hasChanges,
    handleSave,
    updateField,
  } = useSettingsTab({
    fetchSettings: getMcpSettings,
    saveSettings: updateMcpSettings,
  });

  if (loading) return <Spin />;

  const isPublic = formValues['mcp.tools.list.public'] !== 'false';

  return (
    <div>
      <Paragraph type="secondary">
        {t('integrationSettings.mcp.description')}
      </Paragraph>

      <Form layout="vertical" style={{ maxWidth: 600 }}>
        <Form.Item
          label={t('integrationSettings.mcp.toolsListPublic')}
          extra={t('integrationSettings.mcp.toolsListPublicHelp')}
        >
          <Switch
            checked={isPublic}
            onChange={(checked) => updateField('mcp.tools.list.public', checked ? 'true' : 'false')}
          />
        </Form.Item>

        <Form.Item>
          <Button type="primary" onClick={handleSave} loading={saving} disabled={!hasChanges}>
            {t('common.save')}
          </Button>
        </Form.Item>
      </Form>

      <Alert
        type="info"
        showIcon
        message={t('integrationSettings.mcp.note')}
        description={
          <Descriptions size="small" column={1} bordered>
            <Descriptions.Item label="/core/mcp/message">
              {t('integrationSettings.mcp.endpointMessage')}
            </Descriptions.Item>
            <Descriptions.Item label="/core/mcp/info">
              {t('integrationSettings.mcp.endpointInfo')}
            </Descriptions.Item>
            <Descriptions.Item label="/core/mcp/health">
              {t('integrationSettings.mcp.endpointHealth')}
            </Descriptions.Item>
          </Descriptions>
        }
      />
    </div>
  );
}

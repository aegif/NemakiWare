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
        {t('integrationSettings.mcp.description',
          'MCP（Model Context Protocol）サーバーの設定を管理します。Claude Code 等の AI ツールからリポジトリにアクセスする際の動作を制御します。')}
      </Paragraph>

      <Form layout="vertical" style={{ maxWidth: 600 }}>
        <Form.Item
          label={t('integrationSettings.mcp.toolsListPublic', 'ツールリスト公開')}
          extra={t('integrationSettings.mcp.toolsListPublicHelp',
            'オンにすると、認証なしで MCP ツール一覧（tools/list）にアクセスできます。MCP クライアントの互換性のためにデフォルトでオンです。インターネット公開環境ではオフにすることを推奨します。')}
        >
          <Switch
            checked={isPublic}
            onChange={(checked) => updateField('mcp.tools.list.public', checked ? 'true' : 'false')}
          />
        </Form.Item>

        <Form.Item>
          <Button type="primary" onClick={handleSave} loading={saving} disabled={!hasChanges}>
            {t('common.save', '保存')}
          </Button>
        </Form.Item>
      </Form>

      <Alert
        type="info"
        showIcon
        message={t('integrationSettings.mcp.note', 'MCP エンドポイント')}
        description={
          <Descriptions size="small" column={1} bordered>
            <Descriptions.Item label="/core/mcp/message">
              {t('integrationSettings.mcp.endpointMessage', 'メインエンドポイント（JSON-RPC）')}
            </Descriptions.Item>
            <Descriptions.Item label="/core/mcp/info">
              {t('integrationSettings.mcp.endpointInfo', 'サーバー情報（常時公開）')}
            </Descriptions.Item>
            <Descriptions.Item label="/core/mcp/health">
              {t('integrationSettings.mcp.endpointHealth', 'ヘルスチェック（常時公開）')}
            </Descriptions.Item>
          </Descriptions>
        }
      />
    </div>
  );
}

import React, { useState, useMemo } from 'react';
import { Card, Tag, Button, Space, Alert, Typography, Tooltip, message, Descriptions } from 'antd';
import { CopyOutlined, CheckOutlined, WarningOutlined, CloudOutlined, ApiOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

const { Text, Title } = Typography;

interface ExternalContextTabProps {
  context: string | null;
  sourceType: string | null;
  sourceId: string | null;
  updatedAt: string | null;
}

// Source type display configuration
const SOURCE_TYPE_CONFIG: Record<string, { color: string; icon: React.ReactNode }> = {
  cloud_sync: { color: 'blue', icon: <CloudOutlined /> },
  crm: { color: 'purple', icon: <ApiOutlined /> },
  erp: { color: 'orange', icon: <ApiOutlined /> },
  chat: { color: 'green', icon: <ApiOutlined /> },
  email: { color: 'cyan', icon: <ApiOutlined /> },
};

// Source ID display configuration
const SOURCE_ID_CONFIG: Record<string, { label: string; color: string }> = {
  google: { label: 'Google', color: 'blue' },
  microsoft: { label: 'Microsoft', color: 'cyan' },
  salesforce: { label: 'Salesforce', color: 'blue' },
  hubspot: { label: 'HubSpot', color: 'orange' },
  sap: { label: 'SAP', color: 'blue' },
  oracle: { label: 'Oracle', color: 'red' },
  slack: { label: 'Slack', color: 'purple' },
  teams: { label: 'Teams', color: 'blue' },
};

export const ExternalContextTab: React.FC<ExternalContextTabProps> = ({
  context,
  sourceType,
  sourceId,
  updatedAt,
}) => {
  const { t, i18n } = useTranslation();
  const [copied, setCopied] = useState(false);

  // Format date with validation and i18n locale support
  const formatDate = (dateString: string | null): string | null => {
    if (!dateString) return null;
    const date = new Date(dateString);
    if (isNaN(date.getTime())) return null; // Invalid date
    const locale = i18n.language === 'ja' ? 'ja-JP' : 'en-US';
    return date.toLocaleString(locale);
  };

  // Parse and format JSON
  const { formattedJson, parseError, isTruncated } = useMemo(() => {
    if (!context) {
      return { formattedJson: null, parseError: false, isTruncated: false };
    }

    try {
      const parsed = JSON.parse(context);
      const formatted = JSON.stringify(parsed, null, 2);
      return { formattedJson: formatted, parseError: false, isTruncated: false };
    } catch {
      // Check if it looks like truncated JSON
      const trimmed = context.trim();
      const hasOpenBrace = trimmed.startsWith('{') || trimmed.startsWith('[');
      const hasCloseBrace = trimmed.endsWith('}') || trimmed.endsWith(']');
      const isTruncated = hasOpenBrace && !hasCloseBrace;

      return {
        formattedJson: context, // Show raw string if parse fails
        parseError: true,
        isTruncated
      };
    }
  }, [context]);

  // Copy to clipboard
  const handleCopy = async () => {
    if (!context) return;
    try {
      await navigator.clipboard.writeText(context);
      setCopied(true);
      message.success(t('documentViewer.externalContext.copySuccess', 'コピーしました'));
      setTimeout(() => setCopied(false), 2000);
    } catch {
      message.error(t('common.copyFailed', 'コピーに失敗しました'));
    }
  };

  // Get source type display info
  const sourceTypeConfig = sourceType ? SOURCE_TYPE_CONFIG[sourceType] : null;
  const sourceIdConfig = sourceId ? SOURCE_ID_CONFIG[sourceId] : null;
  const formattedUpdatedAt = formatDate(updatedAt);

  if (!context) {
    return (
      <div style={{ padding: 24, textAlign: 'center', color: '#888' }}>
        {t('documentViewer.externalContext.noData', '外部コンテキストデータがありません')}
      </div>
    );
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      {/* Metadata Section */}
      <Card size="small">
        <Descriptions column={3} size="small">
          <Descriptions.Item
            label={t('documentViewer.externalContext.sourceType', 'ソース種別')}
          >
            {sourceType ? (
              <Tag
                color={sourceTypeConfig?.color || 'default'}
                icon={sourceTypeConfig?.icon}
              >
                {t(`documentViewer.externalContext.types.${sourceType}`, sourceType)}
              </Tag>
            ) : (
              <Text type="secondary">-</Text>
            )}
          </Descriptions.Item>
          <Descriptions.Item
            label={t('documentViewer.externalContext.sourceId', 'ソースID')}
          >
            {sourceId ? (
              <Tag color={sourceIdConfig?.color || 'default'}>
                {sourceIdConfig?.label || sourceId}
              </Tag>
            ) : (
              <Text type="secondary">-</Text>
            )}
          </Descriptions.Item>
          <Descriptions.Item
            label={t('documentViewer.externalContext.updatedAt', '最終更新')}
          >
            {formattedUpdatedAt ? (
              <Text>{formattedUpdatedAt}</Text>
            ) : (
              <Text type="secondary">-</Text>
            )}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      {/* Warnings */}
      {parseError && (
        <Alert
          type={isTruncated ? 'warning' : 'error'}
          icon={<WarningOutlined />}
          message={
            isTruncated
              ? t('documentViewer.externalContext.truncatedWarning', 'データが切り捨てられています（5000文字上限）')
              : t('documentViewer.externalContext.parseError', 'JSONの解析に失敗しました')
          }
          showIcon
        />
      )}

      {/* JSON Content */}
      <Card
        size="small"
        title={
          <Space>
            <Title level={5} style={{ margin: 0 }}>
              {t('documentViewer.externalContext.content', 'コンテンツ')}
            </Title>
            <Tag>{context.length.toLocaleString()} chars</Tag>
          </Space>
        }
        extra={
          <Tooltip title={t('documentViewer.externalContext.copy', 'コピー')}>
            <Button
              type="text"
              icon={copied ? <CheckOutlined style={{ color: 'green' }} /> : <CopyOutlined />}
              onClick={handleCopy}
            />
          </Tooltip>
        }
      >
        <pre
          style={{
            backgroundColor: '#f5f5f5',
            padding: 16,
            borderRadius: 4,
            overflow: 'auto',
            maxHeight: 500,
            margin: 0,
            fontSize: 12,
            fontFamily: 'Monaco, Menlo, "Ubuntu Mono", Consolas, monospace',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {formattedJson}
        </pre>
      </Card>
    </Space>
  );
};

export default ExternalContextTab;

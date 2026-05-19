import { useState } from 'react';
import { Alert, Card, Form, Input, Switch, Button, Table, Tag, Space, App, Typography, Tooltip } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  getConnectorsByPrincipal,
  type ConnectorByPrincipalResponse,
  type ConnectorPrincipalMatch,
} from '../../services/externalIngest';

const { Text } = Typography;

interface ConnectorGovernanceTabProps {
  repositoryId: string;
}

/**
 * V3 (RC5 ext): admin UI for the governance view
 * (GET /v1/admin/connectors/by-principal/{id}).
 *
 * Lets the admin enter a principal (user ID or group ID) and see all
 * delegated connectors that grant access — directly or, when "include
 * group expansion" is on, via groups the user belongs to. The matchType
 * badge highlights direct vs group-derived grants so redundant
 * assignments are easy to spot.
 *
 * No-op for empty principalId. Submit triggers a single API call; the
 * response is rendered as a flat table plus a header card showing the
 * principal type and the list of principal IDs that were searched
 * against (so the operator can see what the server actually checked
 * — a missing PrincipalService, for instance, surfaces as UNKNOWN +
 * an unexpanded principal list).
 */
export function ConnectorGovernanceTab({ repositoryId }: ConnectorGovernanceTabProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [form] = Form.useForm<{ principalId: string; expand: boolean }>();
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<ConnectorByPrincipalResponse | null>(null);

  const onSubmit = async (values: { principalId: string; expand: boolean }) => {
    const pid = values.principalId?.trim();
    if (!pid) {
      message.warning(t('integrationSettings.connectorGovernance.principalRequired',
        { defaultValue: 'Enter a principal ID first.' }));
      return;
    }
    setLoading(true);
    try {
      const resp = await getConnectorsByPrincipal(pid, repositoryId, values.expand);
      setResult(resp);
    } catch (err) {
      message.error(t('integrationSettings.connectorGovernance.lookupFailed',
        { defaultValue: 'Governance lookup failed' })
        + ': ' + (err instanceof Error ? err.message : String(err)));
      setResult(null);
    } finally {
      setLoading(false);
    }
  };

  const matchTypeBadge = (type: ConnectorPrincipalMatch['matchType']) => {
    if (type === 'direct') return <Tag color="green">{t('integrationSettings.connectorGovernance.matchTypeDirect', { defaultValue: 'direct' })}</Tag>;
    if (type === 'group') return <Tag color="blue">{t('integrationSettings.connectorGovernance.matchTypeGroup', { defaultValue: 'via group' })}</Tag>;
    return <Tag color="orange">{t('integrationSettings.connectorGovernance.matchTypeBoth', { defaultValue: 'direct + via group' })}</Tag>;
  };

  const principalTypeBadge = (type: ConnectorByPrincipalResponse['principalType']) => {
    if (type === 'USER') return <Tag color="geekblue">USER</Tag>;
    if (type === 'GROUP') return <Tag color="purple">GROUP</Tag>;
    return (
      <Tooltip title={t('integrationSettings.connectorGovernance.unknownHint',
        { defaultValue: 'Principal not found as a user or group. May be a pseudo-principal (e.g. Anyone) or a typo.' })}>
        <Tag color="default">UNKNOWN</Tag>
      </Tooltip>
    );
  };

  const columns = [
    {
      title: t('integrationSettings.connectorGovernance.colConnector', { defaultValue: 'Connector' }),
      key: 'connector',
      render: (_: unknown, m: ConnectorPrincipalMatch) => (
        <Space direction="vertical" size={0}>
          <Text strong>{m.displayName || m.connectorId}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>{m.connectorId}</Text>
        </Space>
      ),
    },
    {
      title: t('integrationSettings.connectorGovernance.colSource', { defaultValue: 'Source' }),
      key: 'source',
      render: (_: unknown, m: ConnectorPrincipalMatch) => (
        <Space direction="vertical" size={0}>
          <Text>{m.sourceSystem}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>{m.sourceArchetype}</Text>
        </Space>
      ),
    },
    {
      title: t('integrationSettings.connectorGovernance.colStatus', { defaultValue: 'Status' }),
      key: 'status',
      render: (_: unknown, m: ConnectorPrincipalMatch) => (
        <Space size={4} wrap>
          {m.enabled
            ? <Tag color="green">{t('common.enabled', { defaultValue: 'enabled' })}</Tag>
            : <Tag color="red">{t('common.disabled', { defaultValue: 'disabled' })}</Tag>}
          {m.delegated
            ? <Tag color="cyan">{t('integrationSettings.connectorGovernance.delegated', { defaultValue: 'delegated' })}</Tag>
            : <Tag color="default">{t('integrationSettings.connectorGovernance.adminOnly', { defaultValue: 'admin-only' })}</Tag>}
        </Space>
      ),
    },
    {
      title: t('integrationSettings.connectorGovernance.colMatch', { defaultValue: 'Match' }),
      key: 'match',
      render: (_: unknown, m: ConnectorPrincipalMatch) => (
        <Space direction="vertical" size={2}>
          {matchTypeBadge(m.matchType)}
          <Text type="secondary" style={{ fontSize: 12 }}>
            {t('integrationSettings.connectorGovernance.viaLabel', { defaultValue: 'via:' })}{' '}
            {m.matchedPrincipalIds.join(', ')}
          </Text>
        </Space>
      ),
    },
  ];

  return (
    <Card title={t('integrationSettings.connectorGovernance.title',
      { defaultValue: 'Connector access governance' })}>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message={t('integrationSettings.connectorGovernance.intro', {
          defaultValue:
            'Enter a user ID or group ID to see every delegated connector that grants access. '
            + 'Use this before removing a user from a group to audit what they will lose access to.',
        })}
      />
      <Form
        form={form}
        layout="inline"
        initialValues={{ expand: true }}
        onFinish={onSubmit}
        style={{ marginBottom: 16 }}
      >
        <Form.Item
          name="principalId"
          rules={[{ required: true, message: t('integrationSettings.connectorGovernance.principalRequired',
            { defaultValue: 'Enter a principal ID first.' }) }]}
        >
          <Input
            placeholder={t('integrationSettings.connectorGovernance.principalPlaceholder',
              { defaultValue: 'user ID or group ID' })}
            allowClear
            style={{ width: 280 }}
          />
        </Form.Item>
        <Form.Item
          name="expand"
          valuePropName="checked"
          label={t('integrationSettings.connectorGovernance.expandLabel',
            { defaultValue: 'Include group expansion' })}
        >
          <Switch />
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit" loading={loading} icon={<SearchOutlined />}>
            {t('integrationSettings.connectorGovernance.lookup', { defaultValue: 'Look up' })}
          </Button>
        </Form.Item>
      </Form>

      {result && (
        <>
          <Card type="inner" style={{ marginBottom: 16 }} size="small">
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Space size={8} wrap>
                <Text strong>{t('integrationSettings.connectorGovernance.queriedPrincipal',
                  { defaultValue: 'Queried principal:' })}</Text>
                <Text code>{result.principalId}</Text>
                {principalTypeBadge(result.principalType)}
                {result.expand && (
                  <Tag color="default">
                    {t('integrationSettings.connectorGovernance.expandedTag',
                      { defaultValue: 'expanded' })}
                  </Tag>
                )}
              </Space>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {t('integrationSettings.connectorGovernance.searchedAgainst',
                  { defaultValue: 'Searched against principal IDs:' })}{' '}
                {result.expandedPrincipals.join(', ')}
              </Text>
            </Space>
          </Card>

          {result.matches.length === 0 ? (
            <Alert
              type="warning"
              message={t('integrationSettings.connectorGovernance.noMatches',
                { defaultValue: 'No delegated connectors grant access to this principal.' })}
            />
          ) : (
            <Table
              rowKey="connectorId"
              columns={columns}
              dataSource={result.matches}
              pagination={false}
              size="middle"
            />
          )}
        </>
      )}
    </Card>
  );
}

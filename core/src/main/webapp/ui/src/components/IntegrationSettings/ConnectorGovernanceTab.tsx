import { useEffect, useMemo, useState } from 'react';
import { Alert, Card, Form, AutoComplete, Switch, Button, Table, Tag, Space, App, Typography, Tooltip, Select } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  getConnectorsByPrincipal,
  type ConnectorByPrincipalResponse,
  type ConnectorPrincipalMatch,
} from '../../services/externalIngest';
import { CMISService } from '../../services/cmis';
import { useAuth } from '../../contexts/AuthContext';

const { Text } = Typography;

interface ConnectorGovernanceTabProps {
  repositoryId: string;
}

/** F3 (RC5 ext): pre-populated principal option for the AutoComplete. */
interface PrincipalOption {
  value: string;           // principalId
  label: string;           // "id  ·  Display Name (USER|GROUP)"
  kind: 'USER' | 'GROUP';
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
 * F3 (RC5 ext): the principalId input is an AutoComplete pre-populated
 * with the repository's users + groups, so typos that surface as
 * `principalType=UNKNOWN` become much rarer. Free-text is still
 * allowed for pseudo-principals (e.g. Anyone) or principals from an
 * external IdP that haven't yet been cached locally.
 *
 * V5 (RC5 ext): when the lookup result has expanded principals beyond
 * the queried one, an "Simulate removing" dropdown lets the admin pick
 * a single principal from the expansion to ask: "what connectors does
 * this user lose if they're removed from that group?" The result table
 * filters to matches where that principal was the sole matching route.
 */
export function ConnectorGovernanceTab({ repositoryId }: ConnectorGovernanceTabProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const { handleAuthError } = useAuth();
  const [form] = Form.useForm<{ principalId: string; expand: boolean }>();
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<ConnectorByPrincipalResponse | null>(null);
  const [principalOptions, setPrincipalOptions] = useState<PrincipalOption[]>([]);
  // V5: which principal to simulate removing (subset of result.expandedPrincipals)
  const [simulateRemove, setSimulateRemove] = useState<string | null>(null);

  /**
   * F3: Load users + groups once on mount. Failure is non-fatal — the
   * AutoComplete simply has no suggestions, and the admin can still
   * type a principalId by hand (covers pseudo-principals / external
   * IdP principals not present in the local store).
   */
  useEffect(() => {
    const cmis = new CMISService(handleAuthError);
    Promise.all([
      cmis.getUsers(repositoryId, { limit: 500 }).catch(() => ({ users: [] as { id: string; name?: string }[] })),
      cmis.getGroups(repositoryId, { limit: 500 }).catch(() => ({ groups: [] as { id: string; name?: string }[] })),
    ]).then(([{ users }, { groups }]) => {
      const opts: PrincipalOption[] = [];
      for (const u of users) {
        opts.push({
          value: u.id,
          label: `${u.id} · ${u.name || u.id} (USER)`,
          kind: 'USER',
        });
      }
      for (const g of groups) {
        opts.push({
          value: g.id,
          label: `${g.id} · ${g.name || g.id} (GROUP)`,
          kind: 'GROUP',
        });
      }
      setPrincipalOptions(opts);
    });
  }, [repositoryId, handleAuthError]);

  const onSubmit = async (values: { principalId: string; expand: boolean }) => {
    const pid = values.principalId?.trim();
    if (!pid) {
      message.warning(t('connectorGovernance.principalRequired'));
      return;
    }
    setLoading(true);
    setSimulateRemove(null);  // V5: reset simulation when new query starts
    try {
      const resp = await getConnectorsByPrincipal(pid, repositoryId, values.expand);
      setResult(resp);
    } catch (err) {
      message.error(t('connectorGovernance.lookupFailed')
        + ': ' + (err instanceof Error ? err.message : String(err)));
      setResult(null);
    } finally {
      setLoading(false);
    }
  };

  const matchTypeBadge = (type: ConnectorPrincipalMatch['matchType']) => {
    if (type === 'direct') return <Tag color="green">{t('connectorGovernance.matchTypeDirect')}</Tag>;
    if (type === 'group') return <Tag color="blue">{t('connectorGovernance.matchTypeGroup')}</Tag>;
    return <Tag color="orange">{t('connectorGovernance.matchTypeBoth')}</Tag>;
  };

  const principalTypeBadge = (type: ConnectorByPrincipalResponse['principalType']) => {
    if (type === 'USER') return <Tag color="geekblue">USER</Tag>;
    if (type === 'GROUP') return <Tag color="purple">GROUP</Tag>;
    return (
      <Tooltip title={t('connectorGovernance.unknownHint')}>
        <Tag color="default">UNKNOWN</Tag>
      </Tooltip>
    );
  };

  /**
   * V5: filter result matches to those that would be LOST if
   * {@code simulateRemove} were removed from the expansion. A match
   * is lost iff every entry in its {@code matchedPrincipalIds} equals
   * the removed principal (no other route grants access).
   */
  const visibleMatches = useMemo(() => {
    if (!result) return [];
    if (!simulateRemove) return result.matches;
    return result.matches.filter(m =>
      m.matchedPrincipalIds.length > 0
      && m.matchedPrincipalIds.every(p => p === simulateRemove)
    );
  }, [result, simulateRemove]);

  // V5: the dropdown options are the expansion set minus the queried principal
  // itself (removing the queried principal isn't a meaningful question — you
  // would just be asking "what does no-one have?").
  const simulateOptions = useMemo(() => {
    if (!result) return [];
    return result.expandedPrincipals
      .filter(p => p !== result.principalId)
      .map(p => ({ value: p, label: p }));
  }, [result]);

  const columns = [
    {
      title: t('connectorGovernance.colConnector'),
      key: 'connector',
      render: (_: unknown, m: ConnectorPrincipalMatch) => (
        <Space direction="vertical" size={0}>
          <Text strong>{m.displayName || m.connectorId}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>{m.connectorId}</Text>
        </Space>
      ),
    },
    {
      title: t('connectorGovernance.colSource'),
      key: 'source',
      render: (_: unknown, m: ConnectorPrincipalMatch) => (
        <Space direction="vertical" size={0}>
          <Text>{m.sourceSystem}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>{m.sourceArchetype}</Text>
        </Space>
      ),
    },
    {
      title: t('connectorGovernance.colStatus'),
      key: 'status',
      render: (_: unknown, m: ConnectorPrincipalMatch) => (
        <Space size={4} wrap>
          {m.enabled
            ? <Tag color="green">{t('common.enabled', { defaultValue: 'enabled' })}</Tag>
            : <Tag color="red">{t('common.disabled', { defaultValue: 'disabled' })}</Tag>}
          {m.delegated
            ? <Tag color="cyan">{t('connectorGovernance.delegated')}</Tag>
            : <Tag color="default">{t('connectorGovernance.adminOnly')}</Tag>}
        </Space>
      ),
    },
    {
      title: t('connectorGovernance.colMatch'),
      key: 'match',
      render: (_: unknown, m: ConnectorPrincipalMatch) => (
        <Space direction="vertical" size={2}>
          {matchTypeBadge(m.matchType)}
          <Text type="secondary" style={{ fontSize: 12 }}>
            {t('connectorGovernance.viaLabel')}{' '}
            {m.matchedPrincipalIds.join(', ')}
          </Text>
        </Space>
      ),
    },
  ];

  return (
    <Card title={t('connectorGovernance.title')}>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message={t('connectorGovernance.intro')}
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
          rules={[{ required: true, message: t('connectorGovernance.principalRequired') }]}
        >
          {/* F3: AutoComplete sourced from repo users + groups. Free-text
              still allowed for pseudo-principals (e.g. Anyone). filterOption
              matches both the principal ID and the human label so typing
              part of the display name also surfaces suggestions. */}
          <AutoComplete
            options={principalOptions}
            placeholder={t('connectorGovernance.principalPlaceholder')}
            allowClear
            style={{ width: 360 }}
            filterOption={(input, option) => {
              if (!input) return true;
              const needle = input.trim().toLowerCase();
              return (option?.label as string ?? '').toLowerCase().includes(needle);
            }}
          />
        </Form.Item>
        <Form.Item
          name="expand"
          valuePropName="checked"
          label={t('connectorGovernance.expandLabel')}
        >
          <Switch />
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit" loading={loading} icon={<SearchOutlined />}>
            {t('connectorGovernance.lookup')}
          </Button>
        </Form.Item>
      </Form>

      {result && (
        <>
          <Card type="inner" style={{ marginBottom: 16 }} size="small">
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Space size={8} wrap>
                <Text strong>{t('connectorGovernance.queriedPrincipal')}</Text>
                <Text code>{result.principalId}</Text>
                {principalTypeBadge(result.principalType)}
                {result.expand && (
                  <Tag color="default">{t('connectorGovernance.expandedTag')}</Tag>
                )}
              </Space>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {t('connectorGovernance.searchedAgainst')}{' '}
                {result.expandedPrincipals.join(', ')}
              </Text>
              {/* V5: simulate-removal dropdown — only when expansion brought
                  in extra principals beyond the queried one. */}
              {simulateOptions.length > 0 && (
                <Space size={8} wrap style={{ marginTop: 8 }}>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {t('connectorGovernance.simulateRemoveLabel')}
                  </Text>
                  <Select
                    allowClear
                    placeholder={t('connectorGovernance.simulateRemovePlaceholder')}
                    options={simulateOptions}
                    value={simulateRemove ?? undefined}
                    onChange={(v) => setSimulateRemove(v ?? null)}
                    style={{ minWidth: 200 }}
                    size="small"
                  />
                  {simulateRemove && (
                    <Button size="small" onClick={() => setSimulateRemove(null)}>
                      {t('connectorGovernance.simulateClear')}
                    </Button>
                  )}
                </Space>
              )}
            </Space>
          </Card>

          {simulateRemove && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message={t('connectorGovernance.simulationLost')}
              description={
                <Space direction="vertical" size={2}>
                  <Text>{t('connectorGovernance.simulationNote', { count: visibleMatches.length })}</Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {t('connectorGovernance.simulationOnlyMatchedVia')}
                  </Text>
                </Space>
              }
            />
          )}

          {visibleMatches.length === 0 ? (
            <Alert
              type={simulateRemove ? 'success' : 'warning'}
              message={simulateRemove
                ? t('connectorGovernance.simulationNote', { count: 0 })
                : t('connectorGovernance.noMatches')}
            />
          ) : (
            <Table
              rowKey="connectorId"
              columns={columns}
              dataSource={visibleMatches}
              pagination={false}
              size="middle"
            />
          )}
        </>
      )}
    </Card>
  );
}

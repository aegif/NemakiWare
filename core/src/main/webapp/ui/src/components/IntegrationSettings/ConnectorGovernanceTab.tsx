import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, AutoComplete, Card, Form, Switch, Button, Table, Tag, Space, App, Typography, Tooltip, Select, Spin, Radio, InputNumber } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  getConnectorsByPrincipal,
  getConnectorsByGroup,
  simulateRemovePrincipals,
  type ConnectorByPrincipalResponse,
  type ConnectorPrincipalMatch,
  type ConnectorsByGroupResponse,
  type MemberImpact,
} from '../../services/externalIngest';
import { CMISService } from '../../services/cmis';
import { useAuth } from '../../contexts/AuthContext';

const { Text } = Typography;

interface ConnectorGovernanceTabProps {
  repositoryId: string;
}

/** V8 (RC5.1) Principal picker option. */
interface PrincipalOption {
  value: string;           // principalId
  label: string;           // "id  ·  Display Name (USER|GROUP)"
  kind: 'USER' | 'GROUP';
}

/**
 * G3 (RC5.1): pseudo-principals that have no real "removal" semantics
 * — they exist as ACL targets, not as group memberships an admin can
 * edit. Hiding them from the V5/V7 simulate dropdown keeps the
 * "what would the user lose" question focused on actionable choices.
 */
const PSEUDO_PRINCIPALS_FOR_SIMULATE = new Set<string>([
  'GROUP_EVERYONE',
  'anyone',
  'Anyone',
  'GROUP_ANYONE',
  'authenticated',
  'Authenticated',
]);

/**
 * H2 (RC5.2): cap V7's multi-principal removal Select so admins
 * don't reach for the "select all → lose almost everything" answer
 * (low operator value, lots of noise). 10 covers the realistic
 * range of group memberships for most NemakiWare deployments; if
 * a user belongs to more groups and the operator genuinely needs
 * to ask "what does this user have access to AT ALL?", the
 * answer is the unfiltered match list itself.
 */
const SIMULATE_REMOVE_MAX = 10;

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
 * V8 (RC5.1): the principal input is an Ant Design `Select` with
 * virtual scrolling (built-in) + `onSearch` debounce that fetches
 * matching users/groups from the server lazily. No 500-record upfront
 * fetch; works on 10k+ principal directories. Free-text entry is
 * preserved via `combobox` mode for pseudo-principals (e.g. Anyone) or
 * principals that aren't yet in the local store.
 *
 * V5 (RC5 ext) / V7 (RC5.1): when the lookup result has expanded
 * principals beyond the queried one, a "Simulate removing" dropdown
 * (V7: multi-select) lets the admin pick one or more principals from
 * the expansion to ask: "what connectors does this user lose if
 * removed from those groups?" The result table filters to matches
 * where every matched principal lies in the selected removal set.
 *
 * G3 (RC5.1): well-known pseudo-principals (GROUP_EVERYONE, Anyone,
 * Authenticated) are filtered out of the simulate dropdown since
 * removing the user from them isn't an actionable admin choice.
 */
export function ConnectorGovernanceTab({ repositoryId }: ConnectorGovernanceTabProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const { handleAuthError } = useAuth();
  const [form] = Form.useForm<{ principalId: string; expand: boolean }>();
  const [groupForm] = Form.useForm<{ groupId: string; includeMembers: boolean; memberLimit: number }>();
  // B3-2 (RC6): mode toggle. 'principal' = legacy by-principal lookup;
  // 'group' = new by-group membership-impact view. Each mode owns its
  // own form and result so switching modes doesn't lose context.
  const [mode, setMode] = useState<'principal' | 'group'>('principal');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<ConnectorByPrincipalResponse | null>(null);
  const [groupResult, setGroupResult] = useState<ConnectorsByGroupResponse | null>(null);
  // V8: lazy-loaded options for the principal picker
  const [principalOptions, setPrincipalOptions] = useState<PrincipalOption[]>([]);
  const [pickerLoading, setPickerLoading] = useState(false);
  // V7: array of principals to simulate removing (was string|null in V5)
  const [simulateRemove, setSimulateRemove] = useState<string[]>([]);
  // V8/G2 (RC6): totals from the last picker fetch so the dropdown can
  // surface "showing 50 of 12,350" + a "narrow the search" hint when the
  // directory is bigger than the per-fetch cap. PICKER_LIMIT is shared
  // by both sides so the truncation indicator is accurate.
  const PICKER_LIMIT = 50;
  const [pickerTotals, setPickerTotals] = useState<{
    userTotal: number; groupTotal: number; lastQuery: string;
  }>({ userTotal: 0, groupTotal: 0, lastQuery: '' });
  // V8/G2 (RC6): track whether the initial fetch has run. Deferred until
  // the dropdown is actually opened — saves a 100-record fetch on every
  // tab mount that an operator never expands.
  const initialFetchDoneRef = useRef(false);

  // V8: stable CMISService reference for debounced search
  const cmisRef = useRef(new CMISService(handleAuthError));

  /**
   * V8 / G2 (RC6): debounced server-side search. Per-keystroke fetch
   * against the existing `query` param of /user/list and /group/list.
   * Each call is capped at {@link PICKER_LIMIT} records — the
   * dropdown surfaces {@code totalCount} so the operator knows when
   * to narrow the query.
   *
   * `kinds` lets the caller restrict to USER, GROUP, or both:
   * - principal mode fetches both (user IDs and group IDs are both
   *   valid input).
   * - B3-2 group mode fetches GROUP only (typing a user ID into the
   *   group-mode AutoComplete makes no sense, and skipping the user
   *   fetch halves both the round-trips and the rendered DOM at any
   *   given moment).
   *
   * Failures are non-fatal — the dropdown shows whatever was
   * previously loaded.
   */
  const fetchPrincipals = useCallback(async (
    query: string,
    kinds: Array<'USER' | 'GROUP'> = ['USER', 'GROUP'],
  ) => {
    setPickerLoading(true);
    try {
      const cmis = cmisRef.current;
      const want = new Set(kinds);
      // Structural shapes — keeps this fetch decoupled from the full
      // User / Group type definitions (which carry many more fields the
      // picker doesn't need).
      type UserMin = { id: string; name?: string };
      type GroupMin = { id: string; name?: string };
      type UserResp = { users: UserMin[]; totalCount?: number };
      type GroupResp = { groups: GroupMin[]; totalCount?: number };
      // offset=0 is required alongside limit — the /user/list and
      // /group/list endpoints treat (offset>=0 && limit>0) as the
      // gate for paginated mode; with only `limit` the server falls
      // back to "no pagination, return all" for backward compat.
      const usersP: Promise<UserResp> = want.has('USER')
        ? (cmis.getUsers(repositoryId, { offset: 0, limit: PICKER_LIMIT, query: query || undefined }) as Promise<UserResp>)
            .catch(() => ({ users: [], totalCount: 0 }))
        : Promise.resolve({ users: [], totalCount: 0 });
      const groupsP: Promise<GroupResp> = want.has('GROUP')
        ? (cmis.getGroups(repositoryId, { offset: 0, limit: PICKER_LIMIT, query: query || undefined }) as Promise<GroupResp>)
            .catch(() => ({ groups: [], totalCount: 0 }))
        : Promise.resolve({ groups: [], totalCount: 0 });
      const [usersResp, groupsResp] = await Promise.all([usersP, groupsP]);
      const opts: PrincipalOption[] = [];
      for (const u of usersResp.users) {
        opts.push({
          value: u.id,
          label: `${u.id} · ${u.name || u.id} (USER)`,
          kind: 'USER',
        });
      }
      for (const g of groupsResp.groups) {
        opts.push({
          value: g.id,
          label: `${g.id} · ${g.name || g.id} (GROUP)`,
          kind: 'GROUP',
        });
      }
      setPrincipalOptions(opts);
      setPickerTotals({
        userTotal: usersResp.totalCount ?? usersResp.users.length,
        groupTotal: groupsResp.totalCount ?? groupsResp.groups.length,
        lastQuery: query,
      });
    } finally {
      setPickerLoading(false);
    }
  }, [repositoryId]);

  // V8/G2 (RC6): deferred initial fetch. Previously the picker eagerly
  // loaded 50 users + 50 groups at mount, even if the admin never
  // opened the dropdown. We now wait for the first dropdown open
  // (onDropdownVisibleChange below) and pass the current mode's kinds.
  const ensureInitialFetch = useCallback((kinds: Array<'USER' | 'GROUP'>) => {
    if (initialFetchDoneRef.current) return;
    initialFetchDoneRef.current = true;
    fetchPrincipals('', kinds);
  }, [fetchPrincipals]);

  // V8: debounce keystroke-driven search (300ms)
  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const onPrincipalSearch = useCallback((q: string, kinds: Array<'USER' | 'GROUP'> = ['USER', 'GROUP']) => {
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    searchTimerRef.current = setTimeout(() => fetchPrincipals(q, kinds), 300);
  }, [fetchPrincipals]);

  // H1 (RC5.2): unmount cleanup for the debounce timer. Without this,
  // a tab navigation away during the 300 ms debounce window leaves
  // a pending setTimeout that fires fetchPrincipals → setState on an
  // unmounted component → React warning. Single-tab admin UI rarely
  // unmounts so the impact is marginal, but the cleanup costs nothing
  // and removes the warning class entirely.
  useEffect(() => {
    return () => {
      if (searchTimerRef.current) {
        clearTimeout(searchTimerRef.current);
        searchTimerRef.current = null;
      }
    };
  }, []);

  // R3 (RC5.4): explicit "Simulate (audit)" button replaces the
  // RC5.3 800 ms debounce-fire pattern. Audit entries now map 1:1 to
  // deliberate operator decisions instead of "every time the
  // multi-select settled for >800 ms". SOC reviewers can read each
  // EXTERNAL_GOVERNANCE_SIMULATE entry as "an admin explicitly
  // asked this question" rather than "a multi-select traversal
  // happened to pass a quiet threshold". Client-computed display
  // unchanged — the button only triggers the audit round-trip.
  const [simulateAuditing, setSimulateAuditing] = useState(false);
  const [simulateLastAuditedAt, setSimulateLastAuditedAt] = useState<string | null>(null);
  const triggerSimulateAudit = useCallback(async () => {
    if (!result || simulateRemove.length === 0) return;
    setSimulateAuditing(true);
    try {
      await simulateRemovePrincipals(
        result.principalId,
        result.repositoryId,
        result.expand,
        simulateRemove,
      );
      setSimulateLastAuditedAt(new Date().toISOString());
    } catch (err) {
      message.error(t('connectorGovernance.simulateAuditFailed')
        + ': ' + (err instanceof Error ? err.message : String(err)));
    } finally {
      setSimulateAuditing(false);
    }
  }, [result, simulateRemove, message, t]);

  // Reset audit-timestamp marker whenever the simulate selection
  // changes — the previous audit only matched the previous selection.
  useEffect(() => {
    setSimulateLastAuditedAt(null);
  }, [simulateRemove]);

  const onSubmitGroup = async (values: { groupId: string; includeMembers: boolean; memberLimit: number }) => {
    const gid = values.groupId?.trim();
    if (!gid) {
      message.warning(t('connectorGovernance.groupRequired'));
      return;
    }
    setLoading(true);
    try {
      const resp = await getConnectorsByGroup(
        gid,
        repositoryId,
        values.includeMembers !== false,
        values.memberLimit ?? 200,
      );
      setGroupResult(resp);
    } catch (err) {
      message.error(t('connectorGovernance.groupLookupFailed')
        + ': ' + (err instanceof Error ? err.message : String(err)));
      setGroupResult(null);
    } finally {
      setLoading(false);
    }
  };

  const onSubmit = async (values: { principalId: string; expand: boolean }) => {
    const pid = values.principalId?.trim();
    if (!pid) {
      message.warning(t('connectorGovernance.principalRequired'));
      return;
    }
    setLoading(true);
    setSimulateRemove([]);  // V5/V7: reset simulation when new query starts
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
   * V5 / V7 (RC5.1): filter result matches to those that would be LOST
   * if EVERY principal in `simulateRemove` were removed from the
   * expansion. A match is lost iff every entry in its
   * `matchedPrincipalIds` is in the removal set (no other route grants
   * access). Single-element removal set degenerates to V5 behaviour.
   */
  const visibleMatches = useMemo(() => {
    if (!result) return [];
    if (simulateRemove.length === 0) return result.matches;
    const removalSet = new Set(simulateRemove);
    return result.matches.filter(m =>
      m.matchedPrincipalIds.length > 0
      && m.matchedPrincipalIds.every(p => removalSet.has(p))
    );
  }, [result, simulateRemove]);

  /**
   * G3 / V7 (RC5.1): dropdown options are the expansion set minus
   * - the queried principal itself ("remove yourself" makes no sense)
   * - well-known pseudo-principals (GROUP_EVERYONE etc., not editable
   *   as a group membership in any meaningful operator workflow)
   */
  const simulateOptions = useMemo(() => {
    if (!result) return [];
    return result.expandedPrincipals
      .filter(p => p !== result.principalId)
      .filter(p => !PSEUDO_PRINCIPALS_FOR_SIMULATE.has(p))
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

  /**
   * V8/G2 (RC6): dropdown footer showing how many records were loaded
   * vs. how many exist in the directory. Renders as a small Alert
   * inside `notFoundContent` so it's only visible when the dropdown
   * is open. The footer is mode-aware: principal picker shows both
   * user and group totals; group-only picker shows just the group
   * total.
   */
  const renderPickerHint = useCallback((kinds: Array<'USER' | 'GROUP'>) => {
    if (pickerLoading) return <Spin size="small" />;
    const want = new Set(kinds);
    const loaded = principalOptions.filter(o => want.has(o.kind)).length;
    const total = (want.has('USER') ? pickerTotals.userTotal : 0)
      + (want.has('GROUP') ? pickerTotals.groupTotal : 0);
    if (total === 0) return null;
    const truncated = total > loaded;
    return (
      <Space direction="vertical" size={2} style={{ padding: 4 }}>
        <Text type="secondary" style={{ fontSize: 11 }}>
          {t('connectorGovernance.pickerLoadedHint', {
            loaded,
            total: total.toLocaleString(),
          })}
        </Text>
        {truncated && (
          <Text type="warning" style={{ fontSize: 11 }}>
            {t('connectorGovernance.pickerNarrowSearch')}
          </Text>
        )}
      </Space>
    );
  }, [pickerLoading, principalOptions, pickerTotals, t]);

  /**
   * V8/G2 (RC6): GROUP-only options for the B3-2 group-mode picker.
   * Filters the shared `principalOptions` state — the actual fetch is
   * group-only (no user round-trip wasted on group-mode keystrokes).
   */
  const groupOnlyOptions = useMemo(
    () => principalOptions.filter(o => o.kind === 'GROUP'),
    [principalOptions],
  );

  // B3-2 (RC6): group-mode table columns. Each row = one member;
  // `lostIfGroupRemoved` is a per-row Tag list showing the connectors
  // that member loses if this group is removed (sole-route detection).
  const groupColumns = [
    {
      title: t('connectorGovernance.colUserId'),
      dataIndex: 'userId',
      key: 'userId',
      render: (uid: string) => <Text code>{uid}</Text>,
    },
    {
      title: t('connectorGovernance.colLost'),
      key: 'lost',
      render: (_: unknown, m: MemberImpact) => {
        if (!m.lostIfGroupRemoved || m.lostIfGroupRemoved.length === 0) {
          return <Tag color="default">{t('connectorGovernance.perMemberKept')}</Tag>;
        }
        return (
          <Space size={4} wrap>
            <Tag color="red">
              {t('connectorGovernance.perMemberLostCount', { count: m.lostIfGroupRemoved.length })}
            </Tag>
            {m.lostIfGroupRemoved.map(c => (
              <Tag key={c.connectorId} color="orange">
                {c.displayName || c.connectorId}
              </Tag>
            ))}
          </Space>
        );
      },
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
      <Form.Item label={t('connectorGovernance.modeLabel')} style={{ marginBottom: 16 }}>
        <Radio.Group
          value={mode}
          onChange={e => setMode(e.target.value)}
          optionType="button"
          buttonStyle="solid"
        >
          <Radio.Button value="principal">{t('connectorGovernance.modePrincipal')}</Radio.Button>
          <Radio.Button value="group">{t('connectorGovernance.modeGroup')}</Radio.Button>
        </Radio.Group>
      </Form.Item>
      {mode === 'principal' && (
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
          {/* F3/V8 (RC5.1, B1-fixed): AutoComplete preserves the
              free-text submission path required for pseudo-principals
              (e.g. Anyone), external-IdP IDs, and any principal not
              yet in the local store. V8's server-side `onSearch` +
              300 ms debounce against /user/list + /group/list is
              kept here so the dropdown scales for large directories
              without an upfront limit=500 fetch. virtual scrolling
              is given up — limit=50 per call keeps the suggestion
              list short enough that rendering cost is negligible. */}
          <AutoComplete
            allowClear
            placeholder={t('connectorGovernance.principalPlaceholder')}
            style={{ width: 360 }}
            options={principalOptions}
            onSearch={q => onPrincipalSearch(q, ['USER', 'GROUP'])}
            onDropdownVisibleChange={open => {
              if (open) ensureInitialFetch(['USER', 'GROUP']);
            }}
            filterOption={false}      // server-side filter — don't double-filter client-side
            notFoundContent={renderPickerHint(['USER', 'GROUP']) ?? null}
            dropdownRender={menu => (
              <>
                {menu}
                {renderPickerHint(['USER', 'GROUP'])}
              </>
            )}
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
      )}

      {mode === 'principal' && result && (
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
              {/* V5/V7 (RC5.1): simulate-removal dropdown — only when
                  expansion brought in extra actionable principals.
                  H2 (RC5.2): maxCount caps selection so the operator
                  is steered toward focused comparisons rather than
                  the trivial "remove all groups → lose almost
                  everything" answer. */}
              {simulateOptions.length > 0 && (
                <Space size={8} wrap style={{ marginTop: 8 }}>
                  <Tooltip title={t('connectorGovernance.simulateRemoveHint', {
                    max: SIMULATE_REMOVE_MAX,
                  })}>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {t('connectorGovernance.simulateRemoveLabel')}
                    </Text>
                  </Tooltip>
                  <Select
                    mode="multiple"
                    allowClear
                    maxCount={SIMULATE_REMOVE_MAX}
                    placeholder={t('connectorGovernance.simulateRemovePlaceholder')}
                    options={simulateOptions}
                    value={simulateRemove}
                    onChange={(v: string[]) => setSimulateRemove(v ?? [])}
                    style={{ minWidth: 240 }}
                    size="small"
                  />
                  {simulateRemove.length > 0 && (
                    <Button size="small" onClick={() => setSimulateRemove([])}>
                      {t('connectorGovernance.simulateClear')}
                    </Button>
                  )}
                  {/* R3 (RC5.4): explicit audit button. Client-computed
                      display is instant; this button is what records the
                      operator's deliberate query in the audit log. */}
                  {simulateRemove.length > 0 && (
                    <Tooltip title={t('connectorGovernance.simulateAuditHint')}>
                      <Button
                        size="small"
                        type="primary"
                        ghost
                        loading={simulateAuditing}
                        disabled={simulateLastAuditedAt !== null}
                        onClick={triggerSimulateAudit}
                      >
                        {simulateLastAuditedAt
                          ? t('connectorGovernance.simulateAudited')
                          : t('connectorGovernance.simulateAudit')}
                      </Button>
                    </Tooltip>
                  )}
                  {simulateRemove.length >= SIMULATE_REMOVE_MAX && (
                    <Tag color="orange" style={{ fontSize: 10 }}>
                      {t('connectorGovernance.simulateMaxReached', { max: SIMULATE_REMOVE_MAX })}
                    </Tag>
                  )}
                </Space>
              )}
            </Space>
          </Card>

          {simulateRemove.length > 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message={t('connectorGovernance.simulationLost')}
              description={
                <Space direction="vertical" size={2}>
                  <Text>
                    {t('connectorGovernance.simulationNote', {
                      count: visibleMatches.length,
                      principals: simulateRemove.join(', '),
                    })}
                  </Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {t('connectorGovernance.simulationOnlyMatchedVia')}
                  </Text>
                </Space>
              }
            />
          )}

          {visibleMatches.length === 0 ? (
            <Alert
              type={simulateRemove.length > 0 ? 'success' : 'warning'}
              message={simulateRemove.length > 0
                ? t('connectorGovernance.simulationNote', {
                    count: 0,
                    principals: simulateRemove.join(', '),
                  })
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

      {mode === 'group' && (
        <Form
          form={groupForm}
          layout="inline"
          initialValues={{ includeMembers: true, memberLimit: 200 }}
          onFinish={onSubmitGroup}
          style={{ marginBottom: 16 }}
        >
          <Form.Item
            name="groupId"
            rules={[{ required: true, message: t('connectorGovernance.groupRequired') }]}
          >
            <AutoComplete
              allowClear
              placeholder={t('connectorGovernance.groupPlaceholder')}
              style={{ width: 360 }}
              options={groupOnlyOptions}
              onSearch={q => onPrincipalSearch(q, ['GROUP'])}
              onDropdownVisibleChange={open => {
                if (open) ensureInitialFetch(['GROUP']);
              }}
              filterOption={false}
              notFoundContent={renderPickerHint(['GROUP']) ?? null}
              dropdownRender={menu => (
                <>
                  {menu}
                  {renderPickerHint(['GROUP'])}
                </>
              )}
            />
          </Form.Item>
          <Form.Item
            name="includeMembers"
            valuePropName="checked"
            label={t('connectorGovernance.includeMembersLabel')}
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="memberLimit"
            label={t('connectorGovernance.memberLimitLabel')}
          >
            <InputNumber min={1} max={1000} step={50} style={{ width: 100 }} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} icon={<SearchOutlined />}>
              {t('connectorGovernance.lookup')}
            </Button>
          </Form.Item>
        </Form>
      )}

      {mode === 'group' && groupResult && (
        <>
          <Card type="inner" style={{ marginBottom: 16 }} size="small">
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Space size={8} wrap>
                <Text strong>{t('connectorGovernance.queriedPrincipal')}</Text>
                <Text code>{groupResult.groupId}</Text>
                {groupResult.groupType === 'GROUP'
                  ? <Tag color="purple">GROUP</Tag>
                  : <Tooltip title={t('connectorGovernance.groupNotResolved')}>
                      <Tag color="default">UNKNOWN</Tag>
                    </Tooltip>}
                {groupResult.groupName && (
                  <Text type="secondary">
                    {t('connectorGovernance.groupSummary', {
                      name: groupResult.groupName,
                      count: groupResult.memberCount,
                    })}
                  </Text>
                )}
              </Space>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {t('connectorGovernance.groupMembersShowing', {
                  shown: groupResult.memberUserIds.length,
                  total: groupResult.memberCount,
                })}
              </Text>
              {groupResult.memberUserIdsTruncated && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginTop: 8 }}
                  message={t('connectorGovernance.groupMembersTruncated', {
                    limit: groupResult.memberLimit,
                  })}
                />
              )}
            </Space>
          </Card>

          {/* Direct group grants — same shape as by-principal/{groupId}. */}
          <Card
            type="inner"
            size="small"
            title={t('connectorGovernance.directGrantsTitle')}
            style={{ marginBottom: 16 }}
          >
            {groupResult.directGrants.length === 0 ? (
              <Alert type="info" message={t('connectorGovernance.directGrantsEmpty')} />
            ) : (
              <Table
                rowKey="connectorId"
                columns={columns}
                dataSource={groupResult.directGrants}
                pagination={false}
                size="small"
              />
            )}
          </Card>

          {/* Per-member impact — empty when includeMembers=false (fast path). */}
          <Card
            type="inner"
            size="small"
            title={t('connectorGovernance.perMemberImpactTitle')}
          >
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 8 }}
              message={t('connectorGovernance.perMemberImpactHint')}
            />
            {groupResult.perMemberImpact.length === 0 ? (
              <Alert type="success" message={t('connectorGovernance.perMemberImpactEmpty')} />
            ) : (
              <Table
                rowKey="userId"
                columns={groupColumns}
                dataSource={groupResult.perMemberImpact}
                pagination={{ pageSize: 25, showSizeChanger: false }}
                size="small"
              />
            )}
          </Card>
        </>
      )}
    </Card>
  );
}

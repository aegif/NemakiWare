import { useEffect, useState } from 'react';
import { Card, Row, Col, Statistic, Spin, message, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { getMetrics, getStats, type LineageMetricsData, type LineageStatsData } from '../../services/lineageJournal';

export default function LineageJournalStats() {
  const { t } = useTranslation();
  const [metrics, setMetrics] = useState<LineageMetricsData | null>(null);
  const [stats, setStats] = useState<LineageStatsData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function load() {
      try {
        const [m, s] = await Promise.all([getMetrics(), getStats()]);
        setMetrics(m);
        setStats(s);
      } catch {
        message.error(t('integrationSettings.lineage.loadMetricsFailed'));
      } finally {
        setLoading(false);
      }
    }
    load();
  }, [t]);

  if (loading) return <Spin />;

  // Localize mode label; append override indicator when global mode is disabled
  // but repository overrides are enabling journaling
  const modeKey = stats?.mode ? `integrationSettings.lineage.mode${stats.mode.charAt(0).toUpperCase()}${stats.mode.slice(1)}` : '';
  let modeLabel = modeKey ? t(modeKey, stats?.mode ?? '') : (stats?.mode ?? '');
  if (stats?.hasRepositoryOverrides) {
    modeLabel += ` (${t('integrationSettings.lineage.repoOverridesActive')})`;
  }

  return (
    <div>
      <Row gutter={[16, 16]}>
        <Col span={6}>
          <Card>
            <Statistic title={t('integrationSettings.lineage.eventsPublished')} value={metrics?.eventsPublished ?? 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title={t('integrationSettings.lineage.eventsFailed')} value={metrics?.eventsFailed ?? 0} valueStyle={{ color: metrics?.eventsFailed ? '#cf1322' : undefined }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title={t('integrationSettings.lineage.eventsDiscarded')} value={metrics?.eventsDiscarded ?? 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title={t('integrationSettings.lineage.deadLetterCount')} value={metrics?.deadLetterCount ?? 0} valueStyle={{ color: metrics?.deadLetterCount ? '#cf1322' : undefined }} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col span={6}>
          <Card>
            <Statistic title={t('integrationSettings.lineage.pollCount')} value={metrics?.pollCount ?? 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title={t('integrationSettings.lineage.lastPoll')} value={metrics?.lastPollTime ? new Date(metrics.lastPollTime).toLocaleString() : '-'} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title={t('integrationSettings.lineage.mode')} value={modeLabel} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title={t('integrationSettings.lineage.storeActive')} value={stats?.storeActive ? t('common.yes') : t('common.no')} />
          </Card>
        </Col>
      </Row>

      {/* Per-target non-terminal counts from stats API */}
      {stats?.nonTerminalByTarget && Object.keys(stats.nonTerminalByTarget).length > 0 && (
        <Card title={t('integrationSettings.lineage.nonTerminalByTarget')} style={{ marginTop: 16 }}>
          {Object.entries(stats.nonTerminalByTarget).map(([target, count]) => (
            <div key={target} style={{ marginBottom: 8 }}>
              <Tag>{target}</Tag>
              {t('integrationSettings.lineage.nonTerminal')}: <strong>{count}</strong>
            </div>
          ))}
        </Card>
      )}

      {/* Detailed backlog from metrics API (includes maxDocs and estimated size) */}
      {metrics?.backlog && Object.keys(metrics.backlog).length > 0 && (
        <Card title={t('integrationSettings.lineage.backlog')} style={{ marginTop: 16 }}>
          {Object.entries(metrics.backlog).map(([target, info]) => (
            <div key={target} style={{ marginBottom: 8 }}>
              <Tag>{target}</Tag>
              {t('integrationSettings.lineage.nonTerminal')}: <strong>{info.nonTerminal}</strong> / {info.maxDocs}
              {' | '}{t('integrationSettings.lineage.estimatedSize')}: {Math.round(info.estimatedSizeBytes / 1024)} KB
            </div>
          ))}
        </Card>
      )}
    </div>
  );
}

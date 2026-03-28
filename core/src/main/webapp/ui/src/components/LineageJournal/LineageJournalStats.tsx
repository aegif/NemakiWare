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
            <Statistic title={t('integrationSettings.lineage.mode')} value={String(stats?.mode ?? 'unknown')} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title={t('integrationSettings.lineage.storeActive')} value={stats?.storeActive ? 'Yes' : 'No'} />
          </Card>
        </Col>
      </Row>

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

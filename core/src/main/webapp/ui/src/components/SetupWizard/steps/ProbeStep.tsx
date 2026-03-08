import { useEffect, useState } from 'react';
import { Table, Tag, Alert, Spin, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import { setupApi, RepositoryOverview } from '../../../services/setupApi';
import type { CouchDbConfig } from './CouchDbStep';

interface ProbeStepProps {
  couchdb: CouchDbConfig;
  onValidChange: (valid: boolean) => void;
}

export function ProbeStep({ couchdb, onValidChange }: ProbeStepProps) {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(true);
  const [repos, setRepos] = useState<RepositoryOverview[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const probe = async () => {
      setLoading(true);
      setError(null);
      try {
        const result = await setupApi.probe(couchdb);
        if (!cancelled) {
          setRepos(result);
          // Block if any main repository has an error (auth failure, connection error, etc.).
          // The mainRepository flag is set by the backend based on repositories.yml.
          // An empty repo (no error) is fine — it means the DB doesn't exist
          // yet, which is normal for a fresh install.
          const mainRepos = result.filter(r => r.mainRepository);
          const hasMainRepo = mainRepos.length > 0;
          const anyMainError = mainRepos.some(r => !!r.error);
          onValidChange(hasMainRepo && !anyMainError);
        }
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : String(e));
          onValidChange(false);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    probe();
    return () => { cancelled = true; };
  }, [couchdb, onValidChange]);

  if (loading) {
    return <Spin tip={t('setup.probe.scanning')} />;
  }

  if (error) {
    return <Alert type="error" message={t('setup.probe.error')} description={error} showIcon />;
  }

  const hasLegacy = repos.some(r => r.judgment === 'legacy');
  const hasError = repos.some(r => !!r.error);

  const columns = [
    {
      title: t('setup.probe.repository'),
      dataIndex: 'repositoryId',
      key: 'repositoryId',
    },
    {
      title: t('setup.probe.documents'),
      dataIndex: 'documentCount',
      key: 'documentCount',
    },
    {
      title: t('setup.probe.views'),
      key: 'views',
      render: (_: unknown, record: RepositoryOverview) =>
        `${record.viewCount} / ${record.requiredViewCount}`,
    },
    {
      title: t('setup.probe.status'),
      key: 'judgment',
      render: (_: unknown, record: RepositoryOverview) => {
        if (record.error) {
          return <Tag color="red">{record.error}</Tag>;
        }
        const colorMap: Record<string, string> = {
          current: 'green',
          legacy: 'orange',
          empty: 'default',
        };
        return <Tag color={colorMap[record.judgment] || 'default'}>{record.judgment}</Tag>;
      },
    },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {hasError && (
        <Alert
          type="error"
          showIcon
          message={t('setup.probe.connectionError')}
          description={t('setup.probe.connectionErrorDescription')}
        />
      )}
      {hasLegacy && (
        <Alert
          type="warning"
          showIcon
          message={t('setup.probe.legacyWarning')}
          description={t('setup.probe.legacyDescription')}
        />
      )}
      <Table
        dataSource={repos}
        columns={columns}
        rowKey="repositoryId"
        pagination={false}
        size="small"
      />
    </Space>
  );
}

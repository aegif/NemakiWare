/**
 * SolrMaintenance Component for NemakiWare React UI
 *
 * Solr index maintenance component providing comprehensive index management operations:
 * - Index health check with document count comparison
 * - Full repository reindexing
 * - Folder-based reindexing with recursive option
 * - Reindex progress monitoring with real-time status updates
 * - Direct Solr query execution for debugging and maintenance
 * - Index optimization
 * - Japanese localized UI
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  Card,
  Button,
  Space,
  message,
  Progress,
  Statistic,
  Row,
  Col,
  Input,
  Table,
  Tag,
  Popconfirm,
  Alert,
  Tabs,
  Form,
  InputNumber,
  Spin,
  Descriptions,
  Typography
} from 'antd';
import {
  SyncOutlined,
  CheckCircleOutlined,
  WarningOutlined,
  SearchOutlined,
  ClearOutlined,
  ThunderboltOutlined,
  FolderOutlined,
  ReloadOutlined,
  StopOutlined
} from '@ant-design/icons';
import { useAuth } from '../../contexts/AuthContext';
import { SolrMaintenanceService, ReindexStatus, IndexHealthStatus, SolrQueryResult } from '../../services/solrMaintenance';

const { TextArea } = Input;
const { Text } = Typography;

interface SolrMaintenanceProps {
  repositoryId: string;
}

export const SolrMaintenance: React.FC<SolrMaintenanceProps> = ({ repositoryId }) => {
  const [loading, setLoading] = useState(false);
  const [healthStatus, setHealthStatus] = useState<IndexHealthStatus | null>(null);
  const [reindexStatus, setReindexStatus] = useState<ReindexStatus | null>(null);
  const [queryResult, setQueryResult] = useState<SolrQueryResult | null>(null);
  const [solrUrl, setSolrUrl] = useState<string>('');
  const [queryForm] = Form.useForm();
  const [folderIdInput, setFolderIdInput] = useState<string>('');
  const [recursiveReindex, setRecursiveReindex] = useState<boolean>(true);

  const { handleAuthError } = useAuth();
  const service = new SolrMaintenanceService(() => handleAuthError(null));

  const loadSolrUrl = useCallback(async () => {
    try {
      const url = await service.getSolrUrl(repositoryId);
      setSolrUrl(url);
    } catch (error: unknown) {
      console.error('Failed to load Solr URL:', error);
    }
  }, [repositoryId]);

  const loadHealthStatus = useCallback(async () => {
    setLoading(true);
    try {
      const status = await service.checkIndexHealth(repositoryId);
      setHealthStatus(status);
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`インデックスヘルスチェックに失敗しました: ${errorMessage}`);
    } finally {
      setLoading(false);
    }
  }, [repositoryId]);

  const loadReindexStatus = useCallback(async () => {
    try {
      const status = await service.getReindexStatus(repositoryId);
      setReindexStatus(status);
      return status;
    } catch (error: unknown) {
      console.error('Failed to load reindex status:', error);
      return null;
    }
  }, [repositoryId]);

  useEffect(() => {
    loadSolrUrl();
    loadHealthStatus();
    loadReindexStatus();
  }, [repositoryId, loadSolrUrl, loadHealthStatus, loadReindexStatus]);

  useEffect(() => {
    let intervalId: NodeJS.Timeout | null = null;
    
    if (reindexStatus?.status === 'running') {
      intervalId = setInterval(async () => {
        const status = await loadReindexStatus();
        if (status && status.status !== 'running') {
          if (intervalId) {
            clearInterval(intervalId);
          }
          loadHealthStatus();
        }
      }, 2000);
    }

    return () => {
      if (intervalId) {
        clearInterval(intervalId);
      }
    };
  }, [reindexStatus?.status, loadReindexStatus, loadHealthStatus]);

  const handleFullReindex = async () => {
    try {
      await service.startFullReindex(repositoryId);
      message.success('全体再インデクシングを開始しました');
      loadReindexStatus();
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`再インデクシングの開始に失敗しました: ${errorMessage}`);
    }
  };

  const handleFolderReindex = async () => {
    if (!folderIdInput.trim()) {
      message.warning('フォルダIDを入力してください');
      return;
    }
    try {
      await service.startFolderReindex(repositoryId, folderIdInput.trim(), recursiveReindex);
      message.success('フォルダ再インデクシングを開始しました');
      loadReindexStatus();
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`フォルダ再インデクシングの開始に失敗しました: ${errorMessage}`);
    }
  };

  const handleCancelReindex = async () => {
    try {
      await service.cancelReindex(repositoryId);
      message.success('再インデクシングをキャンセルしました');
      loadReindexStatus();
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`キャンセルに失敗しました: ${errorMessage}`);
    }
  };

  const handleClearIndex = async () => {
    try {
      await service.clearIndex(repositoryId);
      message.success('インデックスをクリアしました');
      loadHealthStatus();
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`インデックスのクリアに失敗しました: ${errorMessage}`);
    }
  };

  const handleOptimizeIndex = async () => {
    try {
      await service.optimizeIndex(repositoryId);
      message.success('インデックスを最適化しました');
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`インデックスの最適化に失敗しました: ${errorMessage}`);
    }
  };

  const handleExecuteQuery = async (values: { query: string; start: number; rows: number; sort?: string; fields?: string }) => {
    setLoading(true);
    try {
      const result = await service.executeSolrQuery(
        repositoryId,
        values.query,
        values.start || 0,
        values.rows || 10,
        values.sort,
        values.fields
      );
      setQueryResult(result);
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`クエリの実行に失敗しました: ${errorMessage}`);
    } finally {
      setLoading(false);
    }
  };

  const getStatusTag = (status: string) => {
    switch (status) {
      case 'idle':
        return <Tag color="default">待機中</Tag>;
      case 'running':
        return <Tag color="processing" icon={<SyncOutlined spin />}>実行中</Tag>;
      case 'completed':
        return <Tag color="success" icon={<CheckCircleOutlined />}>完了</Tag>;
      case 'error':
        return <Tag color="error" icon={<WarningOutlined />}>エラー</Tag>;
      case 'cancelled':
        return <Tag color="warning">キャンセル</Tag>;
      default:
        return <Tag>{status}</Tag>;
    }
  };

  const queryColumns = [
    {
      title: 'フィールド',
      dataIndex: 'field',
      key: 'field',
      width: 200,
    },
    {
      title: '値',
      dataIndex: 'value',
      key: 'value',
      render: (value: unknown) => {
        if (typeof value === 'object') {
          return <Text code>{JSON.stringify(value)}</Text>;
        }
        return String(value);
      },
    },
  ];

  const renderHealthStatus = () => {
    if (!healthStatus) {
      return <Spin />;
    }

    return (
      <Card title="インデックスヘルスチェック" extra={
        <Button icon={<ReloadOutlined />} onClick={loadHealthStatus} loading={loading}>
          更新
        </Button>
      }>
        <Row gutter={16}>
          <Col span={6}>
            <Statistic
              title="Solrドキュメント数"
              value={healthStatus.solrDocumentCount}
              prefix={<SearchOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title="CouchDBドキュメント数"
              value={healthStatus.couchDbDocumentCount}
              prefix={<FolderOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title="Solrに未登録"
              value={healthStatus.missingInSolr}
              valueStyle={{ color: healthStatus.missingInSolr > 0 ? '#cf1322' : '#3f8600' }}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title="孤立ドキュメント"
              value={healthStatus.orphanedInSolr}
              valueStyle={{ color: healthStatus.orphanedInSolr > 0 ? '#faad14' : '#3f8600' }}
            />
          </Col>
        </Row>
        <div style={{ marginTop: 16 }}>
          {healthStatus.healthy ? (
            <Alert message="インデックスは正常です" type="success" showIcon />
          ) : (
            <Alert message={healthStatus.message} type="warning" showIcon />
          )}
        </div>
      </Card>
    );
  };

  const renderReindexStatus = () => {
    if (!reindexStatus) {
      return null;
    }

    const progress = reindexStatus.totalDocuments > 0
      ? Math.round((reindexStatus.indexedCount / reindexStatus.totalDocuments) * 100)
      : 0;

    return (
      <Card title="再インデクシング状態" style={{ marginTop: 16 }}>
        <Descriptions column={2}>
          <Descriptions.Item label="状態">{getStatusTag(reindexStatus.status)}</Descriptions.Item>
          <Descriptions.Item label="現在のフォルダ">{reindexStatus.currentFolder || '-'}</Descriptions.Item>
          <Descriptions.Item label="総ドキュメント数">{reindexStatus.totalDocuments}</Descriptions.Item>
          <Descriptions.Item label="インデックス済み">{reindexStatus.indexedCount}</Descriptions.Item>
          <Descriptions.Item label="エラー数">{reindexStatus.errorCount}</Descriptions.Item>
          <Descriptions.Item label="エラーメッセージ">{reindexStatus.errorMessage || '-'}</Descriptions.Item>
        </Descriptions>
        {reindexStatus.status === 'running' && (
          <div style={{ marginTop: 16 }}>
            <Progress percent={progress} status="active" />
            <Button
              danger
              icon={<StopOutlined />}
              onClick={handleCancelReindex}
              style={{ marginTop: 8 }}
            >
              キャンセル
            </Button>
          </div>
        )}
      </Card>
    );
  };

  const renderReindexActions = () => (
    <Card title="再インデクシング操作" style={{ marginTop: 16 }}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <div>
          <Text strong>全体再インデクシング</Text>
          <p style={{ color: '#666', marginBottom: 8 }}>
            リポジトリ内の全ドキュメントを再インデックスします。
          </p>
          <Popconfirm
            title="全体再インデクシングを実行しますか？"
            description="この操作には時間がかかる場合があります。"
            onConfirm={handleFullReindex}
            okText="実行"
            cancelText="キャンセル"
          >
            <Button
              type="primary"
              icon={<SyncOutlined />}
              disabled={reindexStatus?.status === 'running'}
            >
              全体再インデクシング
            </Button>
          </Popconfirm>
        </div>

        <div style={{ marginTop: 16 }}>
          <Text strong>フォルダ単位の再インデクシング</Text>
          <p style={{ color: '#666', marginBottom: 8 }}>
            指定したフォルダ配下のドキュメントを再インデックスします。
          </p>
          <Space>
            <Input
              placeholder="フォルダID"
              value={folderIdInput}
              onChange={(e) => setFolderIdInput(e.target.value)}
              style={{ width: 300 }}
            />
            <label>
              <input
                type="checkbox"
                checked={recursiveReindex}
                onChange={(e) => setRecursiveReindex(e.target.checked)}
              />
              {' '}サブフォルダを含む
            </label>
            <Button
              icon={<FolderOutlined />}
              onClick={handleFolderReindex}
              disabled={reindexStatus?.status === 'running'}
            >
              フォルダ再インデクシング
            </Button>
          </Space>
        </div>

        <div style={{ marginTop: 16 }}>
          <Text strong>インデックス管理</Text>
          <p style={{ color: '#666', marginBottom: 8 }}>
            インデックスのクリアや最適化を行います。
          </p>
          <Space>
            <Popconfirm
              title="インデックスをクリアしますか？"
              description="この操作は取り消せません。全てのインデックスが削除されます。"
              onConfirm={handleClearIndex}
              okText="クリア"
              cancelText="キャンセル"
            >
              <Button danger icon={<ClearOutlined />}>
                インデックスクリア
              </Button>
            </Popconfirm>
            <Popconfirm
              title="インデックスを最適化しますか？"
              description="この操作はSolrコア全体に影響します（リポジトリ単位ではありません）。実行中は検索パフォーマンスが低下する可能性があります。"
              onConfirm={handleOptimizeIndex}
              okText="最適化"
              cancelText="キャンセル"
            >
              <Button icon={<ThunderboltOutlined />}>
                インデックス最適化
              </Button>
            </Popconfirm>
          </Space>
        </div>
      </Space>
    </Card>
  );

  const renderSolrQuery = () => (
    <Card title="Solrクエリ実行" style={{ marginTop: 16 }}>
      <Form
        form={queryForm}
        layout="vertical"
        onFinish={handleExecuteQuery}
        initialValues={{ start: 0, rows: 10 }}
      >
        <Form.Item
          name="query"
          label="クエリ (q)"
          rules={[{ required: true, message: 'クエリを入力してください' }]}
        >
          <TextArea
            placeholder="*:* または name:test など"
            rows={3}
          />
        </Form.Item>
        <Row gutter={16}>
          <Col span={6}>
            <Form.Item name="start" label="開始位置 (start)">
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item 
              name="rows" 
              label="件数 (rows)"
              tooltip="サーバー側で最大1000件に制限されます"
            >
              <InputNumber min={1} max={1000} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="sort" label="ソート (sort)">
              <Input placeholder="score desc" />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="fields" label="フィールド (fl)">
              <Input placeholder="id,name,score" />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item>
          <Button type="primary" htmlType="submit" icon={<SearchOutlined />} loading={loading}>
            クエリ実行
          </Button>
        </Form.Item>
      </Form>

      {queryResult && (
        <div style={{ marginTop: 16 }}>
          <Alert
            message={queryResult.numFound === 0 
              ? `0件 (${queryResult.queryTime}ms)` 
              : `${queryResult.numFound}件中 ${queryResult.start + 1}〜${queryResult.start + queryResult.docs.length}件を表示 (${queryResult.queryTime}ms)`}
            type="info"
            style={{ marginBottom: 16 }}
          />
          {queryResult.docs.map((doc, index) => (
            <Card
              key={index}
              size="small"
              title={`ドキュメント ${queryResult.start + index + 1}`}
              style={{ marginBottom: 8 }}
            >
              <Table
                dataSource={Object.entries(doc).map(([field, value]) => ({ field, value, key: field }))}
                columns={queryColumns}
                pagination={false}
                size="small"
              />
            </Card>
          ))}
        </div>
      )}
    </Card>
  );

  const tabItems = [
    {
      key: 'status',
      label: 'ステータス',
      children: (
        <>
          {renderHealthStatus()}
          {renderReindexStatus()}
        </>
      ),
    },
    {
      key: 'reindex',
      label: '再インデクシング',
      children: renderReindexActions(),
    },
    {
      key: 'query',
      label: 'Solrクエリ',
      children: renderSolrQuery(),
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Card>
        <div style={{ marginBottom: 16 }}>
          <h2 style={{ margin: 0 }}>Solrインデックスメンテナンス</h2>
          {solrUrl && (
            <Text type="secondary">Solr URL: {solrUrl}</Text>
          )}
        </div>
        <Tabs items={tabItems} />
      </Card>
    </div>
  );
};

export default SolrMaintenance;

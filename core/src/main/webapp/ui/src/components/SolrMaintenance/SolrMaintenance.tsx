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
  Typography,
  Collapse,
  List,
  Tooltip
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
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { SolrMaintenanceService, ReindexStatus, IndexHealthStatus, SolrQueryResult } from '../../services/solrMaintenance';
import { RAGMaintenanceService, RAGReindexStatus, RAGHealthStatus, RAGSearchResult } from '../../services/ragMaintenance';

const { TextArea } = Input;
const { Text } = Typography;

interface SolrMaintenanceProps {
  repositoryId: string;
}

export const SolrMaintenance: React.FC<SolrMaintenanceProps> = ({ repositoryId }) => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [healthStatus, setHealthStatus] = useState<IndexHealthStatus | null>(null);
  const [reindexStatus, setReindexStatus] = useState<ReindexStatus | null>(null);
  const [queryResult, setQueryResult] = useState<SolrQueryResult | null>(null);
  const [solrUrl, setSolrUrl] = useState<string>('');
  const [queryForm] = Form.useForm();
  const [folderIdInput, setFolderIdInput] = useState<string>('');
  const [recursiveReindex, setRecursiveReindex] = useState<boolean>(true);

  // RAG state
  const [ragHealthStatus, setRagHealthStatus] = useState<RAGHealthStatus | null>(null);
  const [ragReindexStatus, setRagReindexStatus] = useState<RAGReindexStatus | null>(null);
  const [ragSearchResults, setRagSearchResults] = useState<RAGSearchResult[]>([]);
  const [ragSearchForm] = Form.useForm();
  const [ragFolderIdInput, setRagFolderIdInput] = useState<string>('');
  const [ragRecursiveReindex, setRagRecursiveReindex] = useState<boolean>(true);
  const [ragLoading, setRagLoading] = useState(false);

  const { handleAuthError } = useAuth();
  const service = new SolrMaintenanceService(() => handleAuthError(null));
  const ragService = new RAGMaintenanceService(() => handleAuthError(null));

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
      message.error(`${t('solrMaintenance.healthCheck.error')}: ${errorMessage}`);
    } finally {
      setLoading(false);
    }
  }, [repositoryId, t]);

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
    loadRagHealthStatus();
    loadRagReindexStatus();
  }, [repositoryId, loadSolrUrl, loadHealthStatus, loadReindexStatus, loadRagHealthStatus, loadRagReindexStatus]);

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

  // RAG reindex status polling
  useEffect(() => {
    let intervalId: NodeJS.Timeout | null = null;

    if (ragReindexStatus?.status === 'running') {
      intervalId = setInterval(async () => {
        const status = await loadRagReindexStatus();
        if (status && status.status !== 'running') {
          if (intervalId) {
            clearInterval(intervalId);
          }
          loadRagHealthStatus();
        }
      }, 2000);
    }

    return () => {
      if (intervalId) {
        clearInterval(intervalId);
      }
    };
  }, [ragReindexStatus?.status, loadRagReindexStatus, loadRagHealthStatus]);

  const handleFullReindex = async () => {
    try {
      await service.startFullReindex(repositoryId);
      message.success(t('solrMaintenance.messages.fullReindexStarted'));
      loadReindexStatus();
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`${t('solrMaintenance.messages.fullReindexError')}: ${errorMessage}`);
    }
  };

  const handleFolderReindex = async () => {
    if (!folderIdInput.trim()) {
      message.warning(t('solrMaintenance.messages.folderIdRequired'));
      return;
    }
    try {
      await service.startFolderReindex(repositoryId, folderIdInput.trim(), recursiveReindex);
      message.success(t('solrMaintenance.messages.folderReindexStarted'));
      loadReindexStatus();
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`${t('solrMaintenance.messages.folderReindexError')}: ${errorMessage}`);
    }
  };

  const handleCancelReindex = async () => {
    try {
      await service.cancelReindex(repositoryId);
      message.success(t('solrMaintenance.messages.reindexCancelled'));
      loadReindexStatus();
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`${t('solrMaintenance.messages.cancelError')}: ${errorMessage}`);
    }
  };

  const handleClearIndex = async () => {
    try {
      await service.clearIndex(repositoryId);
      message.success(t('solrMaintenance.messages.indexCleared'));
      loadHealthStatus();
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`${t('solrMaintenance.messages.clearError')}: ${errorMessage}`);
    }
  };

  const handleOptimizeIndex = async () => {
    try {
      await service.optimizeIndex(repositoryId);
      message.success(t('solrMaintenance.messages.indexOptimized'));
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`${t('solrMaintenance.messages.optimizeError')}: ${errorMessage}`);
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
      message.error(`${t('solrMaintenance.messages.queryError')}: ${errorMessage}`);
    } finally {
      setLoading(false);
    }
  };

  // RAG handlers
  const loadRagHealthStatus = useCallback(async () => {
    setRagLoading(true);
    try {
      const status = await ragService.checkRAGHealth(repositoryId);
      setRagHealthStatus(status);
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`${t('ragMaintenance.messages.healthCheckError')}: ${errorMessage}`);
    } finally {
      setRagLoading(false);
    }
  }, [repositoryId, t]);

  const loadRagReindexStatus = useCallback(async () => {
    try {
      const status = await ragService.getRAGReindexStatus(repositoryId);
      setRagReindexStatus(status);
      return status;
    } catch (error: unknown) {
      console.error('Failed to load RAG reindex status:', error);
      return null;
    }
  }, [repositoryId]);

  const handleFullRagReindex = async () => {
    try {
      await ragService.startFullRAGReindex(repositoryId);
      message.success(t('ragMaintenance.messages.fullReindexStarted'));
      loadRagReindexStatus();
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`${t('ragMaintenance.messages.fullReindexError')}: ${errorMessage}`);
    }
  };

  const handleFolderRagReindex = async () => {
    if (!ragFolderIdInput.trim()) {
      message.warning(t('ragMaintenance.messages.folderIdRequired'));
      return;
    }
    try {
      await ragService.startFolderRAGReindex(repositoryId, ragFolderIdInput.trim(), ragRecursiveReindex);
      message.success(t('ragMaintenance.messages.folderReindexStarted'));
      loadRagReindexStatus();
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`${t('ragMaintenance.messages.folderReindexError')}: ${errorMessage}`);
    }
  };

  const handleCancelRagReindex = async () => {
    try {
      await ragService.cancelRAGReindex(repositoryId);
      message.success(t('ragMaintenance.messages.reindexCancelled'));
      loadRagReindexStatus();
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`${t('ragMaintenance.messages.cancelError')}: ${errorMessage}`);
    }
  };

  const handleClearRagIndex = async () => {
    try {
      await ragService.clearRAGIndex(repositoryId);
      message.success(t('ragMaintenance.messages.indexCleared'));
      loadRagHealthStatus();
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`${t('ragMaintenance.messages.clearError')}: ${errorMessage}`);
    }
  };

  const handleRagSearch = async (values: { query: string; topK: number; minScore: number; propertyBoost?: number; contentBoost?: number }) => {
    setRagLoading(true);
    try {
      const result = await ragService.search(
        repositoryId,
        values.query,
        values.topK || 10,
        values.minScore || 0.7,
        undefined,
        values.propertyBoost,
        values.contentBoost
      );
      setRagSearchResults(result.results);
      if (result.results.length === 0) {
        message.info(t('ragMaintenance.search.noResults'));
      }
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      message.error(`${t('ragMaintenance.messages.searchError')}: ${errorMessage}`);
    } finally {
      setRagLoading(false);
    }
  };

  const getStatusTag = (status: string) => {
    switch (status) {
      case 'idle':
        return <Tag color="default">{t('solrMaintenance.status.idle')}</Tag>;
      case 'running':
        return <Tag color="processing" icon={<SyncOutlined spin />}>{t('solrMaintenance.status.running')}</Tag>;
      case 'completed':
        return <Tag color="success" icon={<CheckCircleOutlined />}>{t('solrMaintenance.status.completed')}</Tag>;
      case 'error':
        return <Tag color="error" icon={<WarningOutlined />}>{t('solrMaintenance.status.error')}</Tag>;
      case 'cancelled':
        return <Tag color="warning">{t('solrMaintenance.status.cancelled')}</Tag>;
      default:
        return <Tag>{status}</Tag>;
    }
  };

  const queryColumns = [
    {
      title: t('solrMaintenance.query.field'),
      dataIndex: 'field',
      key: 'field',
      width: 200,
    },
    {
      title: t('solrMaintenance.query.value'),
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
      <Card title={t('solrMaintenance.healthCheck.title')} extra={
        <Button icon={<ReloadOutlined />} onClick={loadHealthStatus} loading={loading}>
          {t('solrMaintenance.healthCheck.refresh')}
        </Button>
      }>
        <Row gutter={16}>
          <Col span={6}>
            <Statistic
              title={t('solrMaintenance.healthCheck.solrDocuments')}
              value={healthStatus.solrDocumentCount}
              prefix={<SearchOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title={t('solrMaintenance.healthCheck.couchDbDocuments')}
              value={healthStatus.couchDbDocumentCount}
              prefix={<FolderOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title={t('solrMaintenance.healthCheck.missingInSolr')}
              value={healthStatus.missingInSolr}
              valueStyle={{ color: healthStatus.missingInSolr > 0 ? '#cf1322' : '#3f8600' }}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title={t('solrMaintenance.healthCheck.orphanedInSolr')}
              value={healthStatus.orphanedInSolr}
              valueStyle={{ color: healthStatus.orphanedInSolr > 0 ? '#faad14' : '#3f8600' }}
            />
          </Col>
        </Row>
        <div style={{ marginTop: 16 }}>
          {healthStatus.healthy ? (
            <Alert message={t('solrMaintenance.healthCheck.healthy')} type="success" showIcon />
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
      <Card title={t('solrMaintenance.reindexStatus.title')} style={{ marginTop: 16 }}>
        <Descriptions column={2}>
          <Descriptions.Item label={t('solrMaintenance.reindexStatus.status')}>{getStatusTag(reindexStatus.status)}</Descriptions.Item>
          <Descriptions.Item label={t('solrMaintenance.reindexStatus.currentFolder')}>{reindexStatus.currentFolder || '-'}</Descriptions.Item>
          <Descriptions.Item label={t('solrMaintenance.reindexStatus.totalDocuments')}>{reindexStatus.totalDocuments}</Descriptions.Item>
          <Descriptions.Item label={t('solrMaintenance.reindexStatus.indexed')}>{reindexStatus.indexedCount}</Descriptions.Item>
          <Descriptions.Item label={t('solrMaintenance.reindexStatus.errorCount')}>{reindexStatus.errorCount}</Descriptions.Item>
          <Descriptions.Item label={
            <Tooltip title={t('solrMaintenance.reindexStatus.silentDropCountTooltip')}>
              <span style={{ cursor: 'help', borderBottom: '1px dotted #999' }}>
                {t('solrMaintenance.reindexStatus.silentDropCount')}
              </span>
            </Tooltip>
          }>{reindexStatus.silentDropCount || 0}</Descriptions.Item>
          <Descriptions.Item label={
            <Tooltip title={t('solrMaintenance.reindexStatus.reindexedCountTooltip')}>
              <span style={{ cursor: 'help', borderBottom: '1px dotted #999' }}>
                {t('solrMaintenance.reindexStatus.reindexedCount')}
              </span>
            </Tooltip>
          }>{reindexStatus.reindexedCount || 0}</Descriptions.Item>
          <Descriptions.Item label={
            <Tooltip title={t('solrMaintenance.reindexStatus.verificationSkippedCountTooltip')}>
              <span style={{ cursor: 'help', borderBottom: '1px dotted #999' }}>
                {t('solrMaintenance.reindexStatus.verificationSkippedCount')}
              </span>
            </Tooltip>
          }>{reindexStatus.verificationSkippedCount || 0}</Descriptions.Item>
          <Descriptions.Item label={t('solrMaintenance.reindexStatus.errorMessage')}>{reindexStatus.errorMessage || '-'}</Descriptions.Item>
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
              {t('solrMaintenance.reindexStatus.cancel')}
            </Button>
          </div>
        )}
        {reindexStatus.warnings && reindexStatus.warnings.length > 0 && (
          <div style={{ marginTop: 16 }}>
            <Collapse
              items={[
                {
                  key: 'warnings',
                  label: <span style={{ color: '#faad14' }}>{t('solrMaintenance.reindexStatus.warningDetails', { count: reindexStatus.warnings.length })}</span>,
                  children: (
                    <List
                      size="small"
                      dataSource={reindexStatus.warnings}
                      renderItem={(warning: string, index: number) => (
                        <List.Item>
                          <Text type="warning" style={{ fontSize: '12px' }}>
                            {index + 1}. {warning}
                          </Text>
                        </List.Item>
                      )}
                      style={{ maxHeight: '300px', overflow: 'auto' }}
                    />
                  ),
                },
              ]}
            />
          </div>
        )}
        {reindexStatus.errors.length > 0 && (
          <div style={{ marginTop: 16 }}>
            <Collapse
              items={[
                {
                  key: 'errors',
                  label: t('solrMaintenance.reindexStatus.errorDetails', { count: reindexStatus.errors.length }),
                  children: (
                    <List
                      size="small"
                      dataSource={reindexStatus.errors}
                      renderItem={(error: string, index: number) => (
                        <List.Item>
                          <Text type="danger" style={{ fontSize: '12px' }}>
                            {index + 1}. {error}
                          </Text>
                        </List.Item>
                      )}
                      style={{ maxHeight: '300px', overflow: 'auto' }}
                    />
                  ),
                },
              ]}
            />
          </div>
        )}
      </Card>
    );
  };

  const renderReindexActions = () => (
    <Card title={t('solrMaintenance.reindexActions.title')} style={{ marginTop: 16 }}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <div>
          <Text strong>{t('solrMaintenance.reindexActions.fullReindex')}</Text>
          <p style={{ color: '#666', marginBottom: 8 }}>
            {t('solrMaintenance.reindexActions.fullReindexDesc')}
          </p>
          <Popconfirm
            title={t('solrMaintenance.reindexActions.fullReindexConfirm')}
            description={t('solrMaintenance.reindexActions.fullReindexConfirmDesc')}
            onConfirm={handleFullReindex}
            okText={t('solrMaintenance.reindexActions.execute')}
            cancelText={t('common.cancel')}
          >
            <Button
              type="primary"
              icon={<SyncOutlined />}
              disabled={reindexStatus?.status === 'running'}
            >
              {t('solrMaintenance.reindexActions.fullReindex')}
            </Button>
          </Popconfirm>
        </div>

        <div style={{ marginTop: 16 }}>
          <Text strong>{t('solrMaintenance.reindexActions.folderReindex')}</Text>
          <p style={{ color: '#666', marginBottom: 8 }}>
            {t('solrMaintenance.reindexActions.folderReindexDesc')}
          </p>
          <Space>
            <Input
              placeholder={t('solrMaintenance.reindexActions.folderId')}
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
              {' '}{t('solrMaintenance.reindexActions.includeSubfolders')}
            </label>
            <Button
              icon={<FolderOutlined />}
              onClick={handleFolderReindex}
              disabled={reindexStatus?.status === 'running'}
            >
              {t('solrMaintenance.reindexActions.folderReindexButton')}
            </Button>
          </Space>
        </div>

        <div style={{ marginTop: 16 }}>
          <Text strong>{t('solrMaintenance.reindexActions.indexManagement')}</Text>
          <p style={{ color: '#666', marginBottom: 8 }}>
            {t('solrMaintenance.reindexActions.indexManagementDesc')}
          </p>
          <Space>
            <Popconfirm
              title={t('solrMaintenance.reindexActions.clearIndexConfirm')}
              description={t('solrMaintenance.reindexActions.clearIndexConfirmDesc')}
              onConfirm={handleClearIndex}
              okText={t('common.clear')}
              cancelText={t('common.cancel')}
            >
              <Button danger icon={<ClearOutlined />}>
                {t('solrMaintenance.reindexActions.clearIndex')}
              </Button>
            </Popconfirm>
            <Popconfirm
              title={t('solrMaintenance.reindexActions.optimizeIndexConfirm')}
              description={t('solrMaintenance.reindexActions.optimizeIndexConfirmDesc')}
              onConfirm={handleOptimizeIndex}
              okText={t('solrMaintenance.reindexActions.optimize')}
              cancelText={t('common.cancel')}
            >
              <Button icon={<ThunderboltOutlined />}>
                {t('solrMaintenance.reindexActions.optimizeIndex')}
              </Button>
            </Popconfirm>
          </Space>
        </div>
      </Space>
    </Card>
  );

  const renderSolrQuery = () => (
    <Card title={t('solrMaintenance.query.title')} style={{ marginTop: 16 }}>
      <Form
        form={queryForm}
        layout="vertical"
        onFinish={handleExecuteQuery}
        initialValues={{ start: 0, rows: 10 }}
      >
        <Form.Item
          name="query"
          label={t('solrMaintenance.query.queryLabel')}
          rules={[{ required: true, message: t('solrMaintenance.query.queryRequired') }]}
        >
          <TextArea
            placeholder={t('solrMaintenance.query.queryPlaceholder')}
            rows={3}
          />
        </Form.Item>
        <Row gutter={16}>
          <Col span={6}>
            <Form.Item name="start" label={t('solrMaintenance.query.start')}>
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item 
              name="rows" 
              label={t('solrMaintenance.query.rows')}
              tooltip={t('solrMaintenance.query.rowsTooltip')}
            >
              <InputNumber min={1} max={1000} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="sort" label={t('solrMaintenance.query.sort')}>
              <Input placeholder={t('solrMaintenance.query.sortPlaceholder')} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="fields" label={t('solrMaintenance.query.fields')}>
              <Input placeholder={t('solrMaintenance.query.fieldsPlaceholder')} />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item>
          <Button type="primary" htmlType="submit" icon={<SearchOutlined />} loading={loading}>
            {t('solrMaintenance.query.executeQuery')}
          </Button>
        </Form.Item>
      </Form>

      {queryResult && (
        <div style={{ marginTop: 16 }}>
          <Alert
            message={queryResult.numFound === 0 
              ? t('solrMaintenance.query.noResults', { queryTime: queryResult.queryTime })
              : t('solrMaintenance.query.results', { 
                  numFound: queryResult.numFound, 
                  start: queryResult.start + 1, 
                  end: queryResult.start + queryResult.docs.length, 
                  queryTime: queryResult.queryTime 
                })}
            type="info"
            style={{ marginBottom: 16 }}
          />
          {queryResult.docs.map((doc, index) => (
            <Card
              key={index}
              size="small"
              title={t('solrMaintenance.query.document', { number: queryResult.start + index + 1 })}
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

  // RAG render functions
  const renderRagHealthStatus = () => {
    if (!ragHealthStatus) {
      return <Spin />;
    }

    return (
      <Card title={t('ragMaintenance.healthCheck.title')} extra={
        <Button icon={<ReloadOutlined />} onClick={loadRagHealthStatus} loading={ragLoading}>
          {t('ragMaintenance.healthCheck.refresh')}
        </Button>
      }>
        <Row gutter={16}>
          <Col span={6}>
            <Statistic
              title={t('ragMaintenance.healthCheck.ragEnabled')}
              value={ragHealthStatus.enabled ? t('ragMaintenance.healthCheck.available') : t('ragMaintenance.healthCheck.unavailable')}
              valueStyle={{ color: ragHealthStatus.enabled ? '#3f8600' : '#cf1322' }}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title={t('ragMaintenance.healthCheck.documentCount')}
              value={ragHealthStatus.ragDocumentCount}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title={t('ragMaintenance.healthCheck.chunkCount')}
              value={ragHealthStatus.ragChunkCount}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title="Eligible Documents"
              value={ragHealthStatus.eligibleDocuments}
            />
          </Col>
        </Row>
        <div style={{ marginTop: 16 }}>
          {ragHealthStatus.healthy ? (
            <Alert message={t('ragMaintenance.healthCheck.healthy')} type="success" showIcon />
          ) : (
            <Alert message={ragHealthStatus.message || t('ragMaintenance.healthCheck.unhealthy')} type="warning" showIcon />
          )}
        </div>
      </Card>
    );
  };

  const renderRagReindexStatus = () => {
    if (!ragReindexStatus) {
      return null;
    }

    const progress = ragReindexStatus.totalDocuments > 0
      ? Math.round((ragReindexStatus.indexedCount / ragReindexStatus.totalDocuments) * 100)
      : 0;

    return (
      <Card title={t('ragMaintenance.reindexStatus.title')} style={{ marginTop: 16 }}>
        <Descriptions column={2}>
          <Descriptions.Item label={t('ragMaintenance.reindexStatus.status')}>{getStatusTag(ragReindexStatus.status)}</Descriptions.Item>
          <Descriptions.Item label={t('ragMaintenance.reindexStatus.currentFolder')}>{ragReindexStatus.currentFolder || '-'}</Descriptions.Item>
          <Descriptions.Item label={t('ragMaintenance.reindexStatus.totalDocuments')}>{ragReindexStatus.totalDocuments}</Descriptions.Item>
          <Descriptions.Item label={t('ragMaintenance.reindexStatus.indexed')}>{ragReindexStatus.indexedCount}</Descriptions.Item>
          <Descriptions.Item label={t('ragMaintenance.reindexStatus.errorCount')}>{ragReindexStatus.errorCount}</Descriptions.Item>
          <Descriptions.Item label={t('ragMaintenance.reindexStatus.errorMessage')}>{ragReindexStatus.errorMessage || '-'}</Descriptions.Item>
        </Descriptions>
        {ragReindexStatus.status === 'running' && (
          <div style={{ marginTop: 16 }}>
            <Progress percent={progress} status="active" />
            <Button
              danger
              icon={<StopOutlined />}
              onClick={handleCancelRagReindex}
              style={{ marginTop: 8 }}
            >
              {t('ragMaintenance.reindexStatus.cancel')}
            </Button>
          </div>
        )}
        {ragReindexStatus.errors && ragReindexStatus.errors.length > 0 && (
          <div style={{ marginTop: 16 }}>
            <Collapse
              items={[
                {
                  key: 'errors',
                  label: t('ragMaintenance.reindexStatus.errorDetails', { count: ragReindexStatus.errors.length }),
                  children: (
                    <List
                      size="small"
                      dataSource={ragReindexStatus.errors}
                      renderItem={(error: string, index: number) => (
                        <List.Item>
                          <Text type="danger" style={{ fontSize: '12px' }}>
                            {index + 1}. {error}
                          </Text>
                        </List.Item>
                      )}
                      style={{ maxHeight: '300px', overflow: 'auto' }}
                    />
                  ),
                },
              ]}
            />
          </div>
        )}
      </Card>
    );
  };

  const renderRagReindexActions = () => (
    <Card title={t('ragMaintenance.reindexActions.title')} style={{ marginTop: 16 }}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <div>
          <Text strong>{t('ragMaintenance.reindexActions.fullReindex')}</Text>
          <p style={{ color: '#666', marginBottom: 8 }}>
            {t('ragMaintenance.reindexActions.fullReindexDesc')}
          </p>
          <Popconfirm
            title={t('ragMaintenance.reindexActions.fullReindexConfirm')}
            description={t('ragMaintenance.reindexActions.fullReindexConfirmDesc')}
            onConfirm={handleFullRagReindex}
            okText={t('ragMaintenance.reindexActions.execute')}
            cancelText={t('common.cancel')}
          >
            <Button
              type="primary"
              icon={<SyncOutlined />}
              disabled={ragReindexStatus?.status === 'running'}
            >
              {t('ragMaintenance.reindexActions.fullReindex')}
            </Button>
          </Popconfirm>
        </div>

        <div style={{ marginTop: 16 }}>
          <Text strong>{t('ragMaintenance.reindexActions.folderReindex')}</Text>
          <p style={{ color: '#666', marginBottom: 8 }}>
            {t('ragMaintenance.reindexActions.folderReindexDesc')}
          </p>
          <Space>
            <Input
              placeholder={t('ragMaintenance.reindexActions.folderId')}
              value={ragFolderIdInput}
              onChange={(e) => setRagFolderIdInput(e.target.value)}
              style={{ width: 300 }}
            />
            <label>
              <input
                type="checkbox"
                checked={ragRecursiveReindex}
                onChange={(e) => setRagRecursiveReindex(e.target.checked)}
              />
              {' '}{t('ragMaintenance.reindexActions.includeSubfolders')}
            </label>
            <Button
              icon={<FolderOutlined />}
              onClick={handleFolderRagReindex}
              disabled={ragReindexStatus?.status === 'running'}
            >
              {t('ragMaintenance.reindexActions.folderReindexButton')}
            </Button>
          </Space>
        </div>

        <div style={{ marginTop: 16 }}>
          <Text strong>{t('ragMaintenance.reindexActions.indexManagement')}</Text>
          <p style={{ color: '#666', marginBottom: 8 }}>
            {t('ragMaintenance.reindexActions.indexManagementDesc')}
          </p>
          <Popconfirm
            title={t('ragMaintenance.reindexActions.clearIndexConfirm')}
            description={t('ragMaintenance.reindexActions.clearIndexConfirmDesc')}
            onConfirm={handleClearRagIndex}
            okText={t('common.clear')}
            cancelText={t('common.cancel')}
          >
            <Button danger icon={<ClearOutlined />}>
              {t('ragMaintenance.reindexActions.clearIndex')}
            </Button>
          </Popconfirm>
        </div>
      </Space>
    </Card>
  );

  const renderRagSearch = () => (
    <Card title={t('ragMaintenance.search.title')} style={{ marginTop: 16 }}>
      <Form
        form={ragSearchForm}
        layout="vertical"
        onFinish={handleRagSearch}
        initialValues={{ topK: 10, minScore: 0.5, propertyBoost: 0.3, contentBoost: 0.7 }}
      >
        <Form.Item
          name="query"
          label={t('ragMaintenance.search.queryLabel')}
          rules={[{ required: true, message: t('ragMaintenance.search.queryRequired') }]}
        >
          <TextArea
            placeholder={t('ragMaintenance.search.queryPlaceholder')}
            rows={3}
          />
        </Form.Item>
        <Row gutter={16}>
          <Col span={6}>
            <Form.Item name="topK" label={t('ragMaintenance.search.topK')}>
              <InputNumber min={1} max={100} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="minScore" label={t('ragMaintenance.search.minScore')}>
              <InputNumber min={0} max={1} step={0.1} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="propertyBoost" label={t('ragMaintenance.search.propertyBoost')}>
              <InputNumber min={0} max={1} step={0.1} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="contentBoost" label={t('ragMaintenance.search.contentBoost')}>
              <InputNumber min={0} max={1} step={0.1} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item>
          <Button type="primary" htmlType="submit" icon={<SearchOutlined />} loading={ragLoading}>
            {t('ragMaintenance.search.executeSearch')}
          </Button>
        </Form.Item>
      </Form>

      {ragSearchResults.length > 0 && (
        <div style={{ marginTop: 16 }}>
          <Alert
            message={t('ragMaintenance.search.results', { count: ragSearchResults.length, queryTime: 0 })}
            type="info"
            style={{ marginBottom: 16 }}
          />
          {ragSearchResults.map((result, index) => (
            <Card
              key={result.chunkId}
              size="small"
              title={`${index + 1}. ${result.documentName}`}
              extra={<Tag color="blue">{t('ragMaintenance.search.resultItem.score')}: {(result.score * 100).toFixed(1)}%</Tag>}
              style={{ marginBottom: 8 }}
            >
              <Descriptions column={1} size="small">
                <Descriptions.Item label={t('ragMaintenance.search.resultItem.chunkIndex')}>
                  {result.chunkIndex}
                </Descriptions.Item>
                <Descriptions.Item label={t('ragMaintenance.search.resultItem.objectType')}>
                  {result.objectType}
                </Descriptions.Item>
                <Descriptions.Item label={t('ragMaintenance.search.resultItem.chunkText')}>
                  <Text style={{ whiteSpace: 'pre-wrap' }}>{result.chunkText}</Text>
                </Descriptions.Item>
              </Descriptions>
            </Card>
          ))}
        </div>
      )}
    </Card>
  );

  const tabItems = [
    {
      key: 'status',
      label: t('solrMaintenance.tabs.status'),
      children: (
        <>
          {renderHealthStatus()}
          {renderReindexStatus()}
        </>
      ),
    },
    {
      key: 'reindex',
      label: t('solrMaintenance.tabs.reindex'),
      children: renderReindexActions(),
    },
    {
      key: 'query',
      label: t('solrMaintenance.tabs.query'),
      children: renderSolrQuery(),
    },
    {
      key: 'rag-status',
      label: t('ragMaintenance.tabs.status'),
      children: (
        <>
          {renderRagHealthStatus()}
          {renderRagReindexStatus()}
        </>
      ),
    },
    {
      key: 'rag-reindex',
      label: t('ragMaintenance.tabs.reindex'),
      children: renderRagReindexActions(),
    },
    {
      key: 'rag-search',
      label: t('ragMaintenance.tabs.search'),
      children: renderRagSearch(),
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Card>
        <div style={{ marginBottom: 16 }}>
          <h2 style={{ margin: 0 }}>{t('solrMaintenance.title')}</h2>
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

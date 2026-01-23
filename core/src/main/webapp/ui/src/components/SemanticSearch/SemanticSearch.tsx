/**
 * Semantic Search Component using RAG
 *
 * Provides a UI for semantic (meaning-based) document search.
 * Uses vector embeddings to find documents similar in meaning to the query.
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Input, Button, List, Card, Typography, Space, Spin, Alert, Tooltip, Slider, Empty } from 'antd';
import { SearchOutlined, FileTextOutlined, FolderOutlined, InfoCircleOutlined, EyeOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { RAGService, RAGSearchResult, RAGHealthStatus } from '../../services/rag';
import { useAuth } from '../../contexts/AuthContext';

/** Minimum interval between searches in milliseconds (prevents rapid successive searches) */
const SEARCH_DEBOUNCE_MS = 300;

const { Search } = Input;
const { Text, Paragraph } = Typography;

interface SemanticSearchProps {
  repositoryId: string;
  folderId?: string;
  onDocumentClick?: (documentId: string) => void;
  baseUrl?: string;
  /** Admin only: Simulate search as another user */
  simulateAsUserId?: string;
}

export const SemanticSearch: React.FC<SemanticSearchProps> = ({
  repositoryId,
  folderId,
  onDocumentClick,
  baseUrl = '',
  simulateAsUserId
}) => {
  const { t } = useTranslation();
  const { isAuthenticated } = useAuth();

  const [query, setQuery] = useState('');
  const [results, setResults] = useState<RAGSearchResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [healthStatus, setHealthStatus] = useState<RAGHealthStatus | null>(null);
  const [checkingHealth, setCheckingHealth] = useState(true);

  // Search settings
  const [topK, setTopK] = useState(10);
  const [minScore, setMinScore] = useState(0.7);
  const [showSettings, setShowSettings] = useState(false);

  // Boost settings for weighted search
  const [propertyBoost, setPropertyBoost] = useState(0.3);
  const [contentBoost, setContentBoost] = useState(0.7);

  // Debounce tracking ref to prevent rapid successive searches
  const lastSearchTime = useRef<number>(0);

  // Check RAG health on mount
  useEffect(() => {
    if (isAuthenticated && repositoryId) {
      checkHealth();
    }
  }, [isAuthenticated, repositoryId]);

  const checkHealth = async () => {
    setCheckingHealth(true);
    console.log('[SemanticSearch] Starting health check...', { baseUrl, repositoryId, isAuthenticated });
    try {
      const service = new RAGService(baseUrl, repositoryId);
      const status = await service.getHealth();
      console.log('[SemanticSearch] Health check result:', status);
      setHealthStatus(status);
    } catch (err) {
      console.error('[SemanticSearch] RAG health check failed:', err);
      console.error('[SemanticSearch] Error details:', {
        message: err instanceof Error ? err.message : 'Unknown error',
        stack: err instanceof Error ? err.stack : undefined
      });
      setHealthStatus({ enabled: false, status: 'unavailable' });
    } finally {
      setCheckingHealth(false);
    }
  };

  const handleSearch = useCallback(async (searchQuery: string) => {
    if (!searchQuery.trim()) {
      setResults([]);
      return;
    }

    // Debounce: Prevent rapid successive searches
    const now = Date.now();
    if (now - lastSearchTime.current < SEARCH_DEBOUNCE_MS) {
      console.log('[SemanticSearch] Search debounced - too soon after last search');
      return;
    }
    lastSearchTime.current = now;

    setLoading(true);
    setError(null);

    try {
      const service = new RAGService(baseUrl, repositoryId);
      const response = await service.search({
        query: searchQuery,
        topK,
        minScore,
        folderId,
        propertyBoost,
        contentBoost,
        simulateAsUserId
      });
      setResults(response.results);
    } catch (err) {
      console.error('Semantic search failed:', err);
      setError(err instanceof Error ? err.message : t('semanticSearch.searchFailed'));
      setResults([]);
    } finally {
      setLoading(false);
    }
  }, [baseUrl, repositoryId, topK, minScore, folderId, propertyBoost, contentBoost, simulateAsUserId, t]);

  const handleDocumentClick = (documentId: string) => {
    if (onDocumentClick) {
      onDocumentClick(documentId);
    }
  };

  // Highlight matching text
  const highlightText = (text: string, maxLength: number = 300) => {
    if (text.length > maxLength) {
      return text.substring(0, maxLength) + '...';
    }
    return text;
  };

  // Format score as percentage
  const formatScore = (score: number) => {
    return `${Math.round(score * 100)}%`;
  };

  if (checkingHealth) {
    return (
      <Card>
        <div style={{ textAlign: 'center', padding: '20px' }}>
          <Spin tip={t('semanticSearch.checkingAvailability')} />
        </div>
      </Card>
    );
  }

  if (!healthStatus?.enabled) {
    return (
      <Card>
        <Alert
          message={t('semanticSearch.unavailable')}
          description={t('semanticSearch.unavailableDescription')}
          type="info"
          showIcon
        />
      </Card>
    );
  }

  return (
    <Card
      title={
        <Space>
          <SearchOutlined />
          {t('semanticSearch.title')}
          <Tooltip title={t('semanticSearch.helpText')}>
            <InfoCircleOutlined style={{ color: '#999' }} />
          </Tooltip>
        </Space>
      }
      extra={
        <Button
          type="link"
          onClick={() => setShowSettings(!showSettings)}
        >
          {showSettings ? t('common.hideSettings') : t('common.showSettings')}
        </Button>
      }
    >
      {/* Settings panel */}
      {showSettings && (
        <div style={{ marginBottom: 16, padding: 16, background: '#fafafa', borderRadius: 8 }}>
          <Space direction="vertical" style={{ width: '100%' }}>
            <div>
              <Text strong>{t('semanticSearch.maxResults')}: {topK}</Text>
              <Slider
                min={1}
                max={50}
                value={topK}
                onChange={setTopK}
              />
            </div>
            <div>
              <Text strong>{t('semanticSearch.minSimilarity')}: {formatScore(minScore)}</Text>
              <Slider
                min={0.1}
                max={1}
                step={0.05}
                value={minScore}
                onChange={setMinScore}
              />
            </div>
            <div style={{ marginTop: 8, borderTop: '1px solid #e8e8e8', paddingTop: 12 }}>
              <Text strong style={{ display: 'block', marginBottom: 8 }}>
                {t('semanticSearch.boostSettings')}
                <Tooltip title={t('semanticSearch.boostHelpText')}>
                  <InfoCircleOutlined style={{ marginLeft: 6, color: '#999' }} />
                </Tooltip>
              </Text>
              <div>
                <Text>{t('semanticSearch.propertyBoost')}: {formatScore(propertyBoost)}</Text>
                <Slider
                  min={0}
                  max={1}
                  step={0.1}
                  value={propertyBoost}
                  onChange={(value) => {
                    setPropertyBoost(value);
                    setContentBoost(1 - value);
                  }}
                  marks={{
                    0: t('semanticSearch.contentOnly'),
                    0.5: t('semanticSearch.balanced'),
                    1: t('semanticSearch.propertyOnly')
                  }}
                />
              </div>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {t('semanticSearch.propertyBoostDescription')}
              </Text>
            </div>
          </Space>
        </div>
      )}

      {/* Search input */}
      <Search
        placeholder={t('semanticSearch.placeholder')}
        allowClear
        enterButton={<><SearchOutlined /> {t('semanticSearch.searchButton')}</>}
        size="large"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onSearch={handleSearch}
        loading={loading}
      />

      {/* Folder scope indicator */}
      {folderId && (
        <div style={{ marginTop: 8 }}>
          <Text type="secondary">
            <FolderOutlined /> {t('semanticSearch.searchingInFolder')}
          </Text>
        </div>
      )}

      {/* Error message */}
      {error && (
        <Alert
          message={t('common.error')}
          description={error}
          type="error"
          showIcon
          closable
          onClose={() => setError(null)}
          style={{ marginTop: 16 }}
        />
      )}

      {/* Results */}
      <div style={{ marginTop: 16 }}>
        {loading ? (
          <div style={{ textAlign: 'center', padding: 40 }}>
            <Spin tip={t('semanticSearch.searching')} />
          </div>
        ) : results.length > 0 ? (
          <>
            <Text type="secondary" style={{ marginBottom: 8, display: 'block' }}>
              {t('semanticSearch.resultsCount', { count: results.length })}
            </Text>
            <List
              itemLayout="vertical"
              dataSource={results}
              renderItem={(item) => (
                <List.Item
                  key={item.chunkId}
                  style={{ cursor: 'default' }}
                  actions={[
                    <Button
                      key="view"
                      type="primary"
                      icon={<EyeOutlined />}
                      onClick={() => handleDocumentClick(item.documentId)}
                    >
                      {t('semanticSearch.viewDocument')}
                    </Button>
                  ]}
                >
                  <List.Item.Meta
                    avatar={<FileTextOutlined style={{ fontSize: 24, color: '#1890ff' }} />}
                    title={
                      <Space>
                        <Button
                          type="link"
                          style={{ padding: 0, height: 'auto' }}
                          onClick={() => handleDocumentClick(item.documentId)}
                        >
                          <Text strong style={{ fontSize: 16 }}>{item.documentName}</Text>
                        </Button>
                        <Text type="secondary">
                          {t('semanticSearch.similarity')}: {formatScore(item.score)}
                        </Text>
                      </Space>
                    }
                    description={
                      <Space direction="vertical" size={0}>
                        <Text type="secondary">{item.path}</Text>
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {t('semanticSearch.chunkIndex', { index: item.chunkIndex + 1 })}
                        </Text>
                      </Space>
                    }
                  />
                  <Paragraph
                    style={{
                      background: '#f5f5f5',
                      padding: '8px 12px',
                      borderRadius: 4,
                      marginTop: 8
                    }}
                  >
                    {highlightText(item.chunkText)}
                  </Paragraph>
                </List.Item>
              )}
            />
          </>
        ) : query && !loading ? (
          <Empty
            description={t('semanticSearch.noResults')}
            style={{ padding: 40 }}
          />
        ) : null}
      </div>
    </Card>
  );
};

export default SemanticSearch;

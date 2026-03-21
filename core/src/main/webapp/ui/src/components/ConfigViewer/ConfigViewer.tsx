import React, { useState, useEffect, useMemo } from 'react';
import { Table, Card, Tag, Input, Select, Space, message, InputNumber, Button, Typography } from 'antd';
import { ControlOutlined, ReloadOutlined, LockOutlined } from '@ant-design/icons';
import { CMISService } from '../../services/cmis';
import { getPasswordPolicy, updatePasswordPolicy } from '../../services/passwordPolicy';
import { useAuth } from '../../contexts/AuthContext';
import { useTranslation } from 'react-i18next';

const { Text } = Typography;

interface ConfigViewerProps {
  repositoryId: string;
}

interface PropertyInfo {
  key: string;
  value: string;
  source: string;
  category: string;
}

const sourceTagColor = (source: string): string => {
  if (source === 'system_property') return 'red';
  if (source === 'environment') return 'orange';
  if (source === 'couchdb_dynamic') return 'blue';
  if (source === 'default') return 'default';
  // Property file name
  return 'green';
};

export const ConfigViewer: React.FC<ConfigViewerProps> = ({ repositoryId }) => {
  const [properties, setProperties] = useState<PropertyInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [searchText, setSearchText] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string | undefined>(undefined);
  const [policyMinLength, setPolicyMinLength] = useState(0);
  const [policyInput, setPolicyInput] = useState(0);
  const [policySaving, setPolicySaving] = useState(false);
  const { t } = useTranslation();

  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

  useEffect(() => {
    loadProperties();
    loadPasswordPolicy();
  }, [repositoryId]);

  const loadPasswordPolicy = async () => {
    try {
      const policy = await getPasswordPolicy(repositoryId);
      setPolicyMinLength(policy.minLength);
      setPolicyInput(policy.minLength);
    } catch {
      setPolicyMinLength(0);
      setPolicyInput(0);
    }
  };

  const handleSavePolicy = async () => {
    setPolicySaving(true);
    try {
      const result = await updatePasswordPolicy(repositoryId, { minLength: policyInput });
      setPolicyMinLength(result.minLength);
      message.success(t('passwordPolicy.updateSuccess'));
    } catch (error: any) {
      message.error(error.message || t('passwordPolicy.updateError'));
    } finally {
      setPolicySaving(false);
    }
  };

  const loadProperties = async () => {
    setLoading(true);
    try {
      const result = await cmisService.getConfigProperties(repositoryId);
      setProperties(result);
    } catch (error) {
      message.error(t('configViewer.messages.loadError'));
    } finally {
      setLoading(false);
    }
  };

  const categories = useMemo(() => {
    const cats = new Set(properties.map(p => p.category));
    return Array.from(cats).sort();
  }, [properties]);

  const filteredProperties = useMemo(() => {
    return properties.filter(p => {
      const matchesSearch = !searchText ||
        p.key.toLowerCase().includes(searchText.toLowerCase()) ||
        p.value.toLowerCase().includes(searchText.toLowerCase());
      const matchesCategory = !selectedCategory || p.category === selectedCategory;
      return matchesSearch && matchesCategory;
    });
  }, [properties, searchText, selectedCategory]);

  const sourceLabel = (source: string): string => {
    if (source === 'system_property') return t('configViewer.sources.systemProperty');
    if (source === 'environment') return t('configViewer.sources.environment');
    if (source === 'couchdb_dynamic') return t('configViewer.sources.couchdbDynamic');
    if (source === 'default') return t('configViewer.sources.default');
    // Property file name - show as-is
    return source;
  };

  const columns = [
    {
      title: t('configViewer.columns.category'),
      dataIndex: 'category',
      key: 'category',
      width: 120,
      render: (category: string) => <Tag>{category}</Tag>,
      sorter: (a: PropertyInfo, b: PropertyInfo) => a.category.localeCompare(b.category),
    },
    {
      title: t('configViewer.columns.key'),
      dataIndex: 'key',
      key: 'key',
      ellipsis: true,
      sorter: (a: PropertyInfo, b: PropertyInfo) => a.key.localeCompare(b.key),
      defaultSortOrder: 'ascend' as const,
    },
    {
      title: t('configViewer.columns.value'),
      dataIndex: 'value',
      key: 'value',
      ellipsis: true,
      render: (value: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: '12px' }}>
          {value === '***' ? <Tag color="warning">***</Tag> : value}
        </span>
      ),
    },
    {
      title: t('configViewer.columns.source'),
      dataIndex: 'source',
      key: 'source',
      width: 200,
      render: (source: string) => (
        <Tag color={sourceTagColor(source)}>{sourceLabel(source)}</Tag>
      ),
      sorter: (a: PropertyInfo, b: PropertyInfo) => a.source.localeCompare(b.source),
    },
  ];

  return (
    <>
    <Card
      title={
        <Space>
          <LockOutlined />
          {t('passwordPolicy.title')}
        </Space>
      }
      style={{ marginBottom: 16 }}
    >
      <Space direction="vertical" size="small" style={{ width: '100%' }}>
        <Text type="secondary">{t('passwordPolicy.description')}</Text>
        <Space>
          <Text>{t('passwordPolicy.minLength')}:</Text>
          <InputNumber
            min={0}
            max={128}
            value={policyInput}
            onChange={(val) => setPolicyInput(val ?? 0)}
            style={{ width: 100 }}
          />
          <Button
            type="primary"
            size="small"
            loading={policySaving}
            disabled={policyInput === policyMinLength}
            onClick={handleSavePolicy}
          >
            {t('common.save')}
          </Button>
          <Text type="secondary">
            {policyMinLength > 0
              ? t('passwordPolicy.currentSetting', { min: policyMinLength })
              : t('passwordPolicy.currentSettingNone')}
          </Text>
        </Space>
      </Space>
    </Card>
    <Card
      title={
        <Space>
          <ControlOutlined />
          {t('configViewer.title')}
        </Space>
      }
      extra={
        <Space>
          <a onClick={loadProperties}><ReloadOutlined /> {t('common.reload')}</a>
        </Space>
      }
    >
      <Space style={{ marginBottom: 16 }} wrap>
        <Input.Search
          placeholder={t('configViewer.filters.searchPlaceholder')}
          allowClear
          onChange={(e) => setSearchText(e.target.value)}
          style={{ width: 300 }}
        />
        <Select
          placeholder={t('configViewer.filters.categoryPlaceholder')}
          allowClear
          onChange={(value) => setSelectedCategory(value)}
          style={{ width: 200 }}
          options={categories.map(c => ({ label: c, value: c }))}
        />
        <span style={{ color: '#999' }}>
          {t('configViewer.messages.totalItems', { count: filteredProperties.length })}
        </span>
      </Space>
      <Table
        columns={columns}
        dataSource={filteredProperties}
        rowKey="key"
        loading={loading}
        size="small"
        pagination={{
          pageSize: 50,
          showSizeChanger: true,
          pageSizeOptions: ['20', '50', '100', '200'],
          showTotal: (total) => t('configViewer.messages.totalItems', { count: total }),
        }}
      />
    </Card>
    </>
  );
};

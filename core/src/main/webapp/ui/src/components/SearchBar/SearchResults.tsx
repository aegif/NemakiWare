import React, { useState, useEffect } from 'react';
import { 
  Card, 
  Input, 
  Button, 
  Table, 
  Space, 
  Form, 
  Select, 
  DatePicker, 
  message,
  Tooltip
} from 'antd';
import { 
  SearchOutlined, 
  FileOutlined, 
  FolderOutlined,
  DownloadOutlined,
  EyeOutlined
} from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { CMISService } from '../../services/cmis';
import { CMISObject, SearchResult, TypeDefinition } from '../../types/cmis';

interface SearchResultsProps {
  repositoryId: string;
}

export const SearchResults: React.FC<SearchResultsProps> = ({ repositoryId }) => {
  const [searchParams, setSearchParams] = useSearchParams();
  const [searchResult, setSearchResult] = useState<SearchResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [types, setTypes] = useState<TypeDefinition[]>([]);
  const [form] = Form.useForm();
  const navigate = useNavigate();

  const cmisService = new CMISService();

  useEffect(() => {
    loadTypes();
    const query = searchParams.get('q');
    if (query) {
      form.setFieldsValue({ query });
      performSearch(query);
    }
  }, [repositoryId]);

  const loadTypes = async () => {
    try {
      const typeList = await cmisService.getTypes(repositoryId);
      setTypes(typeList);
    } catch (error) {
      console.error('タイプの読み込みに失敗しました');
    }
  };

  const performSearch = async (query: string) => {
    setLoading(true);
    try {
      const result = await cmisService.search(repositoryId, query);
      setSearchResult(result);
      setSearchParams({ q: query });
    } catch (error) {
      message.error('検索に失敗しました');
    } finally {
      setLoading(false);
    }
  };

  const handleSearch = (values: any) => {
    let query = '';
    
    if (values.query) {
      query = `SELECT * FROM cmis:document WHERE CONTAINS('${values.query}')`;
    } else {
      const conditions: string[] = [];
      
      if (values.objectType) {
        conditions.push(`cmis:objectTypeId = '${values.objectType}'`);
      }
      
      if (values.name) {
        conditions.push(`cmis:name LIKE '%${values.name}%'`);
      }
      
      if (values.createdFrom) {
        conditions.push(`cmis:creationDate >= TIMESTAMP '${values.createdFrom.toISOString()}'`);
      }
      
      if (values.createdTo) {
        conditions.push(`cmis:creationDate <= TIMESTAMP '${values.createdTo.toISOString()}'`);
      }
      
      if (values.createdBy) {
        conditions.push(`cmis:createdBy = '${values.createdBy}'`);
      }
      
      const baseType = values.baseType || 'cmis:document';
      query = `SELECT * FROM ${baseType}`;
      
      if (conditions.length > 0) {
        query += ` WHERE ${conditions.join(' AND ')}`;
      }
    }
    
    if (query) {
      performSearch(query);
    }
  };

  const handleDownload = (objectId: string) => {
    const url = cmisService.getDownloadUrl(repositoryId, objectId);
    window.open(url, '_blank');
  };

  const columns = [
    {
      title: 'タイプ',
      dataIndex: 'baseType',
      key: 'type',
      width: 60,
      render: (baseType: string) => (
        baseType === 'cmis:folder' ? 
          <FolderOutlined style={{ color: '#1890ff', fontSize: '16px' }} /> :
          <FileOutlined style={{ color: '#52c41a', fontSize: '16px' }} />
      ),
    },
    {
      title: '名前',
      dataIndex: 'name',
      key: 'name',
      render: (name: string, record: CMISObject) => (
        <Button 
          type="link" 
          onClick={() => navigate(`/documents/${record.id}`)}
        >
          {name}
        </Button>
      ),
    },
    {
      title: 'パス',
      dataIndex: 'path',
      key: 'path',
      ellipsis: true,
    },
    {
      title: 'オブジェクトタイプ',
      dataIndex: 'objectType',
      key: 'objectType',
      width: 150,
    },
    {
      title: 'サイズ',
      dataIndex: 'contentStreamLength',
      key: 'size',
      width: 100,
      render: (size: number) => size ? `${Math.round(size / 1024)} KB` : '-',
    },
    {
      title: '作成日時',
      dataIndex: 'creationDate',
      key: 'created',
      width: 180,
      render: (date: string) => date ? new Date(date).toLocaleString('ja-JP') : '-',
    },
    {
      title: '作成者',
      dataIndex: 'createdBy',
      key: 'createdBy',
      width: 120,
    },
    {
      title: 'アクション',
      key: 'actions',
      width: 120,
      render: (_, record: CMISObject) => (
        <Space>
          <Tooltip title="詳細表示">
            <Button 
              icon={<EyeOutlined />} 
              size="small"
              onClick={() => navigate(`/documents/${record.id}`)}
            />
          </Tooltip>
          {record.baseType === 'cmis:document' && (
            <Tooltip title="ダウンロード">
              <Button 
                icon={<DownloadOutlined />} 
                size="small"
                onClick={() => handleDownload(record.id)}
              />
            </Tooltip>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card title="検索" style={{ marginBottom: 16 }}>
        <Form
          form={form}
          onFinish={handleSearch}
          layout="vertical"
        >
          <Form.Item
            name="query"
            label="全文検索"
          >
            <Input.Search
              placeholder="検索キーワードを入力"
              enterButton={<SearchOutlined />}
              onSearch={(value) => handleSearch({ query: value })}
            />
          </Form.Item>
          
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 16 }}>
            <Form.Item
              name="baseType"
              label="ベースタイプ"
            >
              <Select placeholder="ベースタイプを選択">
                <Select.Option value="cmis:document">ドキュメント</Select.Option>
                <Select.Option value="cmis:folder">フォルダ</Select.Option>
              </Select>
            </Form.Item>
            
            <Form.Item
              name="objectType"
              label="オブジェクトタイプ"
            >
              <Select placeholder="オブジェクトタイプを選択" allowClear>
                {types.map(type => (
                  <Select.Option key={type.id} value={type.id}>
                    {type.displayName}
                  </Select.Option>
                ))}
              </Select>
            </Form.Item>
            
            <Form.Item
              name="name"
              label="名前"
            >
              <Input placeholder="名前で検索" />
            </Form.Item>
            
            <Form.Item
              name="createdBy"
              label="作成者"
            >
              <Input placeholder="作成者で検索" />
            </Form.Item>
            
            <Form.Item
              name="createdFrom"
              label="作成日（開始）"
            >
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
            
            <Form.Item
              name="createdTo"
              label="作成日（終了）"
            >
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          </div>
          
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={loading}>
                検索
              </Button>
              <Button onClick={() => form.resetFields()}>
                クリア
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      {searchResult && (
        <Card 
          title={`検索結果 (${searchResult.numItems}件)`}
          extra={searchResult.hasMoreItems && <span style={{ color: '#999' }}>さらに結果があります</span>}
        >
          <Table
            columns={columns}
            dataSource={searchResult.objects}
            rowKey="id"
            loading={loading}
            pagination={{ pageSize: 20 }}
            size="small"
          />
        </Card>
      )}
    </div>
  );
};

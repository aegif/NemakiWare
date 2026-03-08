import { useState } from 'react';
import { Form, Input, Button, Alert, Space, Tag } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { setupApi, ConnectionResult } from '../../../services/setupApi';

export interface CouchDbConfig {
  url: string;
  username: string;
  password: string;
}

interface CouchDbStepProps {
  value: CouchDbConfig;
  onChange: (config: CouchDbConfig) => void;
  onValidChange: (valid: boolean) => void;
}

export function CouchDbStep({ value, onChange, onValidChange }: CouchDbStepProps) {
  const { t } = useTranslation();
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<ConnectionResult | null>(null);

  const handleTest = async () => {
    setTesting(true);
    setResult(null);
    try {
      const res = await setupApi.testCouchDb(value);
      setResult(res);
      onValidChange(res.reachable);
    } catch (e) {
      setResult({ reachable: false, errorMessage: e instanceof Error ? e.message : String(e) });
      onValidChange(false);
    } finally {
      setTesting(false);
    }
  };

  const handleChange = (field: keyof CouchDbConfig, val: string) => {
    onChange({ ...value, [field]: val });
    // Reset validation when config changes
    setResult(null);
    onValidChange(false);
  };

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Form layout="vertical">
        <Form.Item label={t('setup.couchdb.url')}>
          <Input
            value={value.url}
            onChange={(e) => handleChange('url', e.target.value)}
            placeholder="http://couchdb:5984"
          />
        </Form.Item>
        <Form.Item label={t('setup.couchdb.username')}>
          <Input
            value={value.username}
            onChange={(e) => handleChange('username', e.target.value)}
            placeholder="admin"
          />
        </Form.Item>
        <Form.Item label={t('setup.couchdb.password')}>
          <Input.Password
            value={value.password}
            onChange={(e) => handleChange('password', e.target.value)}
          />
        </Form.Item>
      </Form>

      <Button type="primary" onClick={handleTest} loading={testing}>
        {t('setup.couchdb.testConnection')}
      </Button>

      {result && (
        result.reachable ? (
          <Alert
            type="success"
            showIcon
            icon={<CheckCircleOutlined />}
            message={t('setup.couchdb.connected')}
            description={
              <Space>
                <Tag color="green">CouchDB {result.couchDbVersion}</Tag>
                <Tag>{result.responseTimeMs}ms</Tag>
              </Space>
            }
          />
        ) : (
          <Alert
            type="error"
            showIcon
            icon={<CloseCircleOutlined />}
            message={t('setup.couchdb.connectionFailed')}
            description={result.errorMessage}
          />
        )
      )}
    </Space>
  );
}

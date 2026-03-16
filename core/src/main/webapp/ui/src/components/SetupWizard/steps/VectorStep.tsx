import { useState, useEffect } from 'react';
import { Form, Radio, Input, Button, Tag, Space, Alert, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { setupApi } from '../../../services/setupApi';

const { Text } = Typography;

export interface VectorConfig {
  type: 'none' | 'tei' | 'bedrock';
  url: string;
  region: string;
  modelId: string;
  accessKeyId: string;
  secretAccessKey: string;
}

interface VectorStepProps {
  value: VectorConfig;
  onChange: (config: VectorConfig) => void;
  onValidChange: (valid: boolean) => void;
}

export function VectorStep({ value, onChange, onValidChange }: VectorStepProps) {
  const { t } = useTranslation();
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ reachable: boolean; error?: string; dimension?: number; dimensionWarning?: string } | null>(null);

  // Report initial validity on mount (type defaults to 'none' → valid)
  useEffect(() => {
    onValidChange(value.type === 'none');
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleTypeChange = (type: 'none' | 'tei' | 'bedrock') => {
    onChange({ ...value, type });
    onValidChange(type === 'none');
    setTestResult(null);
  };

  const handleFieldChange = (field: keyof VectorConfig, val: string) => {
    onChange({ ...value, [field]: val });
    onValidChange(false);
    setTestResult(null);
  };

  const handleTest = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      const req = value.type === 'bedrock'
        ? {
            type: 'bedrock' as const,
            region: value.region,
            modelId: value.modelId,
            accessKeyId: value.accessKeyId || undefined,
            secretAccessKey: value.secretAccessKey || undefined,
          }
        : { type: 'tei' as const, url: value.url };
      const result = await setupApi.testVector(req);
      setTestResult(result);
      // Block setup if unreachable OR if dimension is incompatible with Solr schema (1024)
      const dimensionOk = !result.dimensionWarning;
      onValidChange(result.reachable && dimensionOk);
    } catch (e) {
      setTestResult({ reachable: false, error: e instanceof Error ? e.message : String(e) });
      onValidChange(false);
    } finally {
      setTesting(false);
    }
  };

  const testDisabled = value.type === 'tei' ? !value.url : !value.region;

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Form layout="vertical">
        <Form.Item label={t('setup.vector.type')}>
          <Radio.Group
            value={value.type}
            onChange={(e) => handleTypeChange(e.target.value)}
          >
            <Radio.Button value="none">{t('setup.vector.disabled')}</Radio.Button>
            <Radio.Button value="tei">{t('setup.vector.tei')}</Radio.Button>
            <Radio.Button value="bedrock">{t('setup.vector.bedrock')} <Tag color="orange" style={{ fontSize: '10px', lineHeight: '16px', padding: '0 4px', marginLeft: 2 }}>{t('common.beta')}</Tag></Radio.Button>
          </Radio.Group>
        </Form.Item>

        {value.type === 'tei' && (
          <Form.Item label={t('setup.vector.url')}>
            <Input
              value={value.url}
              onChange={(e) => handleFieldChange('url', e.target.value)}
              placeholder="http://tei:80"
            />
          </Form.Item>
        )}

        {value.type === 'bedrock' && (
          <>
            <Form.Item label={t('setup.vector.bedrockRegion')} required>
              <Input
                value={value.region}
                onChange={(e) => handleFieldChange('region', e.target.value)}
                placeholder="us-east-1"
              />
            </Form.Item>
            <Form.Item label={t('setup.vector.bedrockModelId')} required>
              <Input
                value={value.modelId}
                onChange={(e) => handleFieldChange('modelId', e.target.value)}
                placeholder="amazon.titan-embed-text-v2:0"
              />
            </Form.Item>
            <Form.Item label={t('setup.vector.bedrockAccessKeyId')}>
              <Input
                value={value.accessKeyId}
                onChange={(e) => handleFieldChange('accessKeyId', e.target.value)}
              />
            </Form.Item>
            <Form.Item label={t('setup.vector.bedrockSecretAccessKey')}>
              <Input.Password
                value={value.secretAccessKey}
                onChange={(e) => handleFieldChange('secretAccessKey', e.target.value)}
              />
            </Form.Item>
            <Text type="secondary">{t('setup.vector.bedrockCredentialNote')}</Text>
          </>
        )}

        {value.type !== 'none' && (
          <Form.Item>
            <Space>
              <Button onClick={handleTest} loading={testing} disabled={testDisabled}>
                {t('setup.vector.testConnection')}
              </Button>
              {testResult && (
                <Space direction="vertical" size="small">
                  <Space>
                    <Tag color={testResult.reachable ? 'green' : 'red'}>
                      {testResult.reachable ? t('setup.vector.connected') : t('setup.vector.connectionFailed')}
                    </Tag>
                    {testResult.reachable && testResult.dimension != null && testResult.dimension > 0 && (
                      <Tag color="blue">dimension: {testResult.dimension}</Tag>
                    )}
                  </Space>
                  {!testResult.reachable && testResult.error && (
                    <Alert type="warning" message={testResult.error} showIcon />
                  )}
                  {testResult.reachable && testResult.dimensionWarning && (
                    <Alert type="error" message={testResult.dimensionWarning} showIcon />
                  )}
                </Space>
              )}
            </Space>
          </Form.Item>
        )}
      </Form>
    </Space>
  );
}

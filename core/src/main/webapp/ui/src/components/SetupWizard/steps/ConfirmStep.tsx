import { useState } from 'react';
import { Descriptions, Button, Result, Spin, Alert, Tag, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import { setupApi, ApplyResult } from '../../../services/setupApi';
import type { CouchDbConfig } from './CouchDbStep';
import type { AuthConfig } from './AuthStep';
import type { AdminConfig } from './AdminStep';
import type { VectorConfig } from './VectorStep';

interface ConfirmStepProps {
  couchdb: CouchDbConfig;
  auth: AuthConfig;
  admin: AdminConfig;
  vector: VectorConfig;
  onComplete: () => void;
}

export function ConfirmStep({ couchdb, auth, admin, vector, onComplete }: ConfirmStepProps) {
  const { t } = useTranslation();
  const [applying, setApplying] = useState(false);
  const [proceeding, setProceeding] = useState(false);
  const [result, setResult] = useState<ApplyResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleApply = async () => {
    setApplying(true);
    setError(null);
    try {
      const res = await setupApi.apply({
        couchdb: {
          url: couchdb.url,
          username: couchdb.username,
          password: couchdb.password,
        },
        auth: {
          passwordEnabled: auth.passwordEnabled,
          googleEnabled: auth.googleEnabled,
          microsoftEnabled: auth.microsoftEnabled,
          googleClientId: auth.googleEnabled ? auth.googleClientId : undefined,
          microsoftClientId: auth.microsoftEnabled ? auth.microsoftClientId : undefined,
          microsoftTenantId: auth.microsoftEnabled ? auth.microsoftTenantId : undefined,
        },
        vector: {
          type: vector.type,
          url: vector.type === 'tei' ? vector.url : undefined,
          region: vector.type === 'bedrock' ? vector.region : undefined,
          modelId: vector.type === 'bedrock' ? (vector.modelId || 'amazon.titan-embed-text-v2:0') : undefined,
          accessKeyId: vector.type === 'bedrock' && vector.accessKeyId ? vector.accessKeyId : undefined,
          secretAccessKey: vector.type === 'bedrock' && vector.secretAccessKey ? vector.secretAccessKey : undefined,
        },
        adminPassword: admin.newPassword || undefined,
      });
      setResult(res);
      if (!res.success) {
        setError(res.error || t('setup.confirm.applyFailed'));
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setApplying(false);
    }
  };

  if (applying) {
    return (
      <div style={{ textAlign: 'center', padding: 48 }}>
        <Spin size="large" />
        <p style={{ marginTop: 16 }}>{t('setup.confirm.applying')}</p>
      </div>
    );
  }

  const handleProceedAnyway = async () => {
    setProceeding(true);
    setError(null);
    try {
      await setupApi.markComplete();
      onComplete();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setProceeding(false);
    }
  };

  const handleRetry = () => {
    setResult(null);
    setError(null);
  };

  if (result?.success) {
    const hasWarnings = result.warnings && result.warnings.length > 0;
    return (
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {error && <Alert type="error" showIcon message={error} />}
        <Result
          status={hasWarnings ? 'warning' : 'success'}
          title={hasWarnings ? t('setup.confirm.successWithWarnings') : t('setup.confirm.success')}
          subTitle={t('setup.confirm.successDescription')}
          extra={
            hasWarnings ? (
              <Space>
                <Button onClick={handleRetry} disabled={proceeding}>
                  {t('setup.confirm.retry')}
                </Button>
                <Button type="primary" size="large" onClick={handleProceedAnyway} loading={proceeding}>
                  {t('setup.confirm.proceedAnyway')}
                </Button>
              </Space>
            ) : (
              <Button type="primary" size="large" onClick={onComplete}>
                {t('setup.confirm.startNemakiWare')}
              </Button>
            )
          }
        />
        {hasWarnings && (
          <Alert
            type="warning"
            showIcon
            message={t('setup.confirm.warningsTitle')}
            description={
              <ul style={{ margin: 0, paddingLeft: 20 }}>
                {result.warnings!.map((w, i) => <li key={i}>{w}</li>)}
              </ul>
            }
          />
        )}
        <Alert
          type="info"
          showIcon
          message={t('setup.confirm.persistenceNote')}
        />
      </Space>
    );
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {error && <Alert type="error" showIcon message={error} />}

      <Descriptions bordered column={1} size="small">
        <Descriptions.Item label={t('setup.couchdb.url')}>{couchdb.url}</Descriptions.Item>
        <Descriptions.Item label={t('setup.couchdb.username')}>{couchdb.username}</Descriptions.Item>
        <Descriptions.Item label={t('setup.auth.password')}>
          <Tag color={auth.passwordEnabled ? 'green' : 'default'}>
            {auth.passwordEnabled ? t('common.enabled') : t('common.disabled')}
          </Tag>
        </Descriptions.Item>
        <Descriptions.Item label={t('setup.auth.google')}>
          <Tag color={auth.googleEnabled ? 'green' : 'default'}>
            {auth.googleEnabled ? t('common.enabled') : t('common.disabled')}
          </Tag>
        </Descriptions.Item>
        <Descriptions.Item label={t('setup.auth.microsoft')}>
          <Tag color={auth.microsoftEnabled ? 'green' : 'default'}>
            {auth.microsoftEnabled ? t('common.enabled') : t('common.disabled')}
          </Tag>
        </Descriptions.Item>
        <Descriptions.Item label={t('setup.admin.newPassword')}>
          {admin.newPassword ? '********' : t('setup.admin.noChange')}
        </Descriptions.Item>
        <Descriptions.Item label={t('setup.vector.type')}>
          <Tag color={vector.type !== 'none' ? 'blue' : 'default'}>
            {vector.type === 'tei' ? t('setup.vector.tei') : vector.type === 'bedrock' ? t('setup.vector.bedrock') : t('setup.vector.disabled')}
          </Tag>
        </Descriptions.Item>
        {vector.type === 'tei' && (
          <Descriptions.Item label={t('setup.vector.url')}>{vector.url}</Descriptions.Item>
        )}
        {vector.type === 'bedrock' && (
          <>
            <Descriptions.Item label={t('setup.vector.bedrockRegion')}>{vector.region}</Descriptions.Item>
            <Descriptions.Item label={t('setup.vector.bedrockModelId')}>{vector.modelId || 'amazon.titan-embed-text-v2:0'}</Descriptions.Item>
            <Descriptions.Item label={t('setup.vector.bedrockCredentials')}>
              {vector.accessKeyId ? t('setup.vector.bedrockExplicitCredentials') : t('setup.vector.bedrockDefaultCredentials')}
            </Descriptions.Item>
          </>
        )}
      </Descriptions>

      <Button type="primary" size="large" block onClick={handleApply} loading={applying}>
        {t('setup.confirm.apply')}
      </Button>
    </Space>
  );
}

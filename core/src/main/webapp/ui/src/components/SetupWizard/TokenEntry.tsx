import { useState } from 'react';
import { Input, Button, Alert, Typography, Space, Card } from 'antd';
import { KeyOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { setupApi } from '../../services/setupApi';

const { Title, Paragraph } = Typography;

interface TokenEntryProps {
  onTokenVerified: (token: string) => void;
}

export function TokenEntry({ onTokenVerified }: TokenEntryProps) {
  const { t } = useTranslation();
  const [token, setToken] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleVerify = async () => {
    if (!token.trim()) return;

    setLoading(true);
    setError(null);

    try {
      // Verify token by calling a token-required GET endpoint.
      // getAuthState() uses the shared request() helper which checks response.ok
      // and throws on 401/403, so a successful return proves the token is valid.
      setupApi.setToken(token.trim());
      await setupApi.getAuthState();
      onTokenVerified(token.trim());
    } catch (e) {
      // Any error (401, network, 500) → token NOT accepted.
      // Reset the token in the API client so stale values don't leak.
      setupApi.setToken('');
      const msg = e instanceof Error ? e.message : String(e);
      if (msg.includes('401') || msg.includes('Unauthorized')) {
        setError(t('setup.token.invalid'));
      } else {
        setError(msg);
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f5f5f5' }}>
      <Card style={{ width: 480, textAlign: 'center' }}>
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <KeyOutlined style={{ fontSize: 48, color: '#1890ff' }} />
          <Title level={3}>{t('setup.token.title')}</Title>
          <Paragraph type="secondary">
            {t('setup.token.description')}
          </Paragraph>
          <Paragraph type="secondary" style={{ fontSize: 12 }}>
            <code>docker exec &lt;container&gt; cat /usr/local/tomcat/conf/setup-token</code>
          </Paragraph>

          {error && <Alert type="error" message={error} showIcon />}

          <Input.Password
            size="large"
            placeholder={t('setup.token.placeholder')}
            value={token}
            onChange={(e) => setToken(e.target.value)}
            onPressEnter={handleVerify}
          />
          <Button
            type="primary"
            size="large"
            block
            loading={loading}
            onClick={handleVerify}
            disabled={!token.trim()}
          >
            {t('setup.token.verify')}
          </Button>
        </Space>
      </Card>
    </div>
  );
}

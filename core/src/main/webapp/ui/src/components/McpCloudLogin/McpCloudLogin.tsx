/**
 * MCP Cloud Login Component
 *
 * Handles the completion of cloud-based MCP login.
 * When a user initiates cloud login from Claude Desktop, they receive a URL
 * with a login code. This component:
 * 1. Displays the login code for verification
 * 2. Allows the user to authenticate with their cloud provider
 * 3. Completes the MCP login by submitting the code with the authenticated user ID
 */

import React, { useState, useEffect } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import {
  Card,
  Button,
  Typography,
  Space,
  Alert,
  Spin,
  Result,
  message
} from 'antd';
import {
  CloudOutlined,
  CheckCircleOutlined,
  LoginOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { CloudAuthConfig, fetchCloudAuthConfig, signInWithGoogle, signInWithMicrosoft } from '../../services/cloud-auth';

const { Title, Text, Paragraph } = Typography;

// LocalStorage keys for MCP pending login
// Using localStorage instead of sessionStorage because OAuth libraries (MSAL, Google)
// may clear or interfere with sessionStorage during authentication flows
const MCP_PENDING_LOGIN_CODE_KEY = 'mcp_pending_login_code';
const MCP_PENDING_REQUEST_ID_KEY = 'mcp_pending_request_id';

interface McpCloudLoginProps {
  repositoryId?: string;
}

export const McpCloudLogin: React.FC<McpCloudLoginProps> = ({
  repositoryId = 'bedroom'
}) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { isAuthenticated, authToken, login } = useAuth();

  // Get requestId and loginCode from URL params or localStorage (for returning from login page)
  const urlRequestId = searchParams.get('requestId');
  const urlCode = searchParams.get('code');
  const storedRequestId = localStorage.getItem(MCP_PENDING_REQUEST_ID_KEY);
  const storedCode = localStorage.getItem(MCP_PENDING_LOGIN_CODE_KEY);

  // Use URL values if available, otherwise fall back to stored values
  const requestId = urlRequestId || storedRequestId;
  const loginCode = urlCode || storedCode;

  // Save requestId and code to localStorage when we have URL values (for recovery if tab is closed)
  useEffect(() => {
    if (urlRequestId && urlCode) {
      // SECURITY: Don't log the credentials - they're shared secrets
      localStorage.setItem(MCP_PENDING_REQUEST_ID_KEY, urlRequestId);
      localStorage.setItem(MCP_PENDING_LOGIN_CODE_KEY, urlCode);
    }
  }, [urlRequestId, urlCode]);
  const [status, setStatus] = useState<'pending' | 'submitting' | 'success' | 'error'>('pending');
  const [errorMessage, setErrorMessage] = useState<string>('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [cloudAuthConfig, setCloudAuthConfig] = useState<CloudAuthConfig | null>(null);
  const [isLoadingConfig, setIsLoadingConfig] = useState(true);

  // Load cloud auth configuration
  useEffect(() => {
    const loadConfig = async () => {
      try {
        const config = await fetchCloudAuthConfig();
        setCloudAuthConfig(config);
      } catch (error) {
        console.error('Failed to load cloud auth config:', error);
      } finally {
        setIsLoadingConfig(false);
      }
    };
    loadConfig();
  }, []);

  // Auto-submit when user is authenticated and both requestId and loginCode are available
  useEffect(() => {
    if (isAuthenticated && authToken && requestId && loginCode && status === 'pending') {
      completeLogin();
    }
  }, [isAuthenticated, authToken, requestId, loginCode, status]);

  const completeLogin = async () => {
    // SECURITY: Both requestId and loginCode are required
    if (!requestId || !loginCode || !authToken?.username) {
      return;
    }

    setIsSubmitting(true);
    setStatus('submitting');

    try {
      const response = await fetch(`/core/rest/repo/${repositoryId}/mcp/cloud-login/complete`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          requestId: requestId,
          loginCode: loginCode
          // Note: userId is now obtained from server-side authenticated session
        }),
        credentials: 'include'
      });

      const result = await response.json();

      if (result.status === 'success') {
        // Clear stored pending login data on success
        localStorage.removeItem(MCP_PENDING_REQUEST_ID_KEY);
        localStorage.removeItem(MCP_PENDING_LOGIN_CODE_KEY);
        setStatus('success');
      } else {
        setStatus('error');
        setErrorMessage(result.errMsg?.[0]?.message || t('mcpCloudLogin.errors.completionFailed'));
      }
    } catch (error: any) {
      setStatus('error');
      setErrorMessage(error.message || t('mcpCloudLogin.errors.networkError'));
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleGoogleLogin = async () => {
    if (!cloudAuthConfig?.googleEnabled || !cloudAuthConfig.googleClientId) {
      message.error('Google authentication is not configured');
      return;
    }

    try {
      const auth = await signInWithGoogle(cloudAuthConfig.googleClientId, repositoryId);
      // Login successful - update auth context
      login(auth);
      // completeLogin will be called by useEffect when isAuthenticated changes
    } catch (error: any) {
      console.error('Google login error:', error);
      setStatus('error');
      setErrorMessage(error.message || 'Google authentication failed');
    }
  };

  const handleMicrosoftLogin = async () => {
    if (!cloudAuthConfig?.microsoftEnabled || !cloudAuthConfig.microsoftClientId) {
      message.error('Microsoft authentication is not configured');
      return;
    }

    try {
      const auth = await signInWithMicrosoft(
        cloudAuthConfig.microsoftClientId,
        cloudAuthConfig.microsoftTenantId || 'common',
        repositoryId
      );
      // Login successful - update auth context
      login(auth);
      // completeLogin will be called by useEffect when isAuthenticated changes
    } catch (error: any) {
      console.error('Microsoft login error:', error);
      setStatus('error');
      setErrorMessage(error.message || 'Microsoft authentication failed');
    }
  };

  // No requestId or login code provided
  if (!requestId || !loginCode) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
        <Card style={{ maxWidth: 480, width: '100%', textAlign: 'center' }}>
          <Result
            status="warning"
            title={t('mcpCloudLogin.noCode.title')}
            subTitle={t('mcpCloudLogin.noCode.description')}
            extra={
              <Button type="primary" onClick={() => navigate('/documents')}>
                {t('mcpCloudLogin.noCode.goHome')}
              </Button>
            }
          />
        </Card>
      </div>
    );
  }


  // Login completed successfully
  if (status === 'success') {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
        <Card style={{ maxWidth: 480, width: '100%', textAlign: 'center' }}>
          <Result
            status="success"
            icon={<CheckCircleOutlined style={{ color: '#52c41a' }} />}
            title={t('mcpCloudLogin.success.title')}
            subTitle={t('mcpCloudLogin.success.description')}
            extra={
              <Space direction="vertical" style={{ width: '100%' }}>
                <Alert
                  message={t('mcpCloudLogin.success.instruction')}
                  type="info"
                  showIcon
                />
                <Button
                  onClick={() => {
                    // window.close() only works for windows opened by JavaScript
                    // For externally opened windows, we show a message
                    window.close();
                    // If window didn't close, show message
                    message.info(t('mcpCloudLogin.success.closeManually', 'このタブを手動で閉じてください'));
                  }}
                >
                  {t('mcpCloudLogin.success.closeWindow')}
                </Button>
              </Space>
            }
          />
        </Card>
      </div>
    );
  }

  // Error state
  if (status === 'error') {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
        <Card style={{ maxWidth: 480, width: '100%', textAlign: 'center' }}>
          <Result
            status="error"
            title={t('mcpCloudLogin.error.title')}
            subTitle={errorMessage}
            extra={
              <Space>
                <Button onClick={() => setStatus('pending')}>
                  {t('mcpCloudLogin.error.retry')}
                </Button>
                <Button onClick={() => navigate('/documents')}>
                  {t('mcpCloudLogin.noCode.goHome')}
                </Button>
              </Space>
            }
          />
        </Card>
      </div>
    );
  }

  // Submitting state
  if (status === 'submitting' || isSubmitting) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
        <Card style={{ maxWidth: 480, width: '100%', textAlign: 'center' }}>
          <Spin size="large" />
          <Title level={4} style={{ marginTop: 24 }}>
            {t('mcpCloudLogin.submitting.title')}
          </Title>
          <Paragraph type="secondary">
            {t('mcpCloudLogin.submitting.description')}
          </Paragraph>
        </Card>
      </div>
    );
  }

  // Loading config
  if (isLoadingConfig) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
        <Card style={{ maxWidth: 480, width: '100%', textAlign: 'center' }}>
          <Spin size="large" />
          <Paragraph style={{ marginTop: 16 }}>{t('common.loading')}</Paragraph>
        </Card>
      </div>
    );
  }

  const hasCloudAuth = cloudAuthConfig?.googleEnabled || cloudAuthConfig?.microsoftEnabled;

  // Main view - user needs to authenticate
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
      <Card style={{ maxWidth: 480, width: '100%' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <CloudOutlined style={{ fontSize: 48, color: '#1890ff' }} />
          <Title level={3} style={{ marginTop: 16, marginBottom: 8 }}>
            {t('mcpCloudLogin.title')}
          </Title>
          <Paragraph type="secondary">
            {t('mcpCloudLogin.description')}
          </Paragraph>
        </div>

        <Alert
          message={t('mcpCloudLogin.codeLabel')}
          description={
            <Text strong style={{ fontSize: 24, letterSpacing: '0.2em' }}>
              {loginCode}
            </Text>
          }
          type="info"
          showIcon
          style={{ marginBottom: 24 }}
        />

        {isAuthenticated ? (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Alert
              message={t('mcpCloudLogin.authenticated.message', { username: authToken?.username })}
              type="success"
              showIcon
            />
            <Button
              type="primary"
              size="large"
              block
              loading={isSubmitting}
              onClick={completeLogin}
            >
              {t('mcpCloudLogin.authenticated.completeButton')}
            </Button>
          </Space>
        ) : (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Paragraph>{t('mcpCloudLogin.authenticatePrompt')}</Paragraph>

            <Alert
              message={t('mcpCloudLogin.loginInstructions', '通常のNemakiWareログインを使用してください')}
              description={
                <div>
                  <p>{t('mcpCloudLogin.loginSteps', '1. 下のボタンで新しいタブでログインページを開きます')}</p>
                  <p>{t('mcpCloudLogin.loginSteps2', '2. ログイン後、このページに戻って「完了」をクリックしてください')}</p>
                </div>
              }
              type="info"
              showIcon
              style={{ marginBottom: 16, textAlign: 'left' }}
            />

            <Space direction="vertical" style={{ width: '100%' }}>
              <Button
                size="large"
                block
                icon={<LoginOutlined />}
                onClick={() => {
                  // Open login in new tab so user can return to this page
                  window.open('/core/ui/#/', '_blank');
                }}
              >
                {t('mcpCloudLogin.openLoginPage', 'ログインページを新しいタブで開く')}
              </Button>

              <Button
                type="primary"
                size="large"
                block
                onClick={() => {
                  // Reload this page to check if user is now authenticated
                  window.location.reload();
                }}
              >
                {t('mcpCloudLogin.checkLogin', 'ログイン確認・完了')}
              </Button>
            </Space>
          </Space>
        )}
      </Card>
    </div>
  );
};

export default McpCloudLogin;

import React, { useState, useEffect } from 'react';
import { Form, Input, Button, Card, Alert, Select, Divider } from 'antd';
import { UserOutlined, LockOutlined, DatabaseOutlined, LoginOutlined } from '@ant-design/icons';
import { AuthService, AuthToken } from '../../services/auth';
import { CMISService } from '../../services/cmis';
import { OIDCService } from '../../services/oidc';
import { getOIDCConfig, isOIDCEnabled } from '../../config/oidc';
import { SAMLService } from '../../services/saml';
import { getSAMLConfig, isSAMLEnabled } from '../../config/saml';

interface LoginProps {
  onLogin: (auth: AuthToken) => void;
}

export const Login: React.FC<LoginProps> = ({ onLogin }) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [repositories, setRepositories] = useState<string[]>([]);
  const [form] = Form.useForm();

  const authService = AuthService.getInstance();
  const cmisService = new CMISService();
  
  const [oidcService] = useState(() => {
    if (isOIDCEnabled()) {
      return new OIDCService(getOIDCConfig());
    }
    return null;
  });

  const [samlService] = useState(() => {
    if (isSAMLEnabled()) {
      return new SAMLService(getSAMLConfig());
    }
    return null;
  });
  
  useEffect(() => {
    (window as any).authService = authService;
  }, [authService]);

  useEffect(() => {
    loadRepositories();
  }, []);

  useEffect(() => {
    if (window.location.pathname.includes('oidc-callback') && oidcService) {
      handleOIDCLogin();
    } else if (window.location.pathname.includes('saml-callback') && samlService) {
      handleSAMLCallback();
    }
  }, [oidcService, samlService]);

  const loadRepositories = async () => {
    try {
      const repos = await cmisService.getRepositories();
      setRepositories(repos);
      if (repos.length === 1) {
        form.setFieldsValue({ repositoryId: repos[0] });
      }
    } catch (error) {
      setRepositories(['bedroom']);
    }
  };

  const handleSubmit = async (values: { username: string; password: string; repositoryId: string }) => {
    setLoading(true);
    setError(null);

    try {
      const auth = await authService.login(values.username, values.password, values.repositoryId);
      onLogin(auth);
    } catch (error) {
      setError('ログインに失敗しました。ユーザー名、パスワード、リポジトリIDを確認してください。');
    } finally {
      setLoading(false);
    }
  };

  const handleOIDCLogin = async () => {
    if (!oidcService) return;
    
    setLoading(true);
    setError(null);

    try {
      if (window.location.pathname.includes('oidc-callback')) {
        const oidcUser = await oidcService.signinRedirectCallback();
        const repositoryId = repositories.length > 0 ? repositories[0] : 'bedroom';
        const auth = await oidcService.convertOIDCToken(oidcUser, repositoryId);
        onLogin(auth);
      } else {
        await oidcService.signinRedirect();
      }
    } catch (error) {
      console.error('OIDC login error:', error);
      setError('OIDC認証に失敗しました。');
    } finally {
      setLoading(false);
    }
  };

  const handleSAMLLogin = async () => {
    if (!samlService) return;
    
    setLoading(true);
    setError(null);

    try {
      const repositoryId = repositories.length > 0 ? repositories[0] : 'bedroom';
      samlService.initiateLogin(repositoryId);
    } catch (error) {
      console.error('SAML login error:', error);
      setError('SAML認証に失敗しました。');
      setLoading(false);
    }
  };

  const handleSAMLCallback = async () => {
    if (!samlService) return;
    
    setLoading(true);
    setError(null);

    try {
      const urlParams = new URLSearchParams(window.location.search);
      const samlResponse = urlParams.get('SAMLResponse');
      const relayState = urlParams.get('RelayState');
      
      if (samlResponse) {
        const auth = await samlService.handleSAMLResponse(samlResponse, relayState || undefined);
        onLogin(auth);
      } else {
        setError('SAML認証レスポンスが見つかりません。');
      }
    } catch (error) {
      console.error('SAML callback error:', error);
      setError('SAML認証の処理に失敗しました。');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ 
      display: 'flex', 
      justifyContent: 'center', 
      alignItems: 'center', 
      minHeight: '100vh',
      background: 'linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%)'
    }}>
      <Card 
        title={
          <div style={{ textAlign: 'center' }}>
            <h2 style={{ color: '#c72439', margin: 0 }}>NemakiWare</h2>
            <p style={{ color: '#666', margin: '8px 0 0 0' }}>CMIS Document Management System</p>
          </div>
        }
        style={{ width: 400, boxShadow: '0 4px 12px rgba(0,0,0,0.1)' }}
      >
        {error && (
          <Alert
            message={error}
            type="error"
            style={{ marginBottom: 16 }}
            closable
            onClose={() => setError(null)}
          />
        )}
        
        <Form
          form={form}
          onFinish={handleSubmit}
          layout="vertical"
          initialValues={{ repositoryId: repositories.length === 1 ? repositories[0] : undefined }}
        >
          <Form.Item
            name="repositoryId"
            label="リポジトリ"
            rules={[{ required: true, message: 'リポジトリを選択してください' }]}
          >
            <Select
              prefix={<DatabaseOutlined />}
              placeholder="リポジトリを選択"
              options={repositories.map(repo => ({ label: repo, value: repo }))}
            />
          </Form.Item>

          <Form.Item
            name="username"
            label="ユーザー名"
            rules={[{ required: true, message: 'ユーザー名を入力してください' }]}
          >
            <Input
              prefix={<UserOutlined />}
              placeholder="ユーザー名"
              autoComplete="username"
            />
          </Form.Item>

          <Form.Item
            name="password"
            label="パスワード"
            rules={[{ required: true, message: 'パスワードを入力してください' }]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="パスワード"
              autoComplete="current-password"
            />
          </Form.Item>

          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              loading={loading}
              style={{ width: '100%', height: 40 }}
            >
              ログイン
            </Button>
          </Form.Item>

          {(isOIDCEnabled() || isSAMLEnabled()) && (
            <>
              <Divider>または</Divider>
              {isOIDCEnabled() && (
                <Form.Item>
                  <Button
                    type="default"
                    icon={<LoginOutlined />}
                    onClick={handleOIDCLogin}
                    loading={loading}
                    style={{ width: '100%', height: 40, marginBottom: 8 }}
                  >
                    OIDC認証でログイン
                  </Button>
                </Form.Item>
              )}
              {isSAMLEnabled() && (
                <Form.Item>
                  <Button
                    type="default"
                    icon={<LoginOutlined />}
                    onClick={handleSAMLLogin}
                    loading={loading}
                    style={{ width: '100%', height: 40 }}
                  >
                    SAML認証でログイン
                  </Button>
                </Form.Item>
              )}
            </>
          )}
        </Form>
      </Card>
    </div>
  );
};

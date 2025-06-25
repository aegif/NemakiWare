import React, { useState } from 'react';
import { Form, Input, Button, Card, Alert, Select } from 'antd';
import { UserOutlined, LockOutlined, DatabaseOutlined } from '@ant-design/icons';
import { AuthService, AuthToken } from '../../services/auth';
import { CMISService } from '../../services/cmis';

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

  React.useEffect(() => {
    loadRepositories();
  }, []);

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
        </Form>
      </Card>
    </div>
  );
};

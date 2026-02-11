import React, { useState, useEffect } from 'react';
import { Card, Form, Input, Button, message, Alert, Spin, Tabs, Descriptions, Tag, Tooltip } from 'antd';
import { UserOutlined, LockOutlined, KeyOutlined, CloudOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { CMISService } from '../../services/cmis';
import PasskeyManagement from '../PasskeyManagement/PasskeyManagement';

interface AccountSettingsProps {
  repositoryId: string;
}

interface MeInfo {
  userId: string;
  userName: string;
  isAdmin: boolean;
  firstName?: string;
  lastName?: string;
  email?: string;
  allowedAuthMethods?: string | null;
}

/**
 * Check if password authentication is allowed for a user based on allowedAuthMethods.
 * null/empty = all methods allowed (backward compatibility).
 */
function isPasswordAuthAllowed(allowedAuthMethods: string | null | undefined): boolean {
  if (!allowedAuthMethods || allowedAuthMethods === '' || allowedAuthMethods === 'null') {
    return true;
  }
  if (allowedAuthMethods === 'disabled') {
    return false;
  }
  return allowedAuthMethods.split(',').map(m => m.trim()).includes('password');
}

export const AccountSettings: React.FC<AccountSettingsProps> = ({ repositoryId }) => {
  const { authToken, handleAuthError } = useAuth();
  const { t } = useTranslation();
  const [passwordForm] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const cmisService = new CMISService(handleAuthError);

  const [meInfo, setMeInfo] = useState<MeInfo | null>(null);
  const [meLoading, setMeLoading] = useState(true);

  useEffect(() => {
    const fetchMe = async () => {
      try {
        const info = await cmisService.getCurrentUser(repositoryId);
        setMeInfo({
          ...info,
          allowedAuthMethods: info.allowedAuthMethods ?? null,
        });
      } catch (e) {
        // Fallback to authToken info
        if (authToken) {
          setMeInfo({
            userId: authToken.username,
            userName: authToken.username,
            isAdmin: authToken.isAdmin ?? false,
            allowedAuthMethods: authToken.allowedAuthMethods ?? null,
          });
        }
      } finally {
        setMeLoading(false);
      }
    };
    fetchMe();
  }, [repositoryId]);

  const passwordAllowed = meInfo ? isPasswordAuthAllowed(meInfo.allowedAuthMethods) : true;

  const handlePasswordChange = async (values: {
    oldPassword: string;
    newPassword: string;
    confirmPassword: string;
  }) => {
    if (values.newPassword !== values.confirmPassword) {
      message.error(t('accountSettings.passwordMismatch'));
      return;
    }

    const userId = meInfo?.userId || authToken?.username;
    if (!userId) return;

    setLoading(true);
    try {
      await cmisService.changePassword(repositoryId, userId, values.oldPassword, values.newPassword);
      message.success(t('accountSettings.passwordChanged'));
      passwordForm.resetFields();
    } catch (error: any) {
      message.error(error.message || t('accountSettings.passwordChangeError'));
    } finally {
      setLoading(false);
    }
  };

  if (meLoading) {
    return (
      <div>
        <h2><UserOutlined /> {t('accountSettings.title')}</h2>
        <Card style={{ maxWidth: 700 }}>
          <Spin />
        </Card>
      </div>
    );
  }

  const authMethodLabel = (method: string | null | undefined): string => {
    if (!method || method === '' || method === 'null') return t('accountSettings.profile.authMethodAll');
    if (method === 'disabled') return t('accountSettings.profile.authMethodDisabled');
    return method.split(',').map(m => {
      const trimmed = m.trim();
      if (trimmed === 'password') return t('accountSettings.profile.authMethodPassword');
      if (trimmed === 'cloud') return t('accountSettings.profile.authMethodCloud');
      return trimmed;
    }).join(', ');
  };

  const profileTab = (
    <Descriptions column={1} bordered size="small">
      <Descriptions.Item label={t('accountSettings.profile.userId')}>
        {meInfo?.userId}
      </Descriptions.Item>
      <Descriptions.Item label={t('accountSettings.profile.userName')}>
        {meInfo?.userName || '-'}
      </Descriptions.Item>
      {meInfo?.firstName && (
        <Descriptions.Item label={t('accountSettings.profile.firstName')}>
          {meInfo.firstName}
        </Descriptions.Item>
      )}
      {meInfo?.lastName && (
        <Descriptions.Item label={t('accountSettings.profile.lastName')}>
          {meInfo.lastName}
        </Descriptions.Item>
      )}
      {meInfo?.email && (
        <Descriptions.Item label={t('accountSettings.profile.email')}>
          {meInfo.email}
        </Descriptions.Item>
      )}
      <Descriptions.Item label={t('accountSettings.profile.role')}>
        {meInfo?.isAdmin
          ? <Tag color="red">{t('accountSettings.profile.admin')}</Tag>
          : <Tag>{t('accountSettings.profile.user')}</Tag>
        }
      </Descriptions.Item>
      <Descriptions.Item label={t('accountSettings.profile.authMethods')}>
        {authMethodLabel(meInfo?.allowedAuthMethods)}
      </Descriptions.Item>
    </Descriptions>
  );

  const passwordTab = passwordAllowed ? (
    <Form
      form={passwordForm}
      onFinish={handlePasswordChange}
      layout="vertical"
      style={{ maxWidth: 400 }}
    >
      <Form.Item
        name="oldPassword"
        label={t('accountSettings.currentPassword')}
        rules={[{ required: true, message: t('accountSettings.currentPasswordRequired') }]}
      >
        <Input.Password
          prefix={<LockOutlined />}
          placeholder={t('accountSettings.currentPasswordPlaceholder')}
        />
      </Form.Item>

      <Form.Item
        name="newPassword"
        label={t('accountSettings.newPassword')}
        rules={[
          { required: true, message: t('accountSettings.newPasswordRequired') },
          { min: 8, message: t('accountSettings.passwordMinLength') }
        ]}
      >
        <Input.Password
          prefix={<LockOutlined />}
          placeholder={t('accountSettings.newPasswordPlaceholder')}
        />
      </Form.Item>

      <Form.Item
        name="confirmPassword"
        label={t('accountSettings.confirmPassword')}
        rules={[
          { required: true, message: t('accountSettings.confirmPasswordRequired') },
        ]}
      >
        <Input.Password
          prefix={<LockOutlined />}
          placeholder={t('accountSettings.confirmPasswordPlaceholder')}
        />
      </Form.Item>

      <Form.Item>
        <Button type="primary" htmlType="submit" loading={loading}>
          {t('accountSettings.changePasswordButton')}
        </Button>
      </Form.Item>
    </Form>
  ) : (
    <Alert
      message={t('accountSettings.cloudOnlyAccount')}
      type="info"
      showIcon
      icon={<CloudOutlined />}
    />
  );

  const passkeyTab = passwordAllowed ? (
    <PasskeyManagement repositoryId={repositoryId} />
  ) : (
    <Alert
      message={t('accountSettings.cloudOnlyAccount')}
      type="info"
      showIcon
      icon={<CloudOutlined />}
    />
  );

  const disabledSuffix = !passwordAllowed ? ` (${t('accountSettings.tabDisabled')})` : '';

  const tabItems = [
    {
      key: 'profile',
      label: <span><UserOutlined /> {t('accountSettings.tabs.profile')}</span>,
      children: profileTab,
    },
    {
      key: 'password',
      label: (
        <Tooltip title={!passwordAllowed ? t('accountSettings.cloudOnlyAccount') : undefined}>
          <span style={!passwordAllowed ? { color: 'rgba(0,0,0,0.25)' } : undefined}>
            <LockOutlined /> {t('accountSettings.tabs.password')}{disabledSuffix}
          </span>
        </Tooltip>
      ),
      children: passwordTab,
      disabled: !passwordAllowed,
    },
    {
      key: 'passkey',
      label: (
        <Tooltip title={!passwordAllowed ? t('accountSettings.cloudOnlyAccount') : undefined}>
          <span style={!passwordAllowed ? { color: 'rgba(0,0,0,0.25)' } : undefined}>
            <KeyOutlined /> {t('accountSettings.tabs.passkey')}{disabledSuffix}
          </span>
        </Tooltip>
      ),
      children: passkeyTab,
      disabled: !passwordAllowed,
    },
  ];

  return (
    <div>
      <h2><UserOutlined /> {t('accountSettings.title')}</h2>

      <Card style={{ maxWidth: 700 }}>
        <Tabs defaultActiveKey="profile" items={tabItems} />
      </Card>
    </div>
  );
};

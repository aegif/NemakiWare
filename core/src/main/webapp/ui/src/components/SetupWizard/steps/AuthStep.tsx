import { useState, useEffect } from 'react';
import { Form, Switch, Input, Button, Alert, Space, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { setupApi } from '../../../services/setupApi';
import type { AuthConfig } from '../../../services/setupApi';

export type { AuthConfig };

interface AuthStepProps {
  value: AuthConfig;
  onChange: (config: AuthConfig) => void;
  onValidChange: (valid: boolean) => void;
}

export function AuthStep({ value, onChange, onValidChange }: AuthStepProps) {
  const { t } = useTranslation();
  const [googleOidcTest, setGoogleOidcTest] = useState<{ testing: boolean; reachable?: boolean; error?: string; clientIdValid?: boolean; clientIdError?: string }>({ testing: false });
  const [microsoftOidcTest, setMicrosoftOidcTest] = useState<{ testing: boolean; reachable?: boolean; error?: string; clientIdValid?: boolean; clientIdError?: string }>({ testing: false });

  const validateAuth = (config: AuthConfig) => {
    const anyEnabled = config.passwordEnabled || config.googleEnabled || config.microsoftEnabled;
    const googleValid = !config.googleEnabled || !!config.googleClientId;
    const microsoftValid = !config.microsoftEnabled || !!config.microsoftClientId;
    return anyEnabled && googleValid && microsoftValid;
  };

  // Report initial validity on mount (passwordEnabled defaults to true → valid)
  useEffect(() => {
    onValidChange(validateAuth(value));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleChange = (field: keyof AuthConfig, val: boolean | string) => {
    const updated = { ...value, [field]: val };
    onChange(updated);
    onValidChange(validateAuth(updated));
  };

  const testGoogleOidc = async () => {
    setGoogleOidcTest({ testing: true });
    try {
      const result = await setupApi.testOidc({
        issuerUrl: 'https://accounts.google.com',
        clientId: value.googleClientId,
      });
      setGoogleOidcTest({
        testing: false,
        reachable: result.reachable,
        error: result.error,
        clientIdValid: result.clientIdValid,
        clientIdError: result.clientIdError,
      });
    } catch (e) {
      setGoogleOidcTest({ testing: false, reachable: false, error: e instanceof Error ? e.message : String(e) });
    }
  };

  const testMicrosoftOidc = async () => {
    const tenantId = value.microsoftTenantId || 'common';
    setMicrosoftOidcTest({ testing: true });
    try {
      const result = await setupApi.testOidc({
        issuerUrl: `https://login.microsoftonline.com/${tenantId}/v2.0`,
        clientId: value.microsoftClientId,
      });
      setMicrosoftOidcTest({
        testing: false,
        reachable: result.reachable,
        error: result.error,
        clientIdValid: result.clientIdValid,
        clientIdError: result.clientIdError,
      });
    } catch (e) {
      setMicrosoftOidcTest({ testing: false, reachable: false, error: e instanceof Error ? e.message : String(e) });
    }
  };

  const noneEnabled = !value.passwordEnabled && !value.googleEnabled && !value.microsoftEnabled;

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {noneEnabled && (
        <Alert
          type="error"
          showIcon
          message={t('setup.auth.lockoutWarning')}
        />
      )}

      <Form layout="vertical">
        <Form.Item label={t('setup.auth.password')}>
          <Switch
            checked={value.passwordEnabled}
            onChange={(v) => handleChange('passwordEnabled', v)}
          />
        </Form.Item>

        <Form.Item label={t('setup.auth.google')}>
          <Switch
            checked={value.googleEnabled}
            onChange={(v) => handleChange('googleEnabled', v)}
          />
        </Form.Item>
        {value.googleEnabled && (
          <Space direction="vertical" style={{ width: '100%', paddingLeft: 24 }}>
            <Form.Item label={t('setup.auth.clientId')}>
              <Input
                value={value.googleClientId}
                onChange={(e) => handleChange('googleClientId', e.target.value)}
                placeholder="xxxxx.apps.googleusercontent.com"
              />
            </Form.Item>
            <Button size="small" onClick={testGoogleOidc} loading={googleOidcTest.testing} disabled={!value.googleClientId}>
              {t('setup.auth.testOidc')}
            </Button>
            {googleOidcTest.reachable !== undefined && (
              <Space direction="vertical" size="small">
                <Space>
                  <Tag color={googleOidcTest.reachable ? 'green' : 'red'}>
                    {googleOidcTest.reachable ? t('setup.auth.oidcReachable') : t('setup.auth.oidcUnreachable')}
                  </Tag>
                  {googleOidcTest.reachable && googleOidcTest.clientIdValid === true && (
                    <Tag color="green">{t('setup.auth.clientIdValid')}</Tag>
                  )}
                  {googleOidcTest.reachable && googleOidcTest.clientIdValid === false && (
                    <Tag color="red">{t('setup.auth.clientIdInvalid')}</Tag>
                  )}
                </Space>
                {!googleOidcTest.reachable && googleOidcTest.error && (
                  <Alert type="warning" message={googleOidcTest.error} showIcon />
                )}
                {googleOidcTest.reachable && googleOidcTest.clientIdError && (
                  <Alert type="info" message={googleOidcTest.clientIdError} showIcon />
                )}
              </Space>
            )}
          </Space>
        )}

        <Form.Item label={t('setup.auth.microsoft')}>
          <Switch
            checked={value.microsoftEnabled}
            onChange={(v) => handleChange('microsoftEnabled', v)}
          />
        </Form.Item>
        {value.microsoftEnabled && (
          <Space direction="vertical" style={{ width: '100%', paddingLeft: 24 }}>
            <Form.Item label={t('setup.auth.clientId')}>
              <Input
                value={value.microsoftClientId}
                onChange={(e) => handleChange('microsoftClientId', e.target.value)}
              />
            </Form.Item>
            <Form.Item label={t('setup.auth.tenantId')}>
              <Input
                value={value.microsoftTenantId}
                onChange={(e) => handleChange('microsoftTenantId', e.target.value)}
                placeholder="common"
              />
            </Form.Item>
            <Button size="small" onClick={testMicrosoftOidc} loading={microsoftOidcTest.testing} disabled={!value.microsoftClientId}>
              {t('setup.auth.testOidc')}
            </Button>
            {microsoftOidcTest.reachable !== undefined && (
              <Space direction="vertical" size="small">
                <Space>
                  <Tag color={microsoftOidcTest.reachable ? 'green' : 'red'}>
                    {microsoftOidcTest.reachable ? t('setup.auth.oidcReachable') : t('setup.auth.oidcUnreachable')}
                  </Tag>
                  {microsoftOidcTest.reachable && microsoftOidcTest.clientIdValid === true && (
                    <Tag color="green">{t('setup.auth.clientIdValid')}</Tag>
                  )}
                  {microsoftOidcTest.reachable && microsoftOidcTest.clientIdValid === false && (
                    <Tag color="red">{t('setup.auth.clientIdInvalid')}</Tag>
                  )}
                </Space>
                {!microsoftOidcTest.reachable && microsoftOidcTest.error && (
                  <Alert type="warning" message={microsoftOidcTest.error} showIcon />
                )}
                {microsoftOidcTest.reachable && microsoftOidcTest.clientIdError && (
                  <Alert type="info" message={microsoftOidcTest.clientIdError} showIcon />
                )}
              </Space>
            )}
          </Space>
        )}
      </Form>
    </Space>
  );
}

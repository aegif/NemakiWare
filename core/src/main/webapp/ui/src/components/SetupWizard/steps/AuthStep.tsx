import { useState, useEffect, useCallback } from 'react';
import { Form, Switch, Input, Button, Alert, Space, Tag, Checkbox } from 'antd';
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
  const [googleOidcTest, setGoogleOidcTest] = useState<{ testing: boolean; reachable?: boolean; error?: string; clientIdValid?: boolean; clientIdIndeterminate?: boolean; clientIdError?: string }>({ testing: false });
  const [microsoftOidcTest, setMicrosoftOidcTest] = useState<{ testing: boolean; reachable?: boolean; error?: string; clientIdValid?: boolean; clientIdIndeterminate?: boolean; clientIdError?: string }>({ testing: false });
  const [microsoftClientIdAcknowledged, setMicrosoftClientIdAcknowledged] = useState(false);

  const validateAuth = useCallback((config: AuthConfig) => {
    const anyEnabled = config.passwordEnabled || config.googleEnabled || config.microsoftEnabled;
    const googleConfigured = !config.googleEnabled || !!config.googleClientId?.trim();
    const microsoftConfigured = !config.microsoftEnabled || !!config.microsoftClientId?.trim();

    // Require OIDC test pass when provider auth is enabled.
    // Google: requires explicit clientIdValid===true.
    // Microsoft: provider cannot pre-validate clientId (always returns indeterminate).
    //   If clientIdValid===true  → pass (explicit validation by provider).
    //   If clientIdValid===false → fail (explicitly rejected).
    //   Otherwise (indeterminate) → require user acknowledgement checkbox.
    // Server-side /auth/apply enforces clientId non-empty as additional safeguard.
    const googleTestPassed =
      !config.googleEnabled ||
      (googleOidcTest.reachable === true && googleOidcTest.clientIdValid === true);

    let microsoftTestPassed: boolean;
    if (!config.microsoftEnabled) {
      microsoftTestPassed = true;
    } else if (microsoftOidcTest.reachable === true && microsoftOidcTest.clientIdValid === true) {
      microsoftTestPassed = true;
    } else if (microsoftOidcTest.reachable === true && microsoftOidcTest.clientIdValid === false) {
      microsoftTestPassed = false;
    } else if (microsoftOidcTest.reachable === true && microsoftOidcTest.clientIdIndeterminate === true) {
      // Provider cannot pre-validate: require explicit user acknowledgement
      microsoftTestPassed = microsoftClientIdAcknowledged;
    } else {
      microsoftTestPassed = false;
    }

    return anyEnabled && googleConfigured && microsoftConfigured && googleTestPassed && microsoftTestPassed;
  }, [googleOidcTest, microsoftOidcTest, microsoftClientIdAcknowledged]);

  // Recompute step validity whenever config/test result changes.
  useEffect(() => {
    onValidChange(validateAuth(value));
  }, [value, validateAuth, onValidChange]);

  const handleChange = (field: keyof AuthConfig, val: boolean | string) => {
    const updated = { ...value, [field]: val };

    // Input change invalidates previous OIDC test result for that provider.
    if (field === 'googleEnabled' || field === 'googleClientId') {
      setGoogleOidcTest({ testing: false });
    }
    if (field === 'microsoftEnabled' || field === 'microsoftClientId' || field === 'microsoftTenantId') {
      setMicrosoftOidcTest({ testing: false });
      setMicrosoftClientIdAcknowledged(false);
    }

    onChange(updated);
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
        clientIdIndeterminate: result.clientIdIndeterminate,
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
        clientIdIndeterminate: result.clientIdIndeterminate,
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
                {microsoftOidcTest.reachable && microsoftOidcTest.clientIdError && !microsoftOidcTest.clientIdIndeterminate && (
                  <Alert type="error" message={microsoftOidcTest.clientIdError} showIcon />
                )}
                {microsoftOidcTest.reachable && microsoftOidcTest.clientIdIndeterminate && (
                  <>
                    <Alert type="warning" message={microsoftOidcTest.clientIdError} showIcon />
                    <Checkbox
                      checked={microsoftClientIdAcknowledged}
                      onChange={(e) => setMicrosoftClientIdAcknowledged(e.target.checked)}
                    >
                      {t('setup.auth.microsoftClientIdAcknowledge')}
                    </Checkbox>
                  </>
                )}
              </Space>
            )}
          </Space>
        )}
      </Form>
    </Space>
  );
}

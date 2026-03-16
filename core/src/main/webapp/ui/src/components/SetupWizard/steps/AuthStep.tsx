import { useState, useEffect, useCallback } from 'react';
import { Form, Switch, Input, Button, Alert, Space, Tag, Checkbox } from 'antd';
import { useTranslation } from 'react-i18next';
import { setupApi } from '../../../services/setupApi';
import type { AuthConfig, SamlCertTestResult } from '../../../services/setupApi';

export type { AuthConfig };

/** Docker default Keycloak values */
const KEYCLOAK_DEFAULTS = {
  issuerUrl: 'http://keycloak:8080/realms/nemakiware',
  clientId: 'nemakiware-ui',
};

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
  const [keycloakOidcTest, setKeycloakOidcTest] = useState<{ testing: boolean; reachable?: boolean; error?: string }>({ testing: false });
  const [samlCertTest, setSamlCertTest] = useState<{ testing: boolean; result?: SamlCertTestResult }>({ testing: false });

  const validateAuth = useCallback((config: AuthConfig) => {
    const anyEnabled = config.passwordEnabled || config.googleEnabled || config.microsoftEnabled || config.keycloakOidcEnabled || config.samlEnabled;
    const googleConfigured = !config.googleEnabled || !!config.googleClientId?.trim();
    const microsoftConfigured = !config.microsoftEnabled || !!config.microsoftClientId?.trim();
    const keycloakConfigured = !config.keycloakOidcEnabled || (!!config.keycloakIssuerUrl?.trim() && !!config.keycloakClientId?.trim());

    const samlConfigured = !config.samlEnabled || (!!config.samlIdpSsoUrl?.trim() && !!config.samlIdpCertificate?.trim());
    const samlTestPassed = !config.samlEnabled || samlCertTest.result?.valid === true;

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
      microsoftTestPassed = microsoftClientIdAcknowledged;
    } else {
      microsoftTestPassed = false;
    }

    // Keycloak OIDC: require successful discovery test
    const keycloakTestPassed = !config.keycloakOidcEnabled || keycloakOidcTest.reachable === true;

    return anyEnabled && googleConfigured && microsoftConfigured && keycloakConfigured && samlConfigured && samlTestPassed && googleTestPassed && microsoftTestPassed && keycloakTestPassed;
  }, [googleOidcTest, microsoftOidcTest, microsoftClientIdAcknowledged, keycloakOidcTest, samlCertTest]);

  useEffect(() => {
    onValidChange(validateAuth(value));
  }, [value, validateAuth, onValidChange]);

  const handleChange = (field: keyof AuthConfig, val: boolean | string) => {
    const updated = { ...value, [field]: val };

    if (field === 'googleEnabled' || field === 'googleClientId') {
      setGoogleOidcTest({ testing: false });
    }
    if (field === 'microsoftEnabled' || field === 'microsoftClientId' || field === 'microsoftTenantId') {
      setMicrosoftOidcTest({ testing: false });
      setMicrosoftClientIdAcknowledged(false);
    }
    if (field === 'keycloakOidcEnabled' || field === 'keycloakIssuerUrl' || field === 'keycloakClientId') {
      setKeycloakOidcTest({ testing: false });
    }
    if (field === 'samlEnabled' || field === 'samlIdpCertificate') {
      setSamlCertTest({ testing: false });
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

  const testKeycloakOidc = async () => {
    setKeycloakOidcTest({ testing: true });
    try {
      const result = await setupApi.testOidc({
        issuerUrl: value.keycloakIssuerUrl || '',
      });
      setKeycloakOidcTest({
        testing: false,
        reachable: result.reachable,
        error: result.error,
      });
    } catch (e) {
      setKeycloakOidcTest({ testing: false, reachable: false, error: e instanceof Error ? e.message : String(e) });
    }
  };

  const fillKeycloakDefaults = () => {
    const updated = {
      ...value,
      keycloakIssuerUrl: KEYCLOAK_DEFAULTS.issuerUrl,
      keycloakClientId: KEYCLOAK_DEFAULTS.clientId,
    };
    setKeycloakOidcTest({ testing: false });
    onChange(updated);
  };

  const testSamlCertificate = async () => {
    setSamlCertTest({ testing: true });
    try {
      const result = await setupApi.testSamlCertificate(value.samlIdpCertificate || '');
      setSamlCertTest({ testing: false, result });
    } catch (e) {
      setSamlCertTest({ testing: false, result: { valid: false, error: e instanceof Error ? e.message : String(e) } });
    }
  };

  const noneEnabled = !value.passwordEnabled && !value.googleEnabled && !value.microsoftEnabled && !value.keycloakOidcEnabled && !value.samlEnabled;

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

        <Form.Item label={t('setup.auth.keycloakOidc')}>
          <Switch
            checked={value.keycloakOidcEnabled}
            onChange={(v) => handleChange('keycloakOidcEnabled', v)}
          />
        </Form.Item>
        {value.keycloakOidcEnabled && (
          <Space direction="vertical" style={{ width: '100%', paddingLeft: 24 }}>
            <Button size="small" type="dashed" onClick={fillKeycloakDefaults}>
              {t('setup.auth.keycloakDefaults')}
            </Button>
            <Form.Item label={t('setup.auth.issuerUrl')}>
              <Input
                value={value.keycloakIssuerUrl}
                onChange={(e) => handleChange('keycloakIssuerUrl', e.target.value)}
                placeholder={KEYCLOAK_DEFAULTS.issuerUrl}
              />
            </Form.Item>
            <Form.Item label={t('setup.auth.keycloakClientId')}>
              <Input
                value={value.keycloakClientId}
                onChange={(e) => handleChange('keycloakClientId', e.target.value)}
                placeholder={KEYCLOAK_DEFAULTS.clientId}
              />
            </Form.Item>
            <Button size="small" onClick={testKeycloakOidc} loading={keycloakOidcTest.testing} disabled={!value.keycloakIssuerUrl}>
              {t('setup.auth.testOidc')}
            </Button>
            {keycloakOidcTest.reachable !== undefined && (
              <Tag color={keycloakOidcTest.reachable ? 'green' : 'red'}>
                {keycloakOidcTest.reachable ? t('setup.auth.oidcReachable') : t('setup.auth.oidcUnreachable')}
              </Tag>
            )}
            {!keycloakOidcTest.reachable && keycloakOidcTest.error && (
              <Alert type="warning" message={keycloakOidcTest.error} showIcon />
            )}
          </Space>
        )}

        <Form.Item label={t('setup.auth.saml')}>
          <Switch
            checked={value.samlEnabled}
            onChange={(v) => handleChange('samlEnabled', v)}
          />
        </Form.Item>
        {value.samlEnabled && (
          <Space direction="vertical" style={{ width: '100%', paddingLeft: 24 }}>
            <Alert type="info" message={t('setup.auth.samlNote')} showIcon />
            <Form.Item label={t('setup.auth.samlIdpSsoUrl')} required>
              <Input
                value={value.samlIdpSsoUrl}
                onChange={(e) => handleChange('samlIdpSsoUrl', e.target.value)}
                placeholder="https://idp.example.com/sso/saml"
              />
            </Form.Item>
            <Form.Item label={t('setup.auth.samlSpEntityId')}>
              <Input
                value={value.samlSpEntityId}
                onChange={(e) => handleChange('samlSpEntityId', e.target.value)}
                placeholder="nemakiware-sp"
              />
            </Form.Item>
            <Form.Item label={t('setup.auth.samlIdpCertificate')} required>
              <Input.TextArea
                value={value.samlIdpCertificate}
                onChange={(e) => handleChange('samlIdpCertificate', e.target.value)}
                placeholder="-----BEGIN CERTIFICATE-----&#10;...&#10;-----END CERTIFICATE-----"
                rows={6}
                style={{ fontFamily: 'monospace', fontSize: 12 }}
              />
            </Form.Item>
            <Button
              size="small"
              onClick={testSamlCertificate}
              loading={samlCertTest.testing}
              disabled={!value.samlIdpCertificate?.trim()}
            >
              {t('setup.auth.testSamlCertificate')}
            </Button>
            {samlCertTest.result && (
              <Tag color={samlCertTest.result.valid ? 'green' : 'red'}>
                {samlCertTest.result.valid
                  ? t('setup.auth.samlCertValid', { subject: samlCertTest.result.subject })
                  : t('setup.auth.samlCertInvalid')}
              </Tag>
            )}
            {samlCertTest.result && !samlCertTest.result.valid && samlCertTest.result.error && (
              <Alert type="error" message={samlCertTest.result.error} showIcon />
            )}
            {samlCertTest.result?.warning && (
              <Alert type="warning" message={samlCertTest.result.warning} showIcon />
            )}
            <Form.Item label={t('setup.auth.samlSloUrl')}>
              <Input
                value={value.samlSloUrl}
                onChange={(e) => handleChange('samlSloUrl', e.target.value)}
                placeholder="https://idp.example.com/slo"
              />
            </Form.Item>
            <Form.Item label={t('setup.auth.samlAttributeMapping')}>
              <Input
                value={value.samlAttributeMapping}
                onChange={(e) => handleChange('samlAttributeMapping', e.target.value)}
                placeholder="NameID"
              />
            </Form.Item>
          </Space>
        )}

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

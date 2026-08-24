import { useState, useCallback, useMemo, useEffect, useRef } from 'react';
import { Steps, Button, Card, Typography, Space, Spin, Alert } from 'antd';
import { useTranslation } from 'react-i18next';
import { TokenEntry } from './TokenEntry';
import { CouchDbStep, CouchDbConfig } from './steps/CouchDbStep';
import { ProbeStep } from './steps/ProbeStep';
import { AuthStep, AuthConfig } from './steps/AuthStep';
import { AdminStep, AdminConfig } from './steps/AdminStep';
import { VectorStep, VectorConfig } from './steps/VectorStep';
import { ProvenanceStep, ProvenanceConfig } from './steps/ProvenanceStep';
import { ConfirmStep } from './steps/ConfirmStep';
import { setupApi } from '../../services/setupApi';

const { Title } = Typography;

interface SetupWizardProps {
  onComplete: () => void;
}

interface WizardState {
  couchdb: CouchDbConfig;
  auth: AuthConfig;
  admin: AdminConfig;
  vector: VectorConfig;
  provenance: ProvenanceConfig;
}

const initialState: WizardState = {
  couchdb: { url: 'http://couchdb:5984', username: 'admin', password: '' },
  auth: {
    passwordEnabled: true,
    googleEnabled: false,
    microsoftEnabled: false,
    googleClientId: '',
    microsoftClientId: '',
    microsoftTenantId: '',
    keycloakOidcEnabled: false,
    samlEnabled: false,
    keycloakIssuerUrl: '',
    keycloakClientId: '',
    samlIdpSsoUrl: '',
    samlSpEntityId: 'nemakiware-sp',
    samlIdpCertificate: '',
    samlSloUrl: '',
    samlAttributeMapping: '',
  },
  admin: { newPassword: '', confirmPassword: '' },
  vector: { type: 'none', url: '', region: '', modelId: '', accessKeyId: '', secretAccessKey: '' },
  // Recommended ON (roadmap §2-3): provenance cannot be reconstructed after the fact, so the
  // default has to be the one that can be undone. The SHIPPED fallback for deployments that
  // never run the wizard is unchanged.
  provenance: { journaled: true },
};

export function SetupWizard({ onComplete }: SetupWizardProps) {
  const { t } = useTranslation();
  const [tokenVerified, setTokenVerified] = useState(false);
  const [currentStep, setCurrentStep] = useState(0);
  const [state, setState] = useState<WizardState>(initialState);
  const [stepValid, setStepValid] = useState<Record<number, boolean>>({
    0: false, 1: false, 2: false, 3: false, 4: false,
  });

  // Hydration state: blocks wizard UI until existing config is loaded.
  // Prevents overwriting existing SSO/vector configuration with defaults
  // and prevents late hydration from reverting operator's in-progress edits.
  const [hydrationStatus, setHydrationStatus] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const hydrationAttemptRef = useRef(0);

  const handleTokenVerified = () => {
    setTokenVerified(true);
  };

  // Hydrate existing auth/vector settings when the wizard opens.
  const runHydration = useCallback(async () => {
    const attemptId = ++hydrationAttemptRef.current;
    setHydrationStatus('loading');

    try {
      const [authState, vectorState] = await Promise.all([
        setupApi.getAuthState(),
        setupApi.getVectorState(),
      ]);

      if (attemptId !== hydrationAttemptRef.current) return;

      setState(prev => {
        const next = { ...prev };
        next.auth = {
          passwordEnabled: authState.passwordEnabled ?? prev.auth.passwordEnabled,
          googleEnabled: authState.googleEnabled ?? prev.auth.googleEnabled,
          microsoftEnabled: authState.microsoftEnabled ?? prev.auth.microsoftEnabled,
          googleClientId: authState.googleClientId ?? prev.auth.googleClientId,
          microsoftClientId: authState.microsoftClientId ?? prev.auth.microsoftClientId,
          microsoftTenantId: authState.microsoftTenantId ?? prev.auth.microsoftTenantId,
          keycloakOidcEnabled: authState.keycloakOidcEnabled ?? prev.auth.keycloakOidcEnabled,
          samlEnabled: authState.samlEnabled ?? prev.auth.samlEnabled,
          keycloakIssuerUrl: authState.keycloakIssuerUrl ?? prev.auth.keycloakIssuerUrl,
          keycloakClientId: authState.keycloakClientId ?? prev.auth.keycloakClientId,
          samlIdpSsoUrl: authState.samlIdpSsoUrl ?? prev.auth.samlIdpSsoUrl,
          samlSpEntityId: authState.samlSpEntityId ?? prev.auth.samlSpEntityId,
          samlIdpCertificate: authState.samlIdpCertificate ?? prev.auth.samlIdpCertificate,
          samlSloUrl: authState.samlSloUrl ?? prev.auth.samlSloUrl,
          samlAttributeMapping: authState.samlAttributeMapping ?? prev.auth.samlAttributeMapping,
        };
        const validVectorTypes = ['none', 'tei', 'bedrock'] as const;
        const vectorType = validVectorTypes.includes(vectorState.type as any)
          ? (vectorState.type as VectorConfig['type'])
          : prev.vector.type;
        next.vector = {
          ...prev.vector,
          type: vectorType,
          url: vectorState.url ?? prev.vector.url,
          region: vectorState.region ?? prev.vector.region,
          modelId: vectorState.modelId ?? prev.vector.modelId,
        };
        return next;
      });
      setHydrationStatus('ready');
    } catch (err) {
      if (attemptId !== hydrationAttemptRef.current) return;
      console.error('Setup wizard hydration failed:', err);
      setHydrationStatus('error');
    }
  }, []);

  useEffect(() => {
    if (tokenVerified && hydrationStatus === 'idle') {
      runHydration();
    }
  }, [tokenVerified, hydrationStatus, runHydration]);

  const handleStepValid = useCallback((step: number) => (valid: boolean) => {
    setStepValid(prev => ({ ...prev, [step]: valid }));
  }, []);

  // Memoize per-step callbacks so that child components receive stable references.
  // Without this, handleStepValid(N) creates a new function on every render,
  // which causes useEffect dependency loops in ProbeStep.
  const onValidChange0 = useMemo(() => handleStepValid(0), [handleStepValid]);
  const onValidChange1 = useMemo(() => handleStepValid(1), [handleStepValid]);
  const onValidChange2 = useMemo(() => handleStepValid(2), [handleStepValid]);
  const onValidChange3 = useMemo(() => handleStepValid(3), [handleStepValid]);
  const onValidChange4 = useMemo(() => handleStepValid(4), [handleStepValid]);

  if (!tokenVerified) {
    return <TokenEntry onTokenVerified={handleTokenVerified} />;
  }

  // Block wizard UI until hydration completes to prevent:
  // - submitting hardcoded defaults that overwrite existing config (P2-1)
  // - late hydration reverting operator's in-progress edits (P2-2)
  if (hydrationStatus !== 'ready') {
    return (
      <div style={{ minHeight: '100vh', background: '#f5f5f5', padding: '24px 0' }}>
        <div style={{ maxWidth: 800, margin: '0 auto', padding: '0 24px' }}>
          <Space direction="vertical" size="large" style={{ width: '100%', textAlign: 'center' }}>
            <Title level={2} style={{ margin: 0 }}>{t('setup.title')}</Title>
            {hydrationStatus === 'error' ? (
              <Card>
                <Alert
                  type="error"
                  message={t('setup.hydration.errorTitle')}
                  description={t('setup.hydration.errorDescription')}
                  style={{ marginBottom: 16 }}
                />
                <Button type="primary" onClick={runHydration}>
                  {t('setup.hydration.retry')}
                </Button>
              </Card>
            ) : (
              <Card>
                <Spin size="large" />
                <p style={{ marginTop: 16 }}>{t('setup.hydration.loading')}</p>
              </Card>
            )}
          </Space>
        </div>
      </div>
    );
  }

  const steps = [
    { title: t('setup.steps.couchdb'), key: 'couchdb' },
    { title: t('setup.steps.probe'), key: 'probe' },
    { title: t('setup.steps.auth'), key: 'auth' },
    { title: t('setup.steps.admin'), key: 'admin' },
    { title: t('setup.steps.vector'), key: 'vector' },
    { title: t('setup.steps.provenance'), key: 'provenance' },
    { title: t('setup.steps.confirm'), key: 'confirm' },
  ];

  const canGoNext = stepValid[currentStep] === true;

  const renderStep = () => {
    switch (currentStep) {
      case 0:
        return (
          <CouchDbStep
            value={state.couchdb}
            onChange={(couchdb) => setState(prev => ({ ...prev, couchdb }))}
            onValidChange={onValidChange0}
          />
        );
      case 1:
        return (
          <ProbeStep
            couchdb={state.couchdb}
            onValidChange={onValidChange1}
          />
        );
      case 2:
        return (
          <AuthStep
            value={state.auth}
            onChange={(auth) => setState(prev => ({ ...prev, auth }))}
            onValidChange={onValidChange2}
          />
        );
      case 3:
        return (
          <AdminStep
            value={state.admin}
            onChange={(admin) => setState(prev => ({ ...prev, admin }))}
            onValidChange={onValidChange3}
          />
        );
      case 4:
        return (
          <VectorStep
            value={state.vector}
            onChange={(vector) => setState(prev => ({ ...prev, vector }))}
            onValidChange={onValidChange4}
          />
        );
      case 5:
        return (
          <ProvenanceStep
            value={state.provenance}
            onChange={(provenance) => setState(prev => ({ ...prev, provenance }))}
          />
        );
      case 6:
        return (
          <ConfirmStep
            couchdb={state.couchdb}
            auth={state.auth}
            admin={state.admin}
            vector={state.vector}
            provenance={state.provenance}
            onComplete={onComplete}
          />
        );
      default:
        return null;
    }
  };

  return (
    <div style={{ minHeight: '100vh', background: '#f5f5f5', padding: '24px 0' }}>
      <div style={{ maxWidth: 800, margin: '0 auto', padding: '0 24px' }}>
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <Title level={2} style={{ textAlign: 'center', margin: 0 }}>
            {t('setup.title')}
          </Title>

          <Steps current={currentStep} items={steps.map(s => ({ title: s.title }))} size="small" />

          <Card>{renderStep()}</Card>

          {currentStep < 5 && (
            <Space style={{ display: 'flex', justifyContent: 'space-between' }}>
              <Button
                disabled={currentStep === 0}
                onClick={() => setCurrentStep(prev => prev - 1)}
              >
                {t('setup.nav.previous')}
              </Button>
              <Button
                type="primary"
                disabled={!canGoNext}
                onClick={() => setCurrentStep(prev => prev + 1)}
              >
                {t('setup.nav.next')}
              </Button>
            </Space>
          )}
        </Space>
      </div>
    </div>
  );
}

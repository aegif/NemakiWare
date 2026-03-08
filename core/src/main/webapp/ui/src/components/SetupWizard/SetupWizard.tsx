import { useState, useCallback, useMemo } from 'react';
import { Steps, Button, Card, Typography, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import { TokenEntry } from './TokenEntry';
import { CouchDbStep, CouchDbConfig } from './steps/CouchDbStep';
import { ProbeStep } from './steps/ProbeStep';
import { AuthStep, AuthConfig } from './steps/AuthStep';
import { AdminStep, AdminConfig } from './steps/AdminStep';
import { VectorStep, VectorConfig } from './steps/VectorStep';
import { ConfirmStep } from './steps/ConfirmStep';

const { Title } = Typography;

interface SetupWizardProps {
  onComplete: () => void;
}

interface WizardState {
  couchdb: CouchDbConfig;
  auth: AuthConfig;
  admin: AdminConfig;
  vector: VectorConfig;
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
  },
  admin: { newPassword: '', confirmPassword: '' },
  vector: { type: 'none', url: '', region: '', modelId: '', accessKeyId: '', secretAccessKey: '' },
};

export function SetupWizard({ onComplete }: SetupWizardProps) {
  const { t } = useTranslation();
  const [tokenVerified, setTokenVerified] = useState(false);
  const [currentStep, setCurrentStep] = useState(0);
  const [state, setState] = useState<WizardState>(initialState);
  const [stepValid, setStepValid] = useState<Record<number, boolean>>({
    0: false, 1: false, 2: false, 3: false, 4: false,
  });

  const handleTokenVerified = () => {
    setTokenVerified(true);
  };

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

  const steps = [
    { title: t('setup.steps.couchdb'), key: 'couchdb' },
    { title: t('setup.steps.probe'), key: 'probe' },
    { title: t('setup.steps.auth'), key: 'auth' },
    { title: t('setup.steps.admin'), key: 'admin' },
    { title: t('setup.steps.vector'), key: 'vector' },
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
          <ConfirmStep
            couchdb={state.couchdb}
            auth={state.auth}
            admin={state.admin}
            vector={state.vector}
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

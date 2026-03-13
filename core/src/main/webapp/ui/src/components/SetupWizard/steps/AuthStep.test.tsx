import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { AuthStep } from './AuthStep';
import type { AuthConfig } from '../../../services/setupApi';

// Track testOidc calls and control responses
let testOidcResponses: Record<string, unknown> = {};
const testOidcMock = vi.fn(async (req: { issuerUrl: string; clientId?: string }) => {
  const key = req.issuerUrl.includes('google') ? 'google' : 'microsoft';
  return testOidcResponses[key] ?? { reachable: true };
});

vi.mock('../../../services/setupApi', () => ({
  setupApi: {
    testOidc: (...args: unknown[]) => testOidcMock(...(args as [{ issuerUrl: string; clientId?: string }])),
  },
}));

const STABLE_T = (key: string) => key;
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: STABLE_T, i18n: { language: 'en' } }),
}));

function defaultConfig(): AuthConfig {
  return {
    passwordEnabled: true,
    googleEnabled: false,
    microsoftEnabled: false,
    googleClientId: undefined,
    microsoftClientId: undefined,
    microsoftTenantId: undefined,
  };
}

describe('AuthStep validation', () => {
  let validHistory: boolean[];
  const onChange = vi.fn<(c: AuthConfig) => void>();
  const onValidChange = (v: boolean) => { validHistory.push(v); };

  beforeEach(() => {
    validHistory = [];
    onChange.mockClear();
    testOidcResponses = {};
    testOidcMock.mockClear();
  });

  it('password-only config is valid without OIDC test', () => {
    render(<AuthStep value={defaultConfig()} onChange={onChange} onValidChange={onValidChange} />);
    // passwordEnabled=true, others disabled → valid
    expect(validHistory[validHistory.length - 1]).toBe(true);
  });

  it('all disabled is invalid (lockout prevention)', () => {
    const config = { ...defaultConfig(), passwordEnabled: false };
    render(<AuthStep value={config} onChange={onChange} onValidChange={onValidChange} />);
    expect(validHistory[validHistory.length - 1]).toBe(false);
  });

  it('Google enabled without clientId is invalid', () => {
    const config = { ...defaultConfig(), googleEnabled: true, googleClientId: '' };
    render(<AuthStep value={config} onChange={onChange} onValidChange={onValidChange} />);
    expect(validHistory[validHistory.length - 1]).toBe(false);
  });

  it('Google enabled with clientId but no OIDC test is invalid', () => {
    const config = { ...defaultConfig(), googleEnabled: true, googleClientId: 'test-client-id' };
    render(<AuthStep value={config} onChange={onChange} onValidChange={onValidChange} />);
    // OIDC test not yet run → googleTestPassed is false
    expect(validHistory[validHistory.length - 1]).toBe(false);
  });

  it('Google passes after OIDC test returns clientIdValid=true', async () => {
    testOidcResponses.google = { reachable: true, clientIdValid: true };
    const config = { ...defaultConfig(), googleEnabled: true, googleClientId: 'valid-client-id' };
    const { rerender } = render(
      <AuthStep value={config} onChange={onChange} onValidChange={onValidChange} />
    );

    // Click test button
    const testButton = screen.getByText('setup.auth.testOidc');
    await act(async () => { fireEvent.click(testButton); });

    // After OIDC test, rerender to pick up state change
    await waitFor(() => {
      rerender(<AuthStep value={config} onChange={onChange} onValidChange={onValidChange} />);
      expect(validHistory[validHistory.length - 1]).toBe(true);
    });
  });

  it('Google fails after OIDC test returns clientIdValid=false', async () => {
    testOidcResponses.google = { reachable: true, clientIdValid: false };
    const config = { ...defaultConfig(), googleEnabled: true, googleClientId: 'fake-client-id' };
    render(<AuthStep value={config} onChange={onChange} onValidChange={onValidChange} />);

    const testButton = screen.getByText('setup.auth.testOidc');
    await act(async () => { fireEvent.click(testButton); });

    await waitFor(() => {
      expect(validHistory[validHistory.length - 1]).toBe(false);
    });
  });

  it('Microsoft enabled without clientId is invalid', () => {
    const config = { ...defaultConfig(), microsoftEnabled: true, microsoftClientId: '' };
    render(<AuthStep value={config} onChange={onChange} onValidChange={onValidChange} />);
    expect(validHistory[validHistory.length - 1]).toBe(false);
  });

  it('Microsoft indeterminate is invalid without acknowledgement checkbox', async () => {
    // Backend returns indeterminate (clientIdIndeterminate=true) for Microsoft
    testOidcResponses.microsoft = { reachable: true, clientIdIndeterminate: true, clientIdError: 'Unable to determine client ID validity' };
    const config = { ...defaultConfig(), microsoftEnabled: true, microsoftClientId: 'some-client-id' };
    render(<AuthStep value={config} onChange={onChange} onValidChange={onValidChange} />);

    const testButton = screen.getByText('setup.auth.testOidc');
    await act(async () => { fireEvent.click(testButton); });

    // After OIDC test with indeterminate result, still invalid (checkbox not checked)
    await waitFor(() => {
      expect(validHistory[validHistory.length - 1]).toBe(false);
    });
  });

  it('Microsoft indeterminate passes after acknowledgement checkbox', async () => {
    testOidcResponses.microsoft = { reachable: true, clientIdIndeterminate: true, clientIdError: 'Unable to determine client ID validity' };
    const config = { ...defaultConfig(), microsoftEnabled: true, microsoftClientId: 'some-client-id' };
    render(<AuthStep value={config} onChange={onChange} onValidChange={onValidChange} />);

    const testButton = screen.getByText('setup.auth.testOidc');
    await act(async () => { fireEvent.click(testButton); });

    // Wait for checkbox to appear
    await waitFor(() => {
      expect(screen.getByText('setup.auth.microsoftClientIdAcknowledge')).toBeTruthy();
    });

    // Check the acknowledgement checkbox
    const checkbox = screen.getByRole('checkbox');
    await act(async () => { fireEvent.click(checkbox); });

    await waitFor(() => {
      expect(validHistory[validHistory.length - 1]).toBe(true);
    });
  });

  it('Microsoft probe failure (non-indeterminate) blocks step without checkbox', async () => {
    // Backend returns clientIdError WITHOUT clientIdIndeterminate (real probe failure)
    testOidcResponses.microsoft = { reachable: true, clientIdError: 'Connection timed out' };
    const config = { ...defaultConfig(), microsoftEnabled: true, microsoftClientId: 'some-client-id' };
    render(<AuthStep value={config} onChange={onChange} onValidChange={onValidChange} />);

    const testButton = screen.getByText('setup.auth.testOidc');
    await act(async () => { fireEvent.click(testButton); });

    // Probe failure: no checkbox should appear, step remains invalid
    await waitFor(() => {
      expect(validHistory[validHistory.length - 1]).toBe(false);
    });
    expect(screen.queryByRole('checkbox')).toBeNull();
  });

  it('Microsoft without running OIDC test is invalid', () => {
    const config = { ...defaultConfig(), microsoftEnabled: true, microsoftClientId: 'some-client-id' };
    render(<AuthStep value={config} onChange={onChange} onValidChange={onValidChange} />);
    // reachable is undefined (test not run) → fails
    expect(validHistory[validHistory.length - 1]).toBe(false);
  });

  it('changing clientId resets OIDC test result for Google', async () => {
    testOidcResponses.google = { reachable: true, clientIdValid: true };
    const config = { ...defaultConfig(), googleEnabled: true, googleClientId: 'valid-id' };
    const { rerender } = render(
      <AuthStep value={config} onChange={onChange} onValidChange={onValidChange} />
    );

    // Run test → valid
    const testButton = screen.getByText('setup.auth.testOidc');
    await act(async () => { fireEvent.click(testButton); });

    await waitFor(() => {
      rerender(<AuthStep value={config} onChange={onChange} onValidChange={onValidChange} />);
      expect(validHistory[validHistory.length - 1]).toBe(true);
    });

    // Change clientId → test result should reset → invalid
    const updatedConfig = { ...config, googleClientId: 'changed-id' };
    rerender(<AuthStep value={updatedConfig} onChange={onChange} onValidChange={onValidChange} />);

    // Simulate the onChange handler resetting OIDC test state
    const input = screen.getByPlaceholderText('xxxxx.apps.googleusercontent.com');
    await act(async () => { fireEvent.change(input, { target: { value: 'new-id' } }); });

    await waitFor(() => {
      // After clientId change, test result is reset → googleTestPassed becomes false
      expect(validHistory[validHistory.length - 1]).toBe(false);
    });
  });

  it('changing microsoftTenantId resets OIDC test result and acknowledgement', async () => {
    testOidcResponses.microsoft = { reachable: true, clientIdIndeterminate: true, clientIdError: 'Unable to determine' };
    const config = { ...defaultConfig(), microsoftEnabled: true, microsoftClientId: 'some-id', microsoftTenantId: 'common' };
    render(<AuthStep value={config} onChange={onChange} onValidChange={onValidChange} />);

    // Run test
    const testButton = screen.getByText('setup.auth.testOidc');
    await act(async () => { fireEvent.click(testButton); });

    // Check acknowledgement checkbox → valid
    await waitFor(() => {
      expect(screen.getByRole('checkbox')).toBeTruthy();
    });
    await act(async () => { fireEvent.click(screen.getByRole('checkbox')); });
    await waitFor(() => {
      expect(validHistory[validHistory.length - 1]).toBe(true);
    });

    // Change tenantId → test result and acknowledgement should reset → invalid
    const tenantInput = screen.getByPlaceholderText('common');
    await act(async () => { fireEvent.change(tenantInput, { target: { value: 'new-tenant' } }); });

    await waitFor(() => {
      expect(validHistory[validHistory.length - 1]).toBe(false);
    });
  });
});

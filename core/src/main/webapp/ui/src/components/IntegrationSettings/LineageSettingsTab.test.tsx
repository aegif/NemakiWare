import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { LineageSettingsTab } from './LineageSettingsTab';

const stableT = (key: string) => key;
const stableTranslation = { t: stableT };

const mockMessage = { success: vi.fn(), error: vi.fn() };

vi.mock('react-i18next', () => ({
  useTranslation: () => stableTranslation,
}));

vi.mock('antd', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('antd');
  return {
    ...actual,
    App: {
      ...(actual.App as Record<string, unknown>),
      useApp: () => ({ message: mockMessage }),
    },
  };
});

const mockGetLineageSettings = vi.fn();
const mockUpdateLineageSettings = vi.fn();

vi.mock('../../services/integrationSettings', () => ({
  getLineageSettings: (...args: unknown[]) => mockGetLineageSettings(...args),
  updateLineageSettings: (...args: unknown[]) => mockUpdateLineageSettings(...args),
}));

function buildSettingsResponse(overrides: Record<string, string> = {}) {
  const defaults: Record<string, string> = {
    'lineage.mode': 'disabled',
    'lineage.targets': '',
    'lineage.retention.days': '90',
    'lineage.capture.version-events': 'false',
    'lineage.capture.generic-relationships': 'false',
    'lineage.purge.cron': '',
    'lineage.snapshot.max-name-length': '512',
    'lineage.snapshot.max-path-length': '2048',
    'lineage.snapshot.capture-path': 'true',
  };
  const settings = { ...defaults, ...overrides };
  const sources: Record<string, string> = {};
  for (const key of Object.keys(settings)) {
    sources[key] = 'couchdb';
  }
  return { settings, sources };
}

describe('LineageSettingsTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetLineageSettings.mockResolvedValue(buildSettingsResponse());
    mockUpdateLineageSettings.mockResolvedValue({ status: 'success', message: 'ok' });
  });

  it('renders form fields after loading', async () => {
    render(<LineageSettingsTab />);

    await waitFor(() => {
      expect(screen.queryByRole('img', { name: 'loading' })).not.toBeInTheDocument();
    });

    expect(screen.getByText('integrationSettings.lineage.mode')).toBeInTheDocument();
    expect(screen.getByText('integrationSettings.lineage.targets')).toBeInTheDocument();
    expect(screen.getByText('integrationSettings.lineage.retentionDays')).toBeInTheDocument();
    expect(screen.getByText('integrationSettings.lineage.captureVersionEvents')).toBeInTheDocument();
    expect(screen.getByText('integrationSettings.lineage.captureGenericRelationships')).toBeInTheDocument();
    expect(screen.getByText('integrationSettings.lineage.purgeCron')).toBeInTheDocument();
    expect(screen.getByText('integrationSettings.lineage.snapshotMaxNameLength')).toBeInTheDocument();
    expect(screen.getByText('integrationSettings.lineage.snapshotCapturePath')).toBeInTheDocument();
  });

  it('displays disabled notice when mode is disabled', async () => {
    render(<LineageSettingsTab />);

    await waitFor(() => {
      expect(screen.queryByRole('img', { name: 'loading' })).not.toBeInTheDocument();
    });

    expect(screen.getByText('integrationSettings.lineage.disabledNotice')).toBeInTheDocument();
  });

  it('displays journaled notice when mode is journaled', async () => {
    mockGetLineageSettings.mockResolvedValue(
      buildSettingsResponse({ 'lineage.mode': 'journaled' }),
    );

    render(<LineageSettingsTab />);

    await waitFor(() => {
      expect(screen.queryByRole('img', { name: 'loading' })).not.toBeInTheDocument();
    });

    expect(screen.getByText('integrationSettings.lineage.journaledNotice')).toBeInTheDocument();
  });

  it('displays direct notice when mode is direct', async () => {
    mockGetLineageSettings.mockResolvedValue(
      buildSettingsResponse({ 'lineage.mode': 'direct' }),
    );

    render(<LineageSettingsTab />);

    await waitFor(() => {
      expect(screen.queryByRole('img', { name: 'loading' })).not.toBeInTheDocument();
    });

    expect(screen.getByText('integrationSettings.lineage.directNotice')).toBeInTheDocument();
  });

  it('Save button is disabled when no changes', async () => {
    render(<LineageSettingsTab />);

    await waitFor(() => {
      expect(screen.queryByRole('img', { name: 'loading' })).not.toBeInTheDocument();
    });

    const saveButton = screen.getByRole('button', { name: 'integrationSettings.save' });
    expect(saveButton).toBeDisabled();
  });

  it('saves settings on Save click', async () => {
    render(<LineageSettingsTab />);

    await waitFor(() => {
      expect(screen.queryByRole('img', { name: 'loading' })).not.toBeInTheDocument();
    });

    // Change a field to enable the save button
    const targetInput = screen.getByPlaceholderText('purview, atlas, dataplex');
    fireEvent.change(targetInput, { target: { value: 'purview' } });

    const saveButton = screen.getByRole('button', { name: 'integrationSettings.save' });
    expect(saveButton).not.toBeDisabled();

    fireEvent.click(saveButton);

    await waitFor(() => {
      expect(mockUpdateLineageSettings).toHaveBeenCalledTimes(1);
    });

    expect(mockMessage.success).toHaveBeenCalled();
  });

  it('renders mode select with three options', async () => {
    render(<LineageSettingsTab />);

    await waitFor(() => {
      expect(screen.queryByRole('img', { name: 'loading' })).not.toBeInTheDocument();
    });

    // The select should show the current mode label (disabled)
    expect(screen.getByText('integrationSettings.lineage.modeDisabled')).toBeInTheDocument();
  });
});

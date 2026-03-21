import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { PurviewGovernancePanel } from './PurviewGovernancePanel';

const mockGovernanceService = {
  getGovernance: vi.fn(),
};

const mockHandleAuthError = vi.fn();

vi.mock('../../services/purviewGovernance', () => ({
  PurviewGovernanceService: class {
    constructor() {
      return mockGovernanceService;
    }
  },
}));

vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({
    handleAuthError: mockHandleAuthError,
  }),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

describe('PurviewGovernancePanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders classifications, glossary terms, labels and business metadata', async () => {
    mockGovernanceService.getGovernance.mockResolvedValue({
      featureEnabled: true,
      available: true,
      supportedObjectType: true,
      entityFound: true,
      repositoryId: 'bedroom',
      objectId: 'doc-001',
      objectBaseType: 'cmis:document',
      entityTypeName: 'nemaki_document',
      qualifiedName: 'nemaki://bedroom/objects/doc-001',
      atlasBasePath: 'datamap/api/atlas/v2',
      message: 'loaded',
      classifications: [{ typeName: 'HighlyConfidential', entityStatus: 'ACTIVE' }],
      glossaryTerms: [{ displayText: 'Quarterly Report', termGuid: 'term-001' }],
      labels: ['finance'],
      businessMetadata: {
        nemakiGovernance: {
          ownerDepartment: 'Finance',
        },
      },
    });

    render(<PurviewGovernancePanel repositoryId="bedroom" objectId="doc-001" />);

    await waitFor(() => {
      expect(screen.getByText('HighlyConfidential')).toBeInTheDocument();
    });
    expect(screen.getByText('Quarterly Report')).toBeInTheDocument();
    expect(screen.getByText('finance')).toBeInTheDocument();
    expect(screen.getByText('ownerDepartment')).toBeInTheDocument();
    expect(screen.getByText('Finance')).toBeInTheDocument();
  });

  it('renders status alert when governance asset is not synced', async () => {
    mockGovernanceService.getGovernance.mockResolvedValue({
      featureEnabled: true,
      available: true,
      supportedObjectType: true,
      entityFound: false,
      repositoryId: 'bedroom',
      objectId: 'doc-001',
      objectBaseType: 'cmis:document',
      entityTypeName: 'nemaki_document',
      qualifiedName: 'nemaki://bedroom/objects/doc-001',
      atlasBasePath: 'datamap/api/atlas/v2',
      message: 'Purview governance metadata is not synced for this object yet',
      classifications: [],
      glossaryTerms: [],
      labels: [],
      businessMetadata: {},
    });

    render(<PurviewGovernancePanel repositoryId="bedroom" objectId="doc-001" />);

    await waitFor(() => {
      expect(
        screen.getByText('Purview governance metadata is not synced for this object yet')
      ).toBeInTheDocument();
    });
    expect(screen.getByText('documentViewer.purviewGovernance.status')).toBeInTheDocument();
  });
});

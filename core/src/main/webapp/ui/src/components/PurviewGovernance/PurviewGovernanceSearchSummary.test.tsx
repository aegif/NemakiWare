import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { PurviewGovernanceSearchSummary } from './PurviewGovernanceSearchSummary';

const mockGovernanceService = {
  getGovernanceBulk: vi.fn(),
};

const mockHandleAuthError = vi.fn();
const stableT = (key: string, params?: Record<string, unknown>) =>
  params?.count !== undefined ? `${key}:${params.count}` : key;
const stableTranslation = { t: stableT };

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
  useTranslation: () => stableTranslation,
}));

describe('PurviewGovernanceSearchSummary', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('loads governance metadata for searchable documents and folders', async () => {
    mockGovernanceService.getGovernanceBulk.mockResolvedValue([
      {
        objectId: 'doc-001',
        status: 'OK',
        entityFound: true,
        classifications: [{ typeName: 'HighlyConfidential', entityStatus: 'ACTIVE' }],
        glossaryTerms: [{ displayText: 'Quarterly Report' }],
        labels: ['finance'],
      },
      {
        objectId: 'folder-001',
        status: 'OK',
        entityFound: false,
        classifications: [],
        glossaryTerms: [],
        labels: [],
      },
    ]);

    render(
      <PurviewGovernanceSearchSummary
        repositoryId="bedroom"
        objects={[
          {
            id: 'doc-001',
            name: 'Document 1',
            objectType: 'cmis:document',
            baseType: 'cmis:document',
            properties: {},
          },
          {
            id: 'folder-001',
            name: 'Folder 1',
            objectType: 'cmis:folder',
            baseType: 'cmis:folder',
            properties: {},
          },
          {
            id: 'rel-001',
            name: 'Relationship 1',
            objectType: 'cmis:relationship',
            baseType: 'cmis:relationship',
            properties: {},
          },
        ]}
      />
    );

    await waitFor(() => {
      expect(mockGovernanceService.getGovernanceBulk).toHaveBeenCalledWith('bedroom', ['doc-001', 'folder-001']);
    });

    expect(screen.getByText('searchResults.purviewGovernance.scanned:2')).toBeInTheDocument();
    expect(screen.getByText('searchResults.purviewGovernance.synced:1')).toBeInTheDocument();
    expect(screen.getByText('searchResults.purviewGovernance.unresolved:1')).toBeInTheDocument();
    expect(screen.getByText('HighlyConfidential')).toBeInTheDocument();
    expect(screen.getByText('Quarterly Report')).toBeInTheDocument();
  });

  it('renders nothing when there are no searchable objects', () => {
    const { container } = render(
      <PurviewGovernanceSearchSummary
        repositoryId="bedroom"
        objects={[
          {
            id: 'rel-001',
            name: 'Relationship 1',
            objectType: 'cmis:relationship',
            baseType: 'cmis:relationship',
            properties: {},
          },
        ]}
      />
    );

    expect(container).toBeEmptyDOMElement();
    expect(mockGovernanceService.getGovernanceBulk).not.toHaveBeenCalled();
  });
});

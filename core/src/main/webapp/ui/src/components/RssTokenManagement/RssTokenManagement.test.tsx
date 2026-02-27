import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor, act, cleanup } from '@testing-library/react';
import { RssTokenManagement } from './RssTokenManagement';

// Track fetch calls for verifying cache behavior
let fetchCallLog: string[] = [];

// Stable references (must not change between renders to avoid infinite useEffect loop)
const STABLE_AUTH_TOKEN = { token: 'test-token', username: 'admin', repositoryId: 'bedroom' };
const STABLE_AUTH_VALUE = { authToken: STABLE_AUTH_TOKEN };
const STABLE_T = (key: string) => key;
const STABLE_I18N = { language: 'en' };
const STABLE_TRANSLATION = { t: STABLE_T, i18n: STABLE_I18N };

// Mock AuthContext
vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => STABLE_AUTH_VALUE,
}));

// Mock i18n
vi.mock('react-i18next', () => ({
  useTranslation: () => STABLE_TRANSLATION,
}));

// Mock CmisAuthHeaderProvider
vi.mock('../../services/auth/CmisAuthHeaderProvider', () => ({
  getCmisAuthHeaders: () => ({}),
}));

// Mock ObjectPicker
vi.mock('../ObjectPicker/ObjectPicker', () => ({
  ObjectPicker: () => null,
}));

// Tokens API response with two folders
const makeTokensResponse = () => ({
  status: 'success',
  tokens: [
    {
      id: 'token-1',
      name: 'Test Token',
      enabled: true,
      token: 'abc123',
      folderIds: ['folder-aaa', 'folder-bbb'],
      events: ['created', 'updated'],
      createdAt: '2026-01-01',
      expiresAt: '2027-01-01',
      expired: false,
    },
  ],
});

// CMIS object resolution response
const makeCmisResponse = (objectId: string) => ({
  succinctProperties: {
    'cmis:name': `name-of-${objectId}`,
    'cmis:path': `/path/to/${objectId}`,
  },
});

beforeEach(() => {
  fetchCallLog = [];

  // Setup global.fetch mock
  global.fetch = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input.toString();
    fetchCallLog.push(url);

    // RSS tokens endpoint
    if (url.includes('/rss/tokens')) {
      return new Response(JSON.stringify(makeTokensResponse()), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }

    // CMIS object resolution endpoint
    if (url.includes('cmisselector=object')) {
      const match = url.match(/objectId=([^&]+)/);
      const objectId = match ? decodeURIComponent(match[1]) : 'unknown';
      return new Response(JSON.stringify(makeCmisResponse(objectId)), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }

    return new Response('{}', { status: 404 });
  }) as unknown as typeof fetch;
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

/**
 * Count how many fetch calls were made to CMIS object resolution for a specific objectId.
 */
function countResolveCallsFor(objectId: string): number {
  return fetchCallLog.filter(
    url => url.includes('cmisselector=object') && url.includes(encodeURIComponent(objectId))
  ).length;
}

/**
 * Count total CMIS object resolution calls.
 */
function countAllResolveCalls(): number {
  return fetchCallLog.filter(url => url.includes('cmisselector=object')).length;
}

describe('RssTokenManagement folder cache', () => {
  it('resolves each folder exactly once on initial load', async () => {
    await act(async () => {
      render(<RssTokenManagement repositoryId="bedroom" />);
    });

    // Wait for initial load to complete (tokens API + 2 folder resolutions)
    await waitFor(() => {
      expect(countAllResolveCalls()).toBe(2);
    }, { timeout: 3000 });

    expect(countResolveCallsFor('folder-aaa')).toBe(1);
    expect(countResolveCallsFor('folder-bbb')).toBe(1);
  });

  it('does not re-resolve cached folders when component re-renders with same props', async () => {
    const { rerender } = render(<RssTokenManagement repositoryId="bedroom" />);

    // Wait for initial load
    await waitFor(() => {
      expect(countAllResolveCalls()).toBe(2);
    }, { timeout: 3000 });

    // Reset log to track only new calls
    fetchCallLog = [];

    // Trigger re-render with same props — loadTokens shouldn't re-run
    // since useCallback deps haven't changed
    await act(async () => {
      rerender(<RssTokenManagement repositoryId="bedroom" />);
    });

    // Give time for any async operations
    await new Promise(r => setTimeout(r, 500));

    // No new folder resolve calls should have been made
    const newResolveCalls = countAllResolveCalls();
    expect(newResolveCalls).toBe(0);
  });

  it('clears folder cache and re-resolves when repositoryId changes', async () => {
    const { rerender } = render(<RssTokenManagement repositoryId="bedroom" />);

    // Wait for initial load (2 folder resolutions for bedroom)
    await waitFor(() => {
      expect(countAllResolveCalls()).toBe(2);
    }, { timeout: 3000 });

    // Reset log to track only new calls after repository switch
    fetchCallLog = [];

    // Switch repository — cache should be cleared and folders re-resolved
    await act(async () => {
      rerender(<RssTokenManagement repositoryId="canopy" />);
    });

    // Wait for re-resolution: same 2 folders should be fetched again
    // because the cache was cleared on repository change
    await waitFor(() => {
      expect(countAllResolveCalls()).toBe(2);
    }, { timeout: 3000 });

    // Verify the resolve calls target the new repository
    const canopyResolveCalls = fetchCallLog.filter(
      url => url.includes('cmisselector=object') && url.includes('/canopy/')
    );
    expect(canopyResolveCalls.length).toBe(2);

    // Verify no calls went to the old repository
    const bedroomResolveCalls = fetchCallLog.filter(
      url => url.includes('cmisselector=object') && url.includes('/bedroom/')
    );
    expect(bedroomResolveCalls.length).toBe(0);
  });
});

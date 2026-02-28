import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, act, fireEvent, cleanup } from '@testing-library/react';
import { RssTokenManagement } from './RssTokenManagement';

// Track fetch calls for verifying cache behavior
let fetchCallLog: string[] = [];
// Control response delay per repository for tokens endpoint
let tokensDelay: Record<string, number> = {};

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

// Tokens API response with two folders (repo-specific name for DOM identification)
const makeTokensResponse = (repoId = 'default') => ({
  status: 'success',
  tokens: [
    {
      id: `token-${repoId}`,
      name: `Token-${repoId}`,
      enabled: true,
      token: `abc123-${repoId}`,
      folderIds: ['folder-aaa', 'folder-bbb'],
      events: ['created', 'updated'],
      createdAt: '2026-01-01',
      expiresAt: '2027-01-01',
      expired: false,
    },
  ],
});

function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// CMIS object resolution response
const makeCmisResponse = (objectId: string) => ({
  succinctProperties: {
    'cmis:name': `name-of-${objectId}`,
    'cmis:path': `/path/to/${objectId}`,
  },
});

beforeEach(() => {
  fetchCallLog = [];
  tokensDelay = {};

  // Setup global.fetch mock
  global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();

    // Determine which repository this request targets
    const repoMatch = url.match(/\/repo\/([^/]+)\//);
    const repo = repoMatch ? repoMatch[1] : '';

    fetchCallLog.push(url);

    // RSS token mutation endpoints (disable/refresh/delete) — respond immediately
    if (url.includes('/rss/tokens/') && (url.includes('/disable') || url.includes('/refresh'))) {
      return new Response(JSON.stringify({ status: 'success' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }

    // RSS tokens list endpoint (with configurable delay)
    if (url.includes('/rss/tokens')) {
      const td = tokensDelay[repo] || 0;
      if (td > 0) {
        await delay(td);
        if (init?.signal?.aborted) {
          throw new DOMException('The operation was aborted.', 'AbortError');
        }
      }
      return new Response(JSON.stringify(makeTokensResponse(repo)), {
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

describe('RssTokenManagement manual operation race condition', () => {
  it('discards stale loadTokens response triggered by handleDisable after repository switch', async () => {
    const { rerender, container } = render(<RssTokenManagement repositoryId="bedroom" />);

    // Wait for bedroom token data to appear in DOM
    await waitFor(() => {
      expect(screen.getByText('Token-bedroom')).toBeTruthy();
    }, { timeout: 3000 });

    // Configure: bedroom loadTokens reload will be slow (simulates post-disable re-fetch)
    tokensDelay['bedroom'] = 400;
    tokensDelay['canopy'] = 0;

    // Trigger handleDisable — PUT .../disable returns immediately, then loadTokens() starts
    const disableButton = screen.getByText('rssManagement.disable');
    fireEvent.click(disableButton);

    // Wait for disable API call to complete, loadTokens starts (with 400ms delay)
    await delay(50);

    // Switch repository while bedroom's loadTokens is in-flight
    rerender(<RssTokenManagement repositoryId="canopy" />);

    // Wait for canopy data to load
    await waitFor(() => {
      expect(screen.getByText('Token-canopy')).toBeTruthy();
    }, { timeout: 3000 });

    // Wait for bedroom's slow loadTokens response to arrive
    await delay(600);

    // DOM must show canopy data, not bedroom
    expect(screen.getByText('Token-canopy')).toBeTruthy();
    expect(screen.queryByText('Token-bedroom')).toBeNull();

    // Verify row key is canopy's token, not bedroom's
    expect(container.innerHTML).toContain('token-canopy');
    expect(container.innerHTML).not.toContain('token-bedroom');
  });

  it('discards stale loadTokens response triggered by handleRefresh after repository switch', async () => {
    const { rerender, container } = render(<RssTokenManagement repositoryId="bedroom" />);

    // Wait for bedroom token data to appear
    await waitFor(() => {
      expect(screen.getByText('Token-bedroom')).toBeTruthy();
    }, { timeout: 3000 });

    // Configure slow reload for bedroom
    tokensDelay['bedroom'] = 400;
    tokensDelay['canopy'] = 0;

    // Click refresh button (ReloadOutlined icon)
    const refreshButton = container.querySelector('button .anticon-reload')?.closest('button');
    expect(refreshButton).not.toBeNull();
    fireEvent.click(refreshButton!);

    // Wait for refresh API to complete, loadTokens starts (slow)
    await delay(50);

    // Switch repository
    rerender(<RssTokenManagement repositoryId="canopy" />);

    // Wait for canopy data
    await waitFor(() => {
      expect(screen.getByText('Token-canopy')).toBeTruthy();
    }, { timeout: 3000 });

    // Wait for bedroom's slow response
    await delay(600);

    // DOM must show canopy data only
    expect(screen.getByText('Token-canopy')).toBeTruthy();
    expect(screen.queryByText('Token-bedroom')).toBeNull();

    // Verify row key is canopy's, not bedroom's
    expect(container.innerHTML).toContain('token-canopy');
    expect(container.innerHTML).not.toContain('token-bedroom');
  });
});

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

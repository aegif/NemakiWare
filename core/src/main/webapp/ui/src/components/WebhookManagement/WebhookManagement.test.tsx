import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, cleanup } from '@testing-library/react';
import { WebhookManagement } from './WebhookManagement';

// Track fetch calls for verifying request targets
let fetchCallLog: string[] = [];
// Control response delay per repository to simulate slow responses
let responseDelay: Record<string, number> = {};

// Stable mock references
const STABLE_AUTH_VALUE = { handleAuthError: vi.fn() };
const STABLE_T = (key: string) => key;
const STABLE_TRANSLATION = { t: STABLE_T, i18n: { language: 'en' } };

vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => STABLE_AUTH_VALUE,
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => STABLE_TRANSLATION,
}));

vi.mock('../../services/auth/CmisAuthHeaderProvider', () => ({
  getCmisAuthHeaders: () => ({}),
}));

/** Configs API response: one object with one config */
const makeConfigsResponse = (repoId: string) => ({
  status: 'success',
  objects: [
    {
      objectId: `obj-${repoId}`,
      objectName: `Object in ${repoId}`,
      objectPath: `/${repoId}/root`,
      webhookConfigs: [
        {
          id: `cfg-${repoId}`,
          enabled: true,
          url: `https://hook.example.com/${repoId}`,
          events: ['created'],
          authType: null,
          includeChildren: false,
          maxDepth: null,
          retryCount: null,
          hasAuthCredential: false,
          hasSecret: false,
        },
      ],
    },
  ],
});

/** Deliveries API response */
const makeDeliveriesResponse = (repoId: string) => ({
  status: 'success',
  deliveries: [
    {
      deliveryId: `del-${repoId}`,
      objectId: `obj-${repoId}`,
      eventType: 'created',
      webhookUrl: `https://hook.example.com/${repoId}`,
      statusCode: 200,
      success: true,
      attemptCount: 1,
      deliveredAt: '2026-01-01T00:00:00Z',
      responseBody: null,
    },
  ],
});

/** CMIS object resolution response */
const makeCmisResponse = (objectId: string) => ({
  succinctProperties: {
    'cmis:name': `name-of-${objectId}`,
    'cmis:path': `/path/to/${objectId}`,
  },
});

function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

beforeEach(() => {
  fetchCallLog = [];
  responseDelay = {};

  global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();

    // Determine which repository this request targets
    const repoMatch = url.match(/\/repo\/([^/]+)\//);
    const repo = repoMatch ? repoMatch[1] : '';

    // Apply configured delay for this repository (simulates slow network)
    const delayMs = responseDelay[repo] || 0;
    if (delayMs > 0) {
      await delay(delayMs);
    }

    // Check if request was aborted during delay
    if (init?.signal?.aborted) {
      throw new DOMException('The operation was aborted.', 'AbortError');
    }

    fetchCallLog.push(url);

    if (url.includes('/webhook/configs')) {
      return new Response(JSON.stringify(makeConfigsResponse(repo)), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }

    if (url.includes('/webhook/deliveries')) {
      return new Response(JSON.stringify(makeDeliveriesResponse(repo)), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }

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

/** Count fetch calls targeting a specific repository */
function countCallsForRepo(repoId: string): number {
  return fetchCallLog.filter(url => url.includes(`/repo/${repoId}/`)).length;
}

describe('WebhookManagement repository switch race condition', () => {
  it('does not apply stale response after repository switch', async () => {
    // Slow down bedroom responses so they arrive after repo switch
    responseDelay['bedroom'] = 300;
    responseDelay['canopy'] = 0;

    const { rerender, container } = render(<WebhookManagement repositoryId="bedroom" />);

    // Immediately switch repository before bedroom responses arrive
    rerender(<WebhookManagement repositoryId="canopy" />);

    // Wait for canopy config data to appear in the DOM
    await waitFor(() => {
      expect(screen.getByText('/canopy/root')).toBeTruthy();
    }, { timeout: 3000 });

    // Wait for bedroom delayed responses to potentially arrive
    await delay(500);

    // DOM must show canopy data, not bedroom data
    expect(screen.getByText('/canopy/root')).toBeTruthy();
    expect(screen.queryByText('/bedroom/root')).toBeNull();

    // Verify canopy's webhook URL is rendered, bedroom's is not
    expect(container.innerHTML).toContain('hook.example.com/canopy');
    expect(container.innerHTML).not.toContain('hook.example.com/bedroom');

    // Switch to Delivery Logs tab and verify deliveries show canopy, not bedroom.
    // deliveryId is rendered as first 8 chars: "del-cano" for canopy, "del-bedr" for bedroom.
    const deliveryLogsTab = screen.getByText('webhookManagement.tabs.deliveryLogs');
    fireEvent.click(deliveryLogsTab);

    await waitFor(() => {
      expect(screen.getByText('del-cano...')).toBeTruthy();
    }, { timeout: 3000 });

    expect(screen.queryByText('del-bedr...')).toBeNull();
  });

  it('loads correct data for each repository on sequential switches', async () => {
    responseDelay = {}; // no artificial delay

    const { rerender } = render(<WebhookManagement repositoryId="bedroom" />);

    // Wait for bedroom config data to appear in DOM
    await waitFor(() => {
      expect(screen.getByText('/bedroom/root')).toBeTruthy();
    }, { timeout: 3000 });

    fetchCallLog = [];

    // Switch to canopy
    rerender(<WebhookManagement repositoryId="canopy" />);

    // Wait for canopy config data to replace bedroom data in DOM
    await waitFor(() => {
      expect(screen.getByText('/canopy/root')).toBeTruthy();
    }, { timeout: 3000 });

    // Bedroom data must no longer be in the DOM
    expect(screen.queryByText('/bedroom/root')).toBeNull();

    // Only canopy calls should appear (no bedroom re-fetches)
    const bedroomCalls = fetchCallLog.filter(url => url.includes('/repo/bedroom/'));
    expect(bedroomCalls.length).toBe(0);
  });
});

import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, waitFor } from '@testing-library/react';
import i18n from '../../i18n';
import { WebhookConfigTab } from './WebhookConfigTab';

// The server stores a webhook's event names without checking them and returns them as stored,
// so the tab must cope with a name its label table does not list — including one an object
// inherits ("toString"): looked up naively it finds Object.prototype's function, which handed to
// the real i18next makes it throw while rendering and takes the whole page down. The tab shows
// such a name as itself. Real i18next (not a stub) because the failure is i18next's.
vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({ authToken: null }),
}));

class Boundary extends React.Component<{ onError: (e: Error) => void; children: React.ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  componentDidCatch(error: Error) {
    this.props.onError(error);
  }
  render() {
    return this.state.failed ? null : this.props.children;
  }
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('WebhookConfigTab event labels', () => {
  it('shows a listed event by its label and an unlisted one, even an inherited name, as itself', async () => {
    await i18n.changeLanguage('en');
    const config = {
      id: 'c1', url: 'https://example.com/hook', events: ['created', 'toString'],
      authType: null, includeChildren: false, retryCount: 3, enabled: true,
    };
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ status: 'success', webhookConfigs: [config] }), { status: 200 })));

    const errors: Error[] = [];
    render(
      <Boundary onError={(e) => errors.push(e)}>
        <WebhookConfigTab repositoryId="bedroom" objectId="folder-1" />
      </Boundary>,
    );

    // Wait until the table has rendered or the boundary has caught an error, then say which.
    await waitFor(() => expect(errors.length > 0 || screen.queryByText('Created') !== null).toBe(true));
    expect(errors.map((e) => e.message)).toEqual([]);
    expect(screen.getByText('Created')).toBeTruthy();
    expect(screen.getByText('toString')).toBeTruthy();
  });
});

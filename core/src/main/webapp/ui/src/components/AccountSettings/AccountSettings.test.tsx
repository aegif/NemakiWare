import { describe, it, expect, vi, afterEach, afterAll } from 'vitest';
import { render, screen, cleanup, fireEvent, act } from '@testing-library/react';
import { ConfigProvider, message } from 'antd';
import i18n from '../../i18n';
import { AccountSettings } from './AccountSettings';

// A refused password change is shown inside the translated envelope with the server's text as the
// detail. CMISService.changePassword throws handleHttpError's answer as it is — a plain object
// `{ status, statusText, url, message }`, not an Error — so this is the shape the screen gets.
// Real i18next (not a stub), so the lead and the envelope are the shipped texts.
vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({
    authToken: { username: 'u1', isAdmin: false, allowedAuthMethods: null },
    handleAuthError: vi.fn(),
  }),
}));
vi.mock('../../services/cmis', () => ({
  CMISService: vi.fn().mockImplementation(function () {
    return {
      getCurrentUser: vi.fn().mockResolvedValue({
        userId: 'u1', userName: 'u1', isAdmin: false, groups: [], allowedAuthMethods: null,
      }),
      changePassword: vi.fn().mockRejectedValue({
        status: 400, statusText: 'Bad Request', url: '', message: 'HTTP 400: Bad Request',
      }),
    };
  }),
}));
vi.mock('../../services/passwordPolicy', () => ({
  getPasswordPolicy: vi.fn().mockResolvedValue({ minLength: 0 }),
}));
vi.mock('../PasskeyManagement/PasskeyManagement', () => ({ default: () => null }));
vi.mock('../ApiKeyManagement/ApiKeyManagement', () => ({ ApiKeyManagement: () => null }));

// antd's message notices must not outlive a test. A notice leaves through @rc-component/motion,
// stepped on requestAnimationFrame and finished by transitionend, which jsdom never fires — so a
// "destroyed" notice stayed mounted (1, then 2 — measured with the assertion below while destroy()
// was only wrapped in act). Read from the code (useNoticeTimer), a mounted notice's duration timer
// updates React on every frame, outside any act — work that can run after the file's jsdom is torn
// down: "ReferenceError: window is not defined" from react-dom, an unhandled error that failed the
// run with every test green (CI on 8f0a7c2b9; 1 run in 4 locally). With motion off, destroy()
// unmounts the notices inside act, and neither the notices nor their timers outlive the hook.
ConfigProvider.config({
  holderRender: (children) => <ConfigProvider theme={{ token: { motion: false } }}>{children}</ConfigProvider>,
});
// The setting is global; vitest isolates test files by default, and this puts it back regardless.
afterAll(() => {
  ConfigProvider.config({ holderRender: undefined });
});

afterEach(async () => {
  cleanup();
  await act(async () => {
    message.destroy();
  });
  expect(document.querySelectorAll('.ant-message-notice').length,
    'a message notice outlived the test; its timer keeps updating React after the hook').toBe(0);
});

describe('AccountSettings refused password change', () => {
  for (const [lang, tab, placeholders, button, expected] of [
    ['ja', /パスワード$/, ['現在のパスワードを入力', '新しいパスワードを入力', '新しいパスワードを再入力'], /パスワードを変更/,
      'パスワードの変更に失敗しました（詳細: HTTP 400: Bad Request）'],
    ['en', /Password$/, ['Enter current password', 'Enter new password', 'Re-enter new password'], /Change Password/,
      'Failed to change password (details: HTTP 400: Bad Request)'],
  ] as const) {
    it(`leads with the translated text and keeps the server's text as a detail (${lang})`, async () => {
      await i18n.changeLanguage(lang);
      render(<AccountSettings repositoryId="bedroom" />);
      fireEvent.click(await screen.findByRole('tab', { name: tab }));
      const [current, next, confirm] = placeholders;
      fireEvent.change(await screen.findByPlaceholderText(current), { target: { value: 'old-pass-1' } });
      fireEvent.change(screen.getByPlaceholderText(next), { target: { value: 'New-pass-1234' } });
      fireEvent.change(screen.getByPlaceholderText(confirm), { target: { value: 'New-pass-1234' } });
      fireEvent.click(screen.getByRole('button', { name: button }));
      const shown = await screen.findByText(expected);
      expect(shown).toBeInTheDocument();
      // The afterEach count is only worth something if its selector finds a real notice in this
      // very state (motion off): a renamed class would leave it counting nothing, green.
      expect(shown.closest('.ant-message-notice')).not.toBeNull();
    });
  }
});

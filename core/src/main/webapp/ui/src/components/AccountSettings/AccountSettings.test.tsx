import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent, act } from '@testing-library/react';
import { message } from 'antd';
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

// message.destroy() schedules a React update of antd's message holder. Outside act it went to the
// scheduler and could run after the file's jsdom was torn down — "ReferenceError: window is not
// defined" from react-dom, an unhandled error that fails the run with every test green (CI on
// 8f0a7c2b9, and 1 run in 4 locally). Inside act the update is flushed before the hook returns.
afterEach(async () => {
  cleanup();
  await act(async () => {
    message.destroy();
  });
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
      expect(await screen.findByText(expected)).toBeInTheDocument();
    });
  }
});

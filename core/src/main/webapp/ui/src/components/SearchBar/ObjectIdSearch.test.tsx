import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import i18n from '../../i18n';
import { ObjectIdSearch } from './ObjectIdSearch';

// A server error during an ID search is shown inside the translated envelope: the lead in the UI
// language, the server's own (English) text after it as a detail — never the server's text alone.
// Real i18next (not a stub), so the lead and the envelope are the shipped texts.
vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({ handleAuthError: vi.fn() }),
}));
vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
}));
vi.mock('../../services/cmis', () => ({
  CMISService: vi.fn().mockImplementation(function () {
    return {
      getObject: vi.fn().mockRejectedValue(Object.assign(new Error('Internal Server Error'), { status: 500 })),
      getObjectParents: vi.fn(),
      getChildren: vi.fn(),
    };
  }),
}));

afterEach(() => {
  cleanup();
});

describe('ObjectIdSearch server error', () => {
  for (const [lang, placeholder, expected] of [
    ['ja', 'オブジェクトIDを入力', 'ID検索に失敗しました（詳細: Internal Server Error）'],
    ['en', 'Enter object ID', 'The ID search failed (details: Internal Server Error)'],
  ] as const) {
    it(`leads with the translated text and adds the server's text as a detail (${lang})`, async () => {
      await i18n.changeLanguage(lang);
      render(<ObjectIdSearch repositoryId="bedroom" />);
      const input = screen.getByPlaceholderText(placeholder);
      fireEvent.change(input, { target: { value: 'some-object-id' } });
      fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', keyCode: 13 });
      expect(await screen.findByRole('alert')).toHaveTextContent(expected);
    });
  }
});

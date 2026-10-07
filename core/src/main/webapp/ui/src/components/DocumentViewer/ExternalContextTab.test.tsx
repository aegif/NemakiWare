import { describe, it, expect, afterEach, afterAll } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import i18n from '../../i18n';
import { ExternalContextTab } from './ExternalContextTab';

// The JSON card's tag gives the context's length. Its text is a plural family, so the count the
// tab passes must be a number: a string (what toLocaleString() returns) selects no form, and
// i18next shows the key instead. Real i18next and the shipped locale files.
afterEach(() => cleanup());
afterAll(async () => {
  await i18n.changeLanguage('ja');
});

describe('ExternalContextTab character count', () => {
  it.each([
    ['en', '1', '1 char'],
    ['en', '[1]', '3 chars'],
    ['ja', '1', '1 文字'],
    ['ja', '[1]', '3 文字'],
  ])('in %s, a context of %s shows %s', async (lng, context, shown) => {
    await i18n.changeLanguage(lng);
    render(<ExternalContextTab context={context} sourceType={null} sourceId={null} updatedAt={null} />);
    expect(screen.getByText(shown)).toBeInTheDocument();
  });
});

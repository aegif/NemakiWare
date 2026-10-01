import { describe, it, expect, afterAll } from 'vitest';
import i18n from './index';
import { withDetail, detailOf } from './withDetail';

// The envelope: a translated lead first, the server's English text after it as a detail.
describe('withDetail', () => {
  afterAll(async () => {
    await i18n.changeLanguage('ja');
  });

  it('puts the server text after the translated lead, in the UI language', async () => {
    await i18n.changeLanguage('ja');
    expect(withDetail('保存に失敗しました', new Error('Index mismatch detected'))).toBe('保存に失敗しました（詳細: Index mismatch detected）');
    await i18n.changeLanguage('en');
    expect(withDetail('Save failed', 'Index mismatch detected')).toBe('Save failed (details: Index mismatch detected)');
  });

  it('shows the lead alone when there is no text to add', () => {
    expect(withDetail('Save failed', undefined)).toBe('Save failed');
    expect(withDetail('Save failed', '  ')).toBe('Save failed');
    expect(withDetail('Save failed', { status: 500 })).toBe('Save failed');
    expect(detailOf(null)).toBeUndefined();
  });
});

import { describe, it, expect, afterAll } from 'vitest';
import i18n from './index';

// Count-dependent texts are i18next plural families (`key_one` / `key_other`): English takes the
// form for the count, Japanese has the one form for every count. Real i18next and the shipped
// locale files. That each call site passes count is checked by tests/i18n/locale-keys.spec.ts.
describe('count-dependent texts', () => {
  afterAll(async () => {
    await i18n.changeLanguage('ja');
  });

  it('take the singular for one and the plural otherwise in English', async () => {
    await i18n.changeLanguage('en');
    expect(i18n.t('connectorGovernance.groupSummary', { name: 'G', count: 1 })).toBe('G (1 member)');
    expect(i18n.t('connectorGovernance.groupSummary', { name: 'G', count: 3 })).toBe('G (3 members)');
    expect(i18n.t('common.totalItems', { total: 1, count: 1 })).toBe('Total 1 item');
    expect(i18n.t('common.totalItems', { total: 0, count: 0 })).toBe('Total 0 items');
    expect(i18n.t('importProfileManagement.autoDisabledBanner', { count: 1 }))
      .toBe('1 profile was auto-disabled by the scheduler. Review the reason before re-enabling.');
    expect(i18n.t('importProfileManagement.autoDisabledBanner', { count: 2 }))
      .toBe('2 profiles were auto-disabled by the scheduler. Review the reason before re-enabling.');
  });

  it('read the same for every count in Japanese', async () => {
    await i18n.changeLanguage('ja');
    expect(i18n.t('connectorGovernance.groupSummary', { name: 'G', count: 1 })).toBe('G（1 名）');
    expect(i18n.t('connectorGovernance.groupSummary', { name: 'G', count: 3 })).toBe('G（3 名）');
    expect(i18n.t('common.totalItems', { total: 1, count: 1 })).toBe(i18n.t('common.totalItems', { total: 1, count: 2 }));
  });
});

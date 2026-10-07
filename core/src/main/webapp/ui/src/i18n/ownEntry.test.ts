import { describe, it, expect } from 'vitest';
import { ownEntry } from './ownEntry';

// A value from the server is looked up in a label table; only the table's own entries count.
describe('ownEntry', () => {
  const table: Record<string, string> = { created: 'documentViewer.webhooks.eventTypes.created' };

  it('finds an entry the table lists', () => {
    expect(ownEntry(table, 'created')).toBe('documentViewer.webhooks.eventTypes.created');
  });

  it('finds nothing for a name an object inherits, nor for an absent value', () => {
    for (const inherited of ['toString', 'valueOf', 'hasOwnProperty', 'constructor', '__proto__']) {
      expect(ownEntry(table, inherited)).toBeUndefined();
    }
    expect(ownEntry(table, 'unknown')).toBeUndefined();
    expect(ownEntry(table, undefined)).toBeUndefined();
    expect(ownEntry(table, null)).toBeUndefined();
  });
});

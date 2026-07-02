import { describe, it, expect } from 'vitest';
import { resolveRelativeCmisPath } from './MarkdownPreview';

describe('resolveRelativeCmisPath', () => {
  const base = '/Sites/docs';

  it('resolves a same-folder filename', () => {
    expect(resolveRelativeCmisPath(base, 'foo.png')).toBe('/Sites/docs/foo.png');
  });

  it('resolves an explicit ./ prefix', () => {
    expect(resolveRelativeCmisPath(base, './foo.png')).toBe('/Sites/docs/foo.png');
  });

  it('resolves a subfolder reference', () => {
    expect(resolveRelativeCmisPath(base, 'images/foo.png')).toBe('/Sites/docs/images/foo.png');
  });

  it('resolves a single parent reference', () => {
    expect(resolveRelativeCmisPath(base, '../assets/a.png')).toBe('/Sites/assets/a.png');
  });

  it('resolves multiple parent references', () => {
    expect(resolveRelativeCmisPath(base, '../../shared/img/a.png')).toBe('/shared/img/a.png');
  });

  it('treats a leading slash as repository-root absolute', () => {
    expect(resolveRelativeCmisPath(base, '/global/logo.png')).toBe('/global/logo.png');
  });

  it('strips query and hash', () => {
    expect(resolveRelativeCmisPath(base, 'foo.png?v=2#frag')).toBe('/Sites/docs/foo.png');
  });

  it('decodes percent-encoded segments', () => {
    expect(resolveRelativeCmisPath(base, 'my%20image.png')).toBe('/Sites/docs/my image.png');
  });

  it('does not walk above the repository root', () => {
    // more ..'s than depth collapses to root, then the filename
    expect(resolveRelativeCmisPath('/a', '../../../x.png')).toBe('/x.png');
  });

  it('returns null for an empty reference', () => {
    expect(resolveRelativeCmisPath(base, '')).toBeNull();
    expect(resolveRelativeCmisPath(base, '   ')).toBeNull();
  });

  it('returns null when the reference resolves to nothing (bare ..)', () => {
    expect(resolveRelativeCmisPath('/a', '..')).toBeNull();
  });
});

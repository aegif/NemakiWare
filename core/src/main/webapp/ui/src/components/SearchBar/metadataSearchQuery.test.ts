import { describe, expect, it } from 'vitest';
import type { PropertyDefinition } from '../../types/cmis';
import { buildMetadataSearchQuery } from './metadataSearchQuery';

const contractNo = { id: 'nemaki:contractNo', propertyType: 'string' } as PropertyDefinition;
const reviewNote = { id: 'nemaki:reviewNote', propertyType: 'string' } as PropertyDefinition;

describe('buildMetadataSearchQuery', () => {
  it('queries FROM the selected type, so the properties it declares can be resolved (closing batch 2 review)', () => {
    // FROM the base type named a property cmis:document does not have; the server refuses that since R188, and
    // every custom-property search failed.
    expect(
      buildMetadataSearchQuery(
        { objectType: 'nemaki:contract', baseType: 'cmis:document', 'customProp_nemaki:contractNo': '1' },
        [contractNo],
        [],
      ),
    ).toBe("SELECT * FROM nemaki:contract WHERE cmis:objectTypeId = 'nemaki:contract' AND nemaki:contractNo LIKE '%1%'");
  });

  it('queries FROM the base type when no object type is selected', () => {
    expect(buildMetadataSearchQuery({ baseType: 'cmis:folder', name: 'a' }, [], [])).toBe(
      "SELECT * FROM cmis:folder WHERE cmis:name LIKE '%a%'",
    );
    expect(buildMetadataSearchQuery({}, [], [])).toBe('SELECT * FROM cmis:document');
  });

  it('keeps the secondary type and its properties as conditions', () => {
    expect(
      buildMetadataSearchQuery(
        { secondaryType: 'nemaki:reviewable', 'secondaryProp_nemaki:reviewNote': 'ok' },
        [],
        [reviewNote],
      ),
    ).toBe(
      "SELECT * FROM cmis:document WHERE ANY cmis:secondaryObjectTypeIds IN ('nemaki:reviewable') AND nemaki:reviewNote LIKE '%ok%'",
    );
  });
});

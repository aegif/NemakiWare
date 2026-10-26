import { describe, expect, it } from 'vitest';
import type { PropertyDefinition } from '../../types/cmis';
import { buildMetadataSearchQuery } from './metadataSearchQuery';

const contractNo = { id: 'nemaki:contractNo', propertyType: 'string', cardinality: 'single' } as PropertyDefinition;
const reviewNote = { id: 'nemaki:reviewNote', propertyType: 'string', cardinality: 'single' } as PropertyDefinition;

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

  it('names the type and its properties by their QUERY names, and the type id only as a value (closing batch 3 review)', () => {
    // A type whose id is not its query name: FROM <id> is a type the query parser does not know.
    const renamed = { ...contractNo, queryName: 'acme:number' };
    expect(
      buildMetadataSearchQuery(
        { objectType: 'acme:contract-v1', baseType: 'cmis:document', 'customProp_nemaki:contractNo': '1' },
        [renamed],
        [],
        'acme:contract',
      ),
    ).toBe("SELECT * FROM acme:contract WHERE cmis:objectTypeId = 'acme:contract-v1' AND acme:number LIKE '%1%'");
  });

  it('queries FROM the base type when no object type is selected', () => {
    expect(buildMetadataSearchQuery({ baseType: 'cmis:folder', name: 'a' }, [], [])).toBe(
      "SELECT * FROM cmis:folder WHERE cmis:name LIKE '%a%'",
    );
    expect(buildMetadataSearchQuery({}, [], [])).toBe('SELECT * FROM cmis:document');
  });

  it('asks a secondary type property alone — the property implies the type (closing batch 3 review)', () => {
    // Sent together with ANY cmis:secondaryObjectTypeIds IN (...), the server refused every such search.
    expect(
      buildMetadataSearchQuery(
        { secondaryType: 'nemaki:reviewable', 'secondaryProp_nemaki:reviewNote': 'ok' },
        [],
        [reviewNote],
      ),
    ).toBe("SELECT * FROM cmis:document WHERE nemaki:reviewNote LIKE '%ok%'");
    // The secondary type alone still filters by the type.
    expect(buildMetadataSearchQuery({ secondaryType: 'nemaki:reviewable' }, [], [reviewNote])).toBe(
      "SELECT * FROM cmis:document WHERE ANY cmis:secondaryObjectTypeIds IN ('nemaki:reviewable')",
    );
  });

  it('asks a multi-valued property with ANY ... IN, not LIKE (closing batch 3 review)', () => {
    const comment = { id: 'nemaki:comment', queryName: 'comment', propertyType: 'string', cardinality: 'multi' } as PropertyDefinition;
    expect(
      buildMetadataSearchQuery(
        { secondaryType: 'nemaki:commentable', 'secondaryProp_nemaki:comment': 'ok' },
        [],
        [comment],
      ),
    ).toBe("SELECT * FROM cmis:document WHERE ANY nemaki:comment IN ('ok')");
  });
});

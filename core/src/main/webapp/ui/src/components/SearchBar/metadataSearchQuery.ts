import type { PropertyDefinition } from '../../types/cmis';

/**
 * A value as the inside of a CMIS string literal: a backslash and a quote escaped with a backslash, the way CMIS writes
 * them. The server reads them that way since closing batch 3, so a value with a backslash was searched without it and
 * one with a quote broke the statement (closing batch 4 review, subagent P3-3).
 */
export function cmisString(value: unknown): string {
  return String(value).replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

/** A value matched as itself inside a LIKE pattern: its % and _ are characters, not wildcards. */
export function cmisLikeLiteral(value: unknown): string {
  return cmisString(value).replace(/%/g, '\\%').replace(/_/g, '\\_');
}

/**
 * The CMIS query the full-text search sends. Not cmis:contentStreamFileName: the search index does not hold the file
 * name, the server refuses a query on it, and the condition never matched anything (closing batch 4 review, subagent
 * P3-4).
 */
export function buildFullTextQuery(keyword: string, excludeMetadata: boolean, baseType?: string): string {
  const fromType = baseType || 'cmis:document';
  if (excludeMetadata) {
    return `SELECT * FROM ${fromType} WHERE CONTAINS('${cmisString(keyword)}')`;
  }
  const like = cmisLikeLiteral(keyword);
  const conditions = [
    `CONTAINS('${cmisString(keyword)}')`,
    `cmis:name LIKE '%${like}%'`,
    `cmis:description LIKE '%${like}%'`,
  ];
  // cmis:checkinComment is a document-only property
  if (fromType === 'cmis:document') {
    conditions.push(`cmis:checkinComment LIKE '%${like}%'`);
  }
  return `SELECT * FROM ${fromType} WHERE ${conditions.join(' OR ')}`;
}

/**
 * One condition on a property the form carries a value for, or null when it carries none.
 *
 * `name` is what the statement calls the property. A multi-valued property is asked with CMIS's `ANY … IN`: `=` and
 * LIKE are for single values, and the server refuses them on a multi-valued property the way its query parser does
 * (closing batch 3 review). That makes a multi-valued string an exact match, not a part of the value.
 */
function conditionFor(propDef: PropertyDefinition, name: string, value: any): string | null {
  if (value === undefined || value === null || value === '') {
    return null;
  }
  if (propDef.cardinality === 'multi') {
    switch (propDef.propertyType) {
      case 'integer':
      case 'decimal':
      case 'boolean':
        return `ANY ${name} IN (${value})`;
      case 'datetime':
        return `ANY ${name} IN (TIMESTAMP '${value.toISOString()}')`;
      default:
        return `ANY ${name} IN ('${cmisString(value)}')`;
    }
  }
  switch (propDef.propertyType) {
    case 'string':
      // LIKE for string properties, to match part of the value
      return `${name} LIKE '%${cmisLikeLiteral(value)}%'`;
    case 'integer':
    case 'decimal':
    case 'boolean':
      return `${name} = ${value}`;
    case 'datetime':
      return `${name} = TIMESTAMP '${value.toISOString()}'`;
    default:
      return `${name} LIKE '%${cmisLikeLiteral(value)}%'`;
  }
}

/**
 * The CMIS query the advanced (metadata) search sends.
 *
 * FROM the selected object type when one is selected — not its base type — and by the name a query uses for it, its
 * QUERY NAME, which is not always its id (closing batch 3 review, Codex P2). A property the selected type declares is
 * resolved against the type in FROM, so `FROM cmis:document WHERE cmis:objectTypeId = 'nemaki:contract' AND
 * nemaki:contractNo LIKE …` names a property cmis:document does not have: the server could not translate it and, since
 * it stopped answering an untranslatable WHERE clause as every document (R188), refuses it — every custom-property
 * search failed (closing batch 2 review, subagent P2). The objectTypeId condition stays, with the type's ID, so the
 * search is still for that exact type and not its subtypes.
 *
 * A SECONDARY type's properties are named by their query name when it is a prefixed name, and by their id otherwise:
 * the server answers them through a reader that resolves a prefixed name, not through the query parser, and that
 * reader answers ONE condition. So when one of them is asked,
 * the secondary type itself is not added as a second condition — a document has those properties only while it
 * carries the type — and a secondary-type property asked together with any other condition is refused by the server
 * (closing batch 3 review, subagent P2-3: the two conditions were always sent together, so every such search was
 * refused).
 */
export function buildMetadataSearchQuery(
  values: Record<string, any>,
  typeProperties: PropertyDefinition[],
  secondaryTypeProperties: PropertyDefinition[],
  objectTypeQueryName?: string,
): string {
  const conditions: string[] = [];

  if (values.objectType) {
    conditions.push(`cmis:objectTypeId = '${cmisString(values.objectType)}'`);
  }
  if (values.name) {
    conditions.push(`cmis:name LIKE '%${cmisLikeLiteral(values.name)}%'`);
  }
  if (values.createdFrom) {
    conditions.push(`cmis:creationDate >= TIMESTAMP '${values.createdFrom.toISOString()}'`);
  }
  if (values.createdTo) {
    conditions.push(`cmis:creationDate <= TIMESTAMP '${values.createdTo.toISOString()}'`);
  }
  if (values.createdBy) {
    conditions.push(`cmis:createdBy = '${cmisString(values.createdBy)}'`);
  }
  typeProperties.forEach((propDef) => {
    const condition = conditionFor(propDef, propDef.queryName || propDef.id, values[`customProp_${propDef.id}`]);
    if (condition) {
      conditions.push(condition);
    }
  });
  const secondaryConditions: string[] = [];
  secondaryTypeProperties.forEach((propDef) => {
    // Its query name when that is a prefixed name the server's reader resolves (`acme:review` for an id
    // `acme:review-id`); its id when the query name is a bare word that reader does not see as a property name —
    // `comment` for `nemaki:comment` (closing batch 4 review, Codex P2).
    const name = propDef.queryName && propDef.queryName.includes(':') ? propDef.queryName : propDef.id;
    const condition = conditionFor(propDef, name, values[`secondaryProp_${propDef.id}`]);
    if (condition) {
      secondaryConditions.push(condition);
    }
  });
  // Secondary type filter — only when none of its properties is asked, which already implies it.
  if (values.secondaryType && secondaryConditions.length === 0) {
    conditions.push(`ANY cmis:secondaryObjectTypeIds IN ('${cmisString(values.secondaryType)}')`);
  }
  conditions.push(...secondaryConditions);

  const fromType = values.objectType
    ? objectTypeQueryName || values.objectType
    : values.baseType || 'cmis:document';
  let query = `SELECT * FROM ${fromType}`;
  if (conditions.length > 0) {
    query += ` WHERE ${conditions.join(' AND ')}`;
  }
  return query;
}

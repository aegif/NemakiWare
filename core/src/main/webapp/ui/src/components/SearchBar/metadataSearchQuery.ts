import type { PropertyDefinition } from '../../types/cmis';

/** One condition on a property the form carries a value for, or null when it carries none. */
function conditionFor(propDef: PropertyDefinition, value: any): string | null {
  if (value === undefined || value === null || value === '') {
    return null;
  }
  const propId = propDef.id;
  switch (propDef.propertyType) {
    case 'string':
      // LIKE for string properties, to match part of the value
      return `${propId} LIKE '%${value}%'`;
    case 'integer':
    case 'decimal':
    case 'boolean':
      return `${propId} = ${value}`;
    case 'datetime':
      return `${propId} = TIMESTAMP '${value.toISOString()}'`;
    default:
      return `${propId} LIKE '%${value}%'`;
  }
}

/**
 * The CMIS query the advanced (metadata) search sends.
 *
 * FROM the selected object type when one is selected — not its base type. A property the selected type declares is
 * resolved against the type in FROM, so `FROM cmis:document WHERE cmis:objectTypeId = 'nemaki:contract' AND
 * nemaki:contractNo LIKE …` names a property cmis:document does not have: the server could not translate it and, since
 * it stopped answering an untranslatable WHERE clause as every document (R188), refuses it — every custom-property
 * search failed (closing batch 2 review, subagent P2). The objectTypeId condition stays, so the search is still for
 * that exact type and not its subtypes.
 */
export function buildMetadataSearchQuery(
  values: Record<string, any>,
  typeProperties: PropertyDefinition[],
  secondaryTypeProperties: PropertyDefinition[],
): string {
  const conditions: string[] = [];

  if (values.objectType) {
    conditions.push(`cmis:objectTypeId = '${values.objectType}'`);
  }
  if (values.name) {
    conditions.push(`cmis:name LIKE '%${values.name}%'`);
  }
  if (values.createdFrom) {
    conditions.push(`cmis:creationDate >= TIMESTAMP '${values.createdFrom.toISOString()}'`);
  }
  if (values.createdTo) {
    conditions.push(`cmis:creationDate <= TIMESTAMP '${values.createdTo.toISOString()}'`);
  }
  if (values.createdBy) {
    conditions.push(`cmis:createdBy = '${values.createdBy}'`);
  }
  // Secondary type filter
  if (values.secondaryType) {
    conditions.push(`ANY cmis:secondaryObjectTypeIds IN ('${values.secondaryType}')`);
  }
  typeProperties.forEach((propDef) => {
    const condition = conditionFor(propDef, values[`customProp_${propDef.id}`]);
    if (condition) {
      conditions.push(condition);
    }
  });
  secondaryTypeProperties.forEach((propDef) => {
    const condition = conditionFor(propDef, values[`secondaryProp_${propDef.id}`]);
    if (condition) {
      conditions.push(condition);
    }
  });

  const fromType = values.objectType || values.baseType || 'cmis:document';
  let query = `SELECT * FROM ${fromType}`;
  if (conditions.length > 0) {
    query += ` WHERE ${conditions.join(' AND ')}`;
  }
  return query;
}

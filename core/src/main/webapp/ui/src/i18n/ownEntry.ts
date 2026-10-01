/**
 * The entry a lookup table holds for a value, or undefined when the table does not list it.
 * Only the table's own entries count: a value such as "toString" must not find the member it
 * inherits from Object.prototype. Handed to t(), most of those members (toString, valueOf,
 * hasOwnProperty, …) make i18next 26 throw while rendering; constructor and __proto__ give an empty
 * or a wrong label instead.
 * The label tables that map a value to a literal i18n key go through this whenever the value
 * comes from outside the code (the server, an uploaded type definition).
 */
export function ownEntry<T>(table: Record<string, T>, value: string | null | undefined): T | undefined {
  return value != null && Object.prototype.hasOwnProperty.call(table, value) ? table[value] : undefined;
}

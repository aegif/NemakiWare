/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.cmis.aspect.query.solr;

import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.server.support.query.CmisQlStrictLexer;

/**
 * Validator for CMIS literal types in comparison operations.
 *
 * This utility ensures that the literal type used in CMIS query comparisons
 * is compatible with the property type being compared.
 *
 * Type compatibility matrix:
 * <ul>
 *   <li>STRING, HTML, URI, ID properties: STRING_LIT only</li>
 *   <li>INTEGER properties: NUM_LIT only</li>
 *   <li>DECIMAL properties: NUM_LIT only</li>
 *   <li>BOOLEAN properties: BOOL_LIT only</li>
 *   <li>DATETIME properties: TIME_LIT only</li>
 * </ul>
 *
 * @see CmisQlStrictLexer
 * @see PropertyType
 */
public final class LiteralTypeValidator {

    /** Private constructor to prevent instantiation */
    private LiteralTypeValidator() {
    }

    /**
     * Validates that a literal type is compatible with a property type.
     *
     * @param propertyType the CMIS property type
     * @param literalType the CMIS literal type (from CmisQlStrictLexer)
     * @param propertyName the property name (for error messages)
     * @throws IllegalArgumentException if propertyType is null
     * @throws IllegalStateException if the literal type is not compatible with the property type
     */
    public static void validate(PropertyType propertyType, int literalType, String propertyName) {
        if (propertyType == null) {
            throw new IllegalArgumentException("Property type cannot be null for property: " + propertyName);
        }

        String literalTypeName = getLiteralTypeName(literalType);

        switch (propertyType) {
            case STRING:
            case HTML:
            case URI:
            case ID:
                if (literalType != CmisQlStrictLexer.STRING_LIT) {
                    throw new IllegalStateException(
                            literalTypeName + " is not compatible with " + propertyType +
                            " property '" + propertyName + "'. Expected String literal.");
                }
                break;

            case INTEGER:
                if (literalType != CmisQlStrictLexer.NUM_LIT) {
                    throw new IllegalStateException(
                            literalTypeName + " is not compatible with INTEGER property '" +
                            propertyName + "'. Expected Number literal.");
                }
                break;

            case DECIMAL:
                if (literalType != CmisQlStrictLexer.NUM_LIT) {
                    throw new IllegalStateException(
                            literalTypeName + " is not compatible with DECIMAL property '" +
                            propertyName + "'. Expected Number literal.");
                }
                break;

            case BOOLEAN:
                if (literalType != CmisQlStrictLexer.BOOL_LIT) {
                    throw new IllegalStateException(
                            literalTypeName + " is not compatible with BOOLEAN property '" +
                            propertyName + "'. Expected Boolean literal (TRUE/FALSE).");
                }
                break;

            case DATETIME:
                if (literalType != CmisQlStrictLexer.TIME_LIT) {
                    throw new IllegalStateException(
                            literalTypeName + " is not compatible with DATETIME property '" +
                            propertyName + "'. Expected Timestamp literal (TIMESTAMP '...').");
                }
                break;

            default:
                // For any other property types (future extensions), allow all literals
                // This provides forward compatibility
                break;
        }
    }

    /**
     * Gets a human-readable name for a literal type constant.
     *
     * @param literalType the literal type constant from CmisQlStrictLexer
     * @return the human-readable name, or a descriptive string for unknown types
     */
    private static String getLiteralTypeName(int literalType) {
        switch (literalType) {
            case CmisQlStrictLexer.STRING_LIT:
                return "String literal";
            case CmisQlStrictLexer.NUM_LIT:
                return "Number literal";
            case CmisQlStrictLexer.BOOL_LIT:
                return "Boolean literal";
            case CmisQlStrictLexer.TIME_LIT:
                return "Timestamp literal";
            default:
                return "Unknown literal (type=" + literalType + ")";
        }
    }

    /**
     * Checks if a node type represents a known CMIS literal type.
     *
     * @param nodeType the node type from Tree.getType()
     * @return true if this is a literal type (STRING_LIT, NUM_LIT, BOOL_LIT, TIME_LIT)
     */
    public static boolean isLiteralType(int nodeType) {
        return nodeType == CmisQlStrictLexer.STRING_LIT ||
               nodeType == CmisQlStrictLexer.NUM_LIT ||
               nodeType == CmisQlStrictLexer.BOOL_LIT ||
               nodeType == CmisQlStrictLexer.TIME_LIT;
    }

    /**
     * Checks if a literal type is compatible with a property type without throwing exceptions.
     *
     * @param propertyType the CMIS property type
     * @param literalType the CMIS literal type (from CmisQlStrictLexer)
     * @return true if compatible, false otherwise
     */
    public static boolean isCompatible(PropertyType propertyType, int literalType) {
        if (propertyType == null) {
            return false;
        }

        switch (propertyType) {
            case STRING:
            case HTML:
            case URI:
            case ID:
                return literalType == CmisQlStrictLexer.STRING_LIT;

            case INTEGER:
            case DECIMAL:
                return literalType == CmisQlStrictLexer.NUM_LIT;

            case BOOLEAN:
                return literalType == CmisQlStrictLexer.BOOL_LIT;

            case DATETIME:
                return literalType == CmisQlStrictLexer.TIME_LIT;

            default:
                // For unknown property types, accept any literal
                return true;
        }
    }
}

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
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for LiteralTypeValidator.
 *
 * Tests CMIS literal type validation for comparison operations.
 * Each property type has specific literal types that are valid for comparison.
 *
 * Type compatibility matrix:
 * - STRING, HTML, URI, ID properties: STRING_LIT only
 * - INTEGER properties: NUM_LIT only
 * - DECIMAL properties: NUM_LIT only
 * - BOOLEAN properties: BOOL_LIT only
 * - DATETIME properties: TIME_LIT only
 */
public class LiteralTypeValidatorTest {

    // ========== Valid Type Combinations ==========

    @Test
    public void testStringPropertyWithStringLiteral_ShouldBeValid() {
        // cmis:name = 'test'
        assertValid(PropertyType.STRING, CmisQlStrictLexer.STRING_LIT, "cmis:name");
    }

    @Test
    public void testHtmlPropertyWithStringLiteral_ShouldBeValid() {
        // html property = 'test'
        assertValid(PropertyType.HTML, CmisQlStrictLexer.STRING_LIT, "custom:htmlField");
    }

    @Test
    public void testUriPropertyWithStringLiteral_ShouldBeValid() {
        // uri property = 'http://example.com'
        assertValid(PropertyType.URI, CmisQlStrictLexer.STRING_LIT, "custom:uriField");
    }

    @Test
    public void testIdPropertyWithStringLiteral_ShouldBeValid() {
        // cmis:objectId = 'abc123'
        assertValid(PropertyType.ID, CmisQlStrictLexer.STRING_LIT, "cmis:objectId");
    }

    @Test
    public void testIntegerPropertyWithNumLiteral_ShouldBeValid() {
        // cmis:contentStreamLength > 100
        assertValid(PropertyType.INTEGER, CmisQlStrictLexer.NUM_LIT, "cmis:contentStreamLength");
    }

    @Test
    public void testDecimalPropertyWithNumLiteral_ShouldBeValid() {
        // custom:decimalField = 3.14
        assertValid(PropertyType.DECIMAL, CmisQlStrictLexer.NUM_LIT, "custom:decimalField");
    }

    @Test
    public void testBooleanPropertyWithBoolLiteral_ShouldBeValid() {
        // cmis:isLatestVersion = TRUE
        assertValid(PropertyType.BOOLEAN, CmisQlStrictLexer.BOOL_LIT, "cmis:isLatestVersion");
    }

    @Test
    public void testDatetimePropertyWithTimeLiteral_ShouldBeValid() {
        // cmis:creationDate > TIMESTAMP '2025-01-01T00:00:00.000Z'
        assertValid(PropertyType.DATETIME, CmisQlStrictLexer.TIME_LIT, "cmis:creationDate");
    }

    // ========== Invalid Type Combinations - String property ==========

    @Test
    public void testStringPropertyWithNumLiteral_ShouldBeInvalid() {
        // cmis:name = 123 (invalid)
        assertInvalid(PropertyType.STRING, CmisQlStrictLexer.NUM_LIT, "cmis:name",
                "Number literal is not compatible with STRING property");
    }

    @Test
    public void testStringPropertyWithBoolLiteral_ShouldBeInvalid() {
        // cmis:name = TRUE (invalid)
        assertInvalid(PropertyType.STRING, CmisQlStrictLexer.BOOL_LIT, "cmis:name",
                "Boolean literal is not compatible with STRING property");
    }

    @Test
    public void testStringPropertyWithTimeLiteral_ShouldBeInvalid() {
        // cmis:name = TIMESTAMP '...' (invalid)
        assertInvalid(PropertyType.STRING, CmisQlStrictLexer.TIME_LIT, "cmis:name",
                "Timestamp literal is not compatible with STRING property");
    }

    // ========== Invalid Type Combinations - Integer property ==========

    @Test
    public void testIntegerPropertyWithStringLiteral_ShouldBeInvalid() {
        // cmis:contentStreamLength = 'abc' (invalid)
        assertInvalid(PropertyType.INTEGER, CmisQlStrictLexer.STRING_LIT, "cmis:contentStreamLength",
                "String literal is not compatible with INTEGER property");
    }

    @Test
    public void testIntegerPropertyWithBoolLiteral_ShouldBeInvalid() {
        // cmis:contentStreamLength = TRUE (invalid)
        assertInvalid(PropertyType.INTEGER, CmisQlStrictLexer.BOOL_LIT, "cmis:contentStreamLength",
                "Boolean literal is not compatible with INTEGER property");
    }

    @Test
    public void testIntegerPropertyWithTimeLiteral_ShouldBeInvalid() {
        // cmis:contentStreamLength = TIMESTAMP '...' (invalid)
        assertInvalid(PropertyType.INTEGER, CmisQlStrictLexer.TIME_LIT, "cmis:contentStreamLength",
                "Timestamp literal is not compatible with INTEGER property");
    }

    // ========== Invalid Type Combinations - Decimal property ==========

    @Test
    public void testDecimalPropertyWithStringLiteral_ShouldBeInvalid() {
        assertInvalid(PropertyType.DECIMAL, CmisQlStrictLexer.STRING_LIT, "custom:decimalField",
                "String literal is not compatible with DECIMAL property");
    }

    @Test
    public void testDecimalPropertyWithBoolLiteral_ShouldBeInvalid() {
        assertInvalid(PropertyType.DECIMAL, CmisQlStrictLexer.BOOL_LIT, "custom:decimalField",
                "Boolean literal is not compatible with DECIMAL property");
    }

    @Test
    public void testDecimalPropertyWithTimeLiteral_ShouldBeInvalid() {
        assertInvalid(PropertyType.DECIMAL, CmisQlStrictLexer.TIME_LIT, "custom:decimalField",
                "Timestamp literal is not compatible with DECIMAL property");
    }

    // ========== Invalid Type Combinations - Boolean property ==========

    @Test
    public void testBooleanPropertyWithStringLiteral_ShouldBeInvalid() {
        // cmis:isLatestVersion = 'true' (invalid - must use BOOL_LIT TRUE)
        assertInvalid(PropertyType.BOOLEAN, CmisQlStrictLexer.STRING_LIT, "cmis:isLatestVersion",
                "String literal is not compatible with BOOLEAN property");
    }

    @Test
    public void testBooleanPropertyWithNumLiteral_ShouldBeInvalid() {
        // cmis:isLatestVersion = 1 (invalid)
        assertInvalid(PropertyType.BOOLEAN, CmisQlStrictLexer.NUM_LIT, "cmis:isLatestVersion",
                "Number literal is not compatible with BOOLEAN property");
    }

    @Test
    public void testBooleanPropertyWithTimeLiteral_ShouldBeInvalid() {
        assertInvalid(PropertyType.BOOLEAN, CmisQlStrictLexer.TIME_LIT, "cmis:isLatestVersion",
                "Timestamp literal is not compatible with BOOLEAN property");
    }

    // ========== Invalid Type Combinations - DateTime property ==========

    @Test
    public void testDatetimePropertyWithStringLiteral_ShouldBeInvalid() {
        // cmis:creationDate = '2025-01-01' (invalid - must use TIMESTAMP keyword)
        assertInvalid(PropertyType.DATETIME, CmisQlStrictLexer.STRING_LIT, "cmis:creationDate",
                "String literal is not compatible with DATETIME property");
    }

    @Test
    public void testDatetimePropertyWithNumLiteral_ShouldBeInvalid() {
        // cmis:creationDate = 123456789 (invalid)
        assertInvalid(PropertyType.DATETIME, CmisQlStrictLexer.NUM_LIT, "cmis:creationDate",
                "Number literal is not compatible with DATETIME property");
    }

    @Test
    public void testDatetimePropertyWithBoolLiteral_ShouldBeInvalid() {
        assertInvalid(PropertyType.DATETIME, CmisQlStrictLexer.BOOL_LIT, "cmis:creationDate",
                "Boolean literal is not compatible with DATETIME property");
    }

    // ========== Invalid Type Combinations - ID property ==========

    @Test
    public void testIdPropertyWithNumLiteral_ShouldBeInvalid() {
        // cmis:objectId = 123 (invalid)
        assertInvalid(PropertyType.ID, CmisQlStrictLexer.NUM_LIT, "cmis:objectId",
                "Number literal is not compatible with ID property");
    }

    @Test
    public void testIdPropertyWithBoolLiteral_ShouldBeInvalid() {
        assertInvalid(PropertyType.ID, CmisQlStrictLexer.BOOL_LIT, "cmis:objectId",
                "Boolean literal is not compatible with ID property");
    }

    @Test
    public void testIdPropertyWithTimeLiteral_ShouldBeInvalid() {
        assertInvalid(PropertyType.ID, CmisQlStrictLexer.TIME_LIT, "cmis:objectId",
                "Timestamp literal is not compatible with ID property");
    }

    // ========== Invalid Type Combinations - HTML/URI properties ==========

    @Test
    public void testHtmlPropertyWithNumLiteral_ShouldBeInvalid() {
        assertInvalid(PropertyType.HTML, CmisQlStrictLexer.NUM_LIT, "custom:htmlField",
                "Number literal is not compatible with HTML property");
    }

    @Test
    public void testUriPropertyWithBoolLiteral_ShouldBeInvalid() {
        assertInvalid(PropertyType.URI, CmisQlStrictLexer.BOOL_LIT, "custom:uriField",
                "Boolean literal is not compatible with URI property");
    }

    // ========== Edge Cases ==========

    @Test
    public void testNullPropertyType_ShouldThrowException() {
        try {
            LiteralTypeValidator.validate(null, CmisQlStrictLexer.STRING_LIT, "unknown");
            fail("Expected IllegalArgumentException for null property type");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention null",
                    e.getMessage().contains("null") || e.getMessage().contains("Property type"));
        }
    }

    @Test
    public void testUnknownLiteralType_ShouldReportIncompatible() {
        // Use an invalid literal type (99999)
        // Since getLiteralTypeName now returns "Unknown literal (type=99999)" instead of throwing,
        // the validation should still fail with an informative message
        try {
            LiteralTypeValidator.validate(PropertyType.STRING, 99999, "cmis:name");
            fail("Expected IllegalStateException for unknown literal type");
        } catch (IllegalStateException e) {
            assertTrue("Error message should mention unknown literal type or incompatibility",
                    e.getMessage().contains("Unknown") || e.getMessage().contains("not compatible"));
        }
    }

    // ========== isCompatible Tests ==========

    @Test
    public void testIsCompatible_StringWithStringLit_ShouldBeTrue() {
        assertTrue(LiteralTypeValidator.isCompatible(PropertyType.STRING, CmisQlStrictLexer.STRING_LIT));
    }

    @Test
    public void testIsCompatible_StringWithNumLit_ShouldBeFalse() {
        assertFalse(LiteralTypeValidator.isCompatible(PropertyType.STRING, CmisQlStrictLexer.NUM_LIT));
    }

    @Test
    public void testIsCompatible_IntegerWithNumLit_ShouldBeTrue() {
        assertTrue(LiteralTypeValidator.isCompatible(PropertyType.INTEGER, CmisQlStrictLexer.NUM_LIT));
    }

    @Test
    public void testIsCompatible_BooleanWithBoolLit_ShouldBeTrue() {
        assertTrue(LiteralTypeValidator.isCompatible(PropertyType.BOOLEAN, CmisQlStrictLexer.BOOL_LIT));
    }

    @Test
    public void testIsCompatible_DatetimeWithTimeLit_ShouldBeTrue() {
        assertTrue(LiteralTypeValidator.isCompatible(PropertyType.DATETIME, CmisQlStrictLexer.TIME_LIT));
    }

    @Test
    public void testIsCompatible_NullPropertyType_ShouldBeFalse() {
        assertFalse(LiteralTypeValidator.isCompatible(null, CmisQlStrictLexer.STRING_LIT));
    }

    // ========== isLiteralType Tests ==========

    @Test
    public void testIsLiteralType_StringLit_ShouldBeTrue() {
        assertTrue(LiteralTypeValidator.isLiteralType(CmisQlStrictLexer.STRING_LIT));
    }

    @Test
    public void testIsLiteralType_NumLit_ShouldBeTrue() {
        assertTrue(LiteralTypeValidator.isLiteralType(CmisQlStrictLexer.NUM_LIT));
    }

    @Test
    public void testIsLiteralType_BoolLit_ShouldBeTrue() {
        assertTrue(LiteralTypeValidator.isLiteralType(CmisQlStrictLexer.BOOL_LIT));
    }

    @Test
    public void testIsLiteralType_TimeLit_ShouldBeTrue() {
        assertTrue(LiteralTypeValidator.isLiteralType(CmisQlStrictLexer.TIME_LIT));
    }

    @Test
    public void testIsLiteralType_UnknownType_ShouldBeFalse() {
        assertFalse(LiteralTypeValidator.isLiteralType(99999));
    }

    @Test
    public void testIsLiteralType_ZeroType_ShouldBeFalse() {
        assertFalse(LiteralTypeValidator.isLiteralType(0));
    }

    // ========== Helper Methods ==========

    private void assertValid(PropertyType propertyType, int literalType, String propertyName) {
        try {
            LiteralTypeValidator.validate(propertyType, literalType, propertyName);
            // No exception means valid
        } catch (IllegalStateException e) {
            fail("Expected valid type combination for " + propertyName +
                 " (" + propertyType + ") with literal type " + literalType +
                 ", but got: " + e.getMessage());
        }
    }

    private void assertInvalid(PropertyType propertyType, int literalType, String propertyName,
                               String expectedMessagePart) {
        try {
            LiteralTypeValidator.validate(propertyType, literalType, propertyName);
            fail("Expected IllegalStateException for invalid type combination: " +
                 propertyName + " (" + propertyType + ") with literal type " + literalType);
        } catch (IllegalStateException e) {
            // Expected exception - verify message contains useful information
            String message = e.getMessage();
            assertTrue("Error message should mention property name, got: " + message,
                    message.contains(propertyName) || message.contains(propertyType.toString()));
        }
    }
}

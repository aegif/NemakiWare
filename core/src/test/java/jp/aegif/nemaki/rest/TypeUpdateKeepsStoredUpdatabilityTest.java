/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.util.constant.CallContextKey;

import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A type update must not rewrite an updatability the request did not mention.
 *
 * <h2>Why this test drives the endpoint rather than a parser</h2>
 *
 * <p>The first attempt at this guard was put in {@code parseJsonPropertyDefinitions}, and it
 * protected nothing: both parsers {@code continue} past a property that already exists, so an
 * evidence property never reaches them. The single place a STORED updatability is rewritten is
 * the copy in {@code updateTypeDefinition}, and it is reached only when the parse-time lookup
 * and the loop's own lookup disagree — that is, when the property-core view was silent a moment
 * earlier and answers now (external review).
 *
 * <p>So the fake below makes exactly that happen: {@code getPropertyDefinitionCoreByPropertyId}
 * answers null once and the core afterwards. Reverting the guard makes these tests fail; guarding
 * the parsers again does not make them pass.
 */
class TypeUpdateKeepsStoredUpdatabilityTest {

    private static final String REPO = "bedroom";
    private static final String TYPE_ID = "nemaki:chatContextMetadata";
    private static final String PROPERTY_ID = "nemaki:chatChannelId";

    private TypeService typeService;
    private NemakiPropertyDefinitionDetail stored;
    private TypeResource resource;

    @BeforeEach
    void setUp() {
        typeService = mock(TypeService.class);

        NemakiTypeDefinition existingType = new NemakiTypeDefinition();
        existingType.setTypeId(TYPE_ID);
        existingType.setId("type-node-id");
        when(typeService.getTypeDefinition(REPO, TYPE_ID)).thenReturn(existingType);

        NemakiPropertyDefinitionCore core = new NemakiPropertyDefinitionCore();
        core.setId("core-node-id");
        core.setPropertyId(PROPERTY_ID);
        core.setPropertyType(PropertyType.STRING);
        core.setCardinality(Cardinality.SINGLE);
        core.setQueryName(PROPERTY_ID);

        stored = new NemakiPropertyDefinitionDetail();
        stored.setId("detail-node-id");
        // What the read-only migration left behind.
        stored.setUpdatability(Updatability.READONLY);

        // Silent first — which is what makes the parser treat the property as new — then present.
        when(typeService.getPropertyDefinitionCoreByPropertyId(REPO, PROPERTY_ID))
                .thenReturn(null).thenReturn(core);
        when(typeService.getPropertyDefinitionDetailByCoreNodeId(REPO, "core-node-id"))
                .thenReturn(List.of(stored));
        when(typeService.updatePropertyDefinitionDetail(anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(1));

        resource = new TypeResource();
        resource.setTypeService(typeService);
        resource.setTypeManager(mock(TypeManager.class));
    }

    private static HttpServletRequest adminRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(callContext.getRepositoryId()).thenReturn(REPO);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");
        return request;
    }

    /** The array form is what the admin UI sends. {@code updatability} is deliberately absent. */
    private static String payload(String updatabilityOrNull) {
        String updatability = updatabilityOrNull == null
                ? "" : "\"updatability\": \"" + updatabilityOrNull + "\", ";
        return "{"
                + "\"id\": \"" + TYPE_ID + "\","
                + "\"localName\": \"chatContextMetadata\","
                + "\"queryName\": \"" + TYPE_ID + "\","
                + "\"displayName\": \"Chat context\","
                + "\"baseId\": \"cmis:secondary\","
                + "\"parentId\": \"cmis:secondary\","
                + "\"creatable\": true, \"queryable\": true, \"fulltextIndexed\": false,"
                + "\"includedInSupertypeQuery\": true,"
                + "\"controllablePolicy\": false, \"controllableACL\": false,"
                + "\"propertyDefinitions\": [{"
                + "  \"id\": \"" + PROPERTY_ID + "\","
                + "  \"localName\": \"chatChannelId\","
                + "  \"queryName\": \"" + PROPERTY_ID + "\","
                + "  \"propertyType\": \"string\","
                + "  \"cardinality\": \"single\","
                + "  " + updatability
                + "  \"required\": false, \"queryable\": true"
                + "}]}";
    }

    @Test
    @DisplayName("an update that does not mention updatability leaves the stored READONLY alone")
    void omissionDoesNotRewrite() {
        Response response = resource.update(REPO, TYPE_ID, payload(null), adminRequest());

        assertEquals(200, response.getStatus(), "the update itself should still succeed");
        // Without this the test is vacuously green: if a future change made the parser see the
        // property as existing, the loop body would never run and `stored` would be untouched
        // for the wrong reason (external review).
        verify(typeService).updatePropertyDefinitionDetail(REPO, stored);
        assertNotNull(stored.getUpdatability(),
                "the stored updatability was erased by an update that never mentioned it");
        assertEquals(Updatability.READONLY, stored.getUpdatability(),
                "a type update with no updatability field unprotected an evidence property");
    }

    @Test
    @DisplayName("an update that DOES mention it still changes it")
    void anExplicitValueIsStillApplied() {
        // The guard must not turn into "updatability can never be changed through the API".
        Response response = resource.update(REPO, TYPE_ID, payload("readwrite"), adminRequest());

        assertEquals(200, response.getStatus());
        assertEquals(Updatability.READWRITE, stored.getUpdatability(),
                "an explicit updatability was ignored, so the field became uneditable");
    }

    @Test
    @DisplayName("whencheckedout is a CMIS value and is accepted")
    void whenCheckedOutIsNotRejected() {
        // The hand-written if/else chain this replaced knew three of the four CMIS values, so
        // the first version of the "unknown value" rejection turned a legitimate one into a 500.
        Response response = resource.update(REPO, TYPE_ID, payload("whencheckedout"), adminRequest());

        assertEquals(200, response.getStatus(),
                "whencheckedout was rejected even though CMIS defines it");
        assertEquals(Updatability.WHENCHECKEDOUT, stored.getUpdatability());
    }

    @Test
    @DisplayName("a misspelt value is refused rather than widened")
    void aTypoIsRefused() {
        Response response = resource.update(REPO, TYPE_ID, payload("read-write"), adminRequest());

        assertNotEquals(200, response.getStatus(), "a misspelt updatability was accepted");
        assertEquals(Updatability.READONLY, stored.getUpdatability(),
                "a misspelt updatability changed the stored one");
    }
}

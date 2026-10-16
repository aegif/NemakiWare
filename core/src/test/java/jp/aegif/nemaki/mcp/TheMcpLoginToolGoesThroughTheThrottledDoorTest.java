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
package jp.aegif.nemaki.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.cmis.factory.auth.ApiKeyService;
import jp.aegif.nemaki.config.ObjectMapperFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * The MCP tool a client actually calls — {@code nemakiware_login} — is answered by the throttled
 * door (3.4.1, round-5 review).
 *
 * <p>The throttle's behaviour is measured on {@code McpAuthenticationHandler.login} in
 * {@code McpAuthenticationHandlerTest}. One reviewer pointed out that this leaves the wiring
 * unmeasured: the public entry is {@code McpToolsProvider.executeLoginTool}, and if that were ever
 * pointed at another password check, both the handler's tests and the source-reading locks would stay
 * green while guessing over MCP went back to being unlimited.
 *
 * <p>So this drives the tool entry: fifteen wrong passwords through {@code executeLoginTool}, and then
 * the RIGHT password through it, which must be refused. Nothing but a throttle on that path can
 * produce that answer.
 */
class TheMcpLoginToolGoesThroughTheThrottledDoorTest {

    private static final String REPO = "bedroom";
    private static final String USER = "alice";
    private static final String PASSWORD = "the-real-one";

    private McpToolsProvider tools;

    @BeforeEach
    void setUp() {
        PrincipalService principalService = mock(PrincipalService.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        ContentService contentService = mock(ContentService.class);

        User alice = mock(User.class);
        when(alice.getUserId()).thenReturn(USER);
        when(alice.getPasswordHash()).thenReturn(BCrypt.hashpw(PASSWORD, BCrypt.gensalt(4)));
        when(principalService.getUserById(REPO, USER)).thenReturn(alice);
        // The account names its own DOCUMENT: since R170 a session is opened only for an account it
        // can be bound to, so a bare mock (getId() == null) would make the login fail here for a
        // reason that has nothing to do with the throttle this lock measures (3.4.1 batch 19).
        jp.aegif.nemaki.model.UserItem account = mock(jp.aegif.nemaki.model.UserItem.class);
        when(account.getId()).thenReturn(USER + "-doc");
        when(contentService.getUserItemById(anyString(), anyString())).thenReturn(account);

        McpAuthenticationHandler authHandler =
                new McpAuthenticationHandler(principalService, apiKeyService, 3600);
        authHandler.setContentService(contentService);

        // Through the factory: the bare constructor adopts Jackson 3 defaults and the migration
        // boundary lock bans it — including, as measured, when it appears in a comment.
        ObjectMapper objectMapper = ObjectMapperFactory.createDefaultObjectMapper();
        tools = new McpToolsProvider(authHandler, null, contentService, null, null, null, null,
                new McpToolResultFactory(objectMapper), objectMapper,
                "http://localhost:8080/core", REPO);
    }

    private McpToolResult login(String password) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("repositoryId", REPO);
        arguments.put("username", USER);
        arguments.put("password", password);
        return tools.executeLoginTool(arguments);
    }

    @Test
    @DisplayName("fifteen wrong passwords through the tool refuse the right one afterwards")
    void theToolEntryIsThrottled() {
        // Discriminating first: the right password opens a session when nothing has been counted.
        assertFalse(login(PASSWORD).isError(), "the tool refused a correct password with no failures"
                + " recorded, so the refusal below would prove nothing");

        for (int attempt = 0; attempt < 15; attempt++) {
            assertTrue(login("not-the-password").isError(),
                    "a wrong password was accepted on attempt " + attempt);
        }

        assertTrue(login(PASSWORD).isError(),
                "the tool accepted the correct password after fifteen failures, so the public MCP"
                        + " login does not reach the throttled door and guessing over MCP is unlimited");
    }
}

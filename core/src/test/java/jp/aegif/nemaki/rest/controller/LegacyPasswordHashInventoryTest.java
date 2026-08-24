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
package jp.aegif.nemaki.rest.controller;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.util.constant.CallContextKey;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Naming the accounts that MD5 removal will break, while the path still works (roadmap §2-5).
 *
 * <p>{@code passwordMatchesWithUpgrade} migrates an account to BCrypt the first time it
 * authenticates, so the population at risk is exactly the accounts that have NOT signed in since
 * that shipped — service accounts, dormant users, anything driven by a stored credential nobody
 * rotates. Those cannot be found by waiting for a failure: the failure arrives after the
 * upgrade, all at once.
 */
class LegacyPasswordHashInventoryTest {

    private static final String MD5 = "5f4dcc3b5aa765d61d8327deb882cf99";
    private static final String BCRYPT = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private PrincipalService principals;
    private LegacyPasswordHashController controller;

    @BeforeEach
    void setUp() throws Exception {
        principals = mock(PrincipalService.class);
        RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
        when(infoMap.keys()).thenReturn(new java.util.LinkedHashSet<>(List.of("bedroom")));
        when(infoMap.isArchiveRepository("bedroom")).thenReturn(false);

        controller = new LegacyPasswordHashController();
        set("principalService", principals);
        set("repositoryInfoMap", infoMap);

        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        controller.setHttpRequest(request);
    }

    private void set(String field, Object value) throws Exception {
        Field f = LegacyPasswordHashController.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private static User user(String id, String hash) {
        User u = new User();
        u.setUserId(id);
        u.setPasswordHash(hash);
        return u;
    }

    @Test
    @DisplayName("an MD5 account is named; a BCrypt one is not")
    void namesOnlyTheLegacyAccounts() {
        when(principals.getUsers("bedroom"))
                .thenReturn(List.of(user("service-bot", MD5), user("ishii", BCRYPT)));

        Map<String, Object> body = controller.legacyPasswordHashes(null).getBody();

        assertEquals(1, body.get("legacyCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) body.get("accounts");
        assertEquals("service-bot", accounts.get(0).get("userId"),
                "the account that breaks at 3.5 was not named");
        assertEquals(2, body.get("scannedUsers"),
                "the scan must say how many it looked at — a count of 1 legacy out of an "
                        + "unknown total means nothing");
    }

    @Test
    @DisplayName("the report never carries the hash itself")
    void theHashIsNeverReturned() {
        // An inventory of weak credential material is worth more to an attacker than the list
        // of names, and the operator does not need it to act.
        when(principals.getUsers("bedroom")).thenReturn(List.of(user("service-bot", MD5)));

        assertFalse(controller.legacyPasswordHashes(null).getBody().toString().contains(MD5),
                "the report handed back the weak hash it exists to help retire");
    }

    @Test
    @DisplayName("a non-admin is refused")
    void nonAdminIsRefused() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        controller.setHttpRequest(request);

        ResponseEntity<Map<String, Object>> response = controller.legacyPasswordHashes(null);

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    @DisplayName("the MD5 test matches the verifier's, including its case rule")
    void theDetectionMatchesTheVerifier() {
        // The verifier tests length == 32 && [a-f0-9]{32}. An inventory using a looser rule
        // would name accounts that will keep working; a stricter one would miss accounts that
        // will break. Either way the operator acts on the wrong list.
        assertTrue(LegacyPasswordHashController.isLegacyMd5(MD5));
        assertFalse(LegacyPasswordHashController.isLegacyMd5(BCRYPT));
        assertFalse(LegacyPasswordHashController.isLegacyMd5(MD5.toUpperCase()),
                "upper-case hex is not what the verifier accepts, so it is not what breaks");
        assertFalse(LegacyPasswordHashController.isLegacyMd5(null));
        assertFalse(LegacyPasswordHashController.isLegacyMd5(MD5.substring(1)));
    }
}

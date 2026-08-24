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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which accounts still hold a legacy MD5 password hash (roadmap §2-5).
 *
 * <h2>Why this exists before the removal, not after</h2>
 *
 * <p>MD5 verification is deprecated in 3.4 and removed in 3.5. {@code
 * passwordMatchesWithUpgrade} migrates an account to BCrypt the first time it authenticates, so
 * the population at risk is precisely <b>the accounts that have not signed in since the upgrade
 * mechanism shipped</b> — service accounts, dormant users, anything driven by a stored
 * credential nobody rotates. An operator cannot find those by waiting for a failure: the
 * failure arrives after the upgrade, all at once.
 *
 * <p>So: name them while the path still works, and give the operator the two ways out (have
 * the user sign in once, or reset the password).
 *
 * <h2>What it does not do</h2>
 *
 * <p><b>It does not return the hashes.</b> An inventory of weak credential material is worth
 * more to an attacker than the list of names, and the operator does not need it to act.
 *
 * <p>It does not change anything. Rotating credentials on the operator's behalf is not a
 * side effect a GET should have, and "which accounts" is the question that was unanswerable.
 */
@RestController
@RequestMapping("/v1/admin/security")
public class LegacyPasswordHashController {

    private static final Logger logger =
            LoggerFactory.getLogger(LegacyPasswordHashController.class);

    @Autowired
    private PrincipalService principalService;

    @Autowired
    private RepositoryInfoMap repositoryInfoMap;

    private HttpServletRequest httpRequest;

    @Autowired
    public void setHttpRequest(HttpServletRequest httpRequest) {
        this.httpRequest = httpRequest;
    }

    /** A 32-hex-character stored hash is MD5 — the same test the verifier uses. */
    static boolean isLegacyMd5(String hash) {
        return hash != null && hash.length() == 32 && hash.matches("[a-f0-9]{32}");
    }

    @GetMapping("/legacy-password-hashes")
    public ResponseEntity<Map<String, Object>> legacyPasswordHashes(
            @RequestParam(required = false) String repositoryId) {

        Map<String, Object> body = new LinkedHashMap<>();
        if (!isAdmin()) {
            body.put("status", "error");
            body.put("message", "Admin access required");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }

        List<String> repositories = new ArrayList<>();
        if (repositoryId != null && !repositoryId.isBlank()) {
            repositories.add(repositoryId);
        } else if (repositoryInfoMap != null) {
            for (String id : repositoryInfoMap.keys()) {
                if (!repositoryInfoMap.isArchiveRepository(id)) {
                    repositories.add(id);
                }
            }
        }

        List<Map<String, Object>> findings = new ArrayList<>();
        int scanned = 0;
        try {
            for (String repo : repositories) {
                List<User> users = principalService.getUsers(repo);
                if (users == null) {
                    continue;
                }
                for (User user : users) {
                    scanned++;
                    if (isLegacyMd5(user.getPasswordHash())) {
                        Map<String, Object> finding = new LinkedHashMap<>();
                        finding.put("repositoryId", repo);
                        finding.put("userId", user.getUserId());
                        // The HASH is deliberately absent: an inventory of weak credential
                        // material is worth more to an attacker than the list of names, and
                        // the operator does not need it to act.
                        findings.add(finding);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("legacy-password-hash scan failed: {}", e.getMessage());
            body.put("status", "error");
            body.put("message", "the principal store could not be scanned: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }

        body.put("status", "success");
        body.put("scannedUsers", scanned);
        body.put("legacyCount", findings.size());
        body.put("accounts", findings);
        body.put("deprecation", "MD5 password verification is DEPRECATED in 3.4 and will be "
                + "REMOVED in 3.5. These accounts will stop authenticating then.");
        body.put("remedy", "Have each user sign in once (which upgrades the stored hash to "
                + "BCrypt automatically), or reset the password. Accounts that never sign in "
                + "interactively — service accounts and the like — need the reset.");
        return ResponseEntity.ok(body);
    }

    private boolean isAdmin() {
        if (httpRequest == null) {
            return false;
        }
        Object ctx = httpRequest.getAttribute("CallContext");
        return ctx instanceof CallContext callContext
                && Boolean.TRUE.equals(callContext.get(CallContextKey.IS_ADMIN));
    }
}

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
package jp.aegif.nemaki.businesslogic;

/**
 * The users no write path deletes or demotes, and the one place that decides it (R115).
 *
 * <p>The bulk principal batch (C-1) refused to delete or demote the built-in {@code admin}, the
 * service accounts (Solr's and {@code mcp-service}) and the acting user; the three existing user
 * endpoints — legacy {@code /rest}, Spring MVC {@code /api/v1/repo}, Jersey {@code /api/v1/cmis}
 * — had no such guard, so {@code admin} could be deleted one request at a time. Nor had CMIS
 * {@code deleteObject} on a user's item, or the Microsoft cloud sync's removal, which both used the
 * generic delete. The decision is here, and the batch's planner, {@code ContentService.deleteUser}
 * (which every user deletion now goes through) and the legacy endpoint's admin-flag change all
 * ask it.
 */
public final class ProtectedPrincipals {

    /** The built-in administrator the initial content creates. */
    public static final String ADMIN_USER_ID = "admin";
    /** The MCP transport's service account ({@code Patch_McpServiceAccount}). */
    public static final String MCP_SERVICE_USER_ID = "mcp-service";
    /** Solr's account when {@code solr.nemaki.userid} is not set. */
    public static final String DEFAULT_SOLR_USER_ID = "solr";

    /** Why a user may not be deleted or lose its admin flag. */
    public enum Reason {
        BUILT_IN_ADMIN,
        BUILT_IN_SERVICE_ACCOUNT,
        ACTOR_ITSELF
    }

    private ProtectedPrincipals() {
    }

    /**
     * Why {@code userId} may not be deleted or demoted by {@code actor}, or null when it may.
     *
     * @param actor the acting user, or null for a path that acts for nobody (directory sync) —
     *        then only the built-in accounts are refused
     */
    public static Reason reasonFor(String userId, String actor, String adminUserId, String solrUserId,
            String mcpServiceUserId) {
        if (userId == null) {
            return null;
        }
        if (userId.equals(adminUserId)) {
            return Reason.BUILT_IN_ADMIN;
        }
        if (userId.equals(solrUserId) || userId.equals(mcpServiceUserId)) {
            return Reason.BUILT_IN_SERVICE_ACCOUNT;
        }
        if (actor != null && userId.equals(actor)) {
            return Reason.ACTOR_ITSELF;
        }
        return null;
    }

    /** The same, with the deployment's ids: the constants, and Solr's from its setting. */
    public static Reason reasonFor(String userId, String actor, String configuredSolrUserId) {
        String solr = configuredSolrUserId == null || configuredSolrUserId.isBlank()
                ? DEFAULT_SOLR_USER_ID : configuredSolrUserId.trim();
        return reasonFor(userId, actor, ADMIN_USER_ID, solr, MCP_SERVICE_USER_ID);
    }

    /** A write refused because the user is protected. The message names the user and the reason only. */
    public static final class RefusedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Reason reason;

        public RefusedException(Reason reason, String userId, String what) {
            super("user '" + userId + "' is never " + what + ": " + describe(reason));
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }

        private static String describe(Reason reason) {
            return switch (reason) {
                case BUILT_IN_ADMIN -> "it is the built-in administrator";
                case BUILT_IN_SERVICE_ACCOUNT -> "it is a built-in service account";
                case ACTOR_ITSELF -> "it is the user making the request";
            };
        }
    }
}

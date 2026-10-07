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
package jp.aegif.nemaki.api.v1.principals;

/**
 * A request the batch refuses before or instead of writing: malformed input (400), too large
 * (413), a plan that is gone or no longer matches (409), or a store that could not answer (503).
 *
 * <p>The message is the product's own words about the request — a line number, a column name, a
 * reason from the registry — and is meant for the client. Nothing from an infrastructure
 * exception is put here; those go to the log under an incident id (the resource wraps them).
 */
public final class PrincipalBatchRequestException extends RuntimeException {

    private final int status;
    private final String reason;

    public PrincipalBatchRequestException(int status, String message) {
        this(status, null, message);
    }

    public PrincipalBatchRequestException(int status, String reason, String message) {
        super(message);
        this.status = status;
        this.reason = reason;
    }

    public int status() {
        return status;
    }

    /** A registry value ({@code PLAN_EXPIRED}, {@code SNAPSHOT_CHANGED} …) or null. */
    public String reason() {
        return reason;
    }
}

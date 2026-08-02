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
package jp.aegif.nemaki.rest.purview.journal;

/**
 * One target's §8-d replay request on a v2 journal row — the typed form of
 * {@code v2ReplayRequestsByTarget[target]} (D-rest-3, v2.3.20).
 *
 * <p>The record owns its {@code generation} and {@code requestId} from the moment the
 * REQUESTED CAS lands; both are immutable for the request's lifetime and are the fence every
 * later transition checks. {@code FAILED} is terminal and durable: the frozen expected set for
 * a NEW request is exactly {absent, ACKED}, so a failed request permanently blocks its target
 * pending an explicitly designed audited repair — the collision diagnosis is the only durable
 * record of an integrity violation and must not be overwritten.
 *
 * <p>All timestamps are epoch millis (numeric, exact-integral), like every v2 mutable field.
 */
public record LineageReplayRequest(
        State state,
        long generation,
        String requestId,
        long requestedAtMs,
        long updatedAtMs,
        LineageTargetLifecycle.TerminalReason reason
) {

    /** §8-d: REQUESTED → CREATED → ACKED, or → FAILED (terminal, durable). */
    public enum State { REQUESTED, CREATED, ACKED, FAILED }

    public LineageReplayRequest {
        if (state == null) {
            throw new IllegalArgumentException("replay request state must not be null");
        }
        if (generation < 1) {
            throw new IllegalArgumentException("replay generation must be >= 1, got "
                    + generation);
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("replay requestId must not be blank");
        }
        try {
            // The requestId is an ownership FENCE — the contract says UUID, and accepting
            // arbitrary strings would let a mangled field impersonate one.
            if (!java.util.UUID.fromString(requestId).toString().equals(requestId)) {
                throw new IllegalArgumentException("replay requestId must be a canonical"
                        + " UUID string, got '" + requestId + "'");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("replay requestId must be a canonical UUID"
                    + " string, got '" + requestId + "'");
        }
        if (requestedAtMs <= 0 || updatedAtMs <= 0) {
            throw new IllegalArgumentException("replay request timestamps must be positive");
        }
        boolean hasReason = reason != null;
        if (state == State.FAILED && !hasReason) {
            throw new IllegalArgumentException("a FAILED replay request requires its durable"
                    + " reason — the diagnosis is the point of the state");
        }
        if (state != State.FAILED && hasReason) {
            throw new IllegalArgumentException("only FAILED carries a reason, got one in "
                    + state);
        }
    }

    /** True while the request still owes work (the crash scanner's selection). */
    public boolean isUnacked() {
        return state == State.REQUESTED || state == State.CREATED;
    }
}

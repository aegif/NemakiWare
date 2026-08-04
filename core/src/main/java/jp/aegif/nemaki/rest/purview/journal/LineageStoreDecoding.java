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

import java.math.BigDecimal;

import com.ibm.cloud.sdk.core.service.exception.ServiceResponseException;

/**
 * How a stored value or a failure is read strictly. No IO, no state, no responsibility.
 *
 * <p>Both members are shared by more than one extracted store, and both are the kind of thing
 * that must have exactly one definition: a second "integral" or a second "too large" would be a
 * second contract wearing the same name. They are not on {@link LineageStoreSupport} because
 * that interface is the <em>storage</em> basis — a delegate holds an instance of it — and these
 * are pure functions that no instance owns.
 *
 * <p><b>Nothing here changes meaning.</b> Both bodies are the ones
 * {@link CouchLineageJournalStore} declared, moved unaltered.
 */
final class LineageStoreDecoding {

    private LineageStoreDecoding() {
    }

    /**
     * Exact integral conversion — Gson hands back {@code LazilyParsedNumber}; fractions fail.
     *
     * <p>Shared by the materialization codec and the sequencer. Both read numbers a remote wrote,
     * and a value that is "close enough to an integer" is a corrupt sequence or a corrupt
     * generation, not a value to round.
     */
    static long exactLong(Object value, String what) {
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException(what + " must be a number, got "
                    + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        try {
            return new BigDecimal(n.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be an exact integral value, got "
                    + n);
        }
    }

    /**
     * CouchDB's document-size verdict, classified strictly (v2.3.22 D1): 413, or a status whose
     * reason names {@code document_too_large}. Everything else is infrastructure.
     */
    static boolean isDocumentTooLarge(RuntimeException e) {
        // ONLY a response-carrying failure counts, and only 413 or a CouchDB error/reason of
        // document_too_large: a 503 (or any message that merely says "too large") is an
        // infrastructure failure and must propagate, never park a fact (F3).
        if (!(e instanceof ServiceResponseException sre)) {
            return false;
        }
        if (sre.getStatusCode() == 413) {
            return true;
        }
        if (sre.getStatusCode() != 400 && sre.getStatusCode() != 500) {
            return false;
        }
        Object reason = sre.getDebuggingInfo() == null ? null
                : sre.getDebuggingInfo().get("reason");
        Object error = sre.getDebuggingInfo() == null ? null
                : sre.getDebuggingInfo().get("error");
        return "document_too_large".equals(reason) || "document_too_large".equals(error);
    }
}

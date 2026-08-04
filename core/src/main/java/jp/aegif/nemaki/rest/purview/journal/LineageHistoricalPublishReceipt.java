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
 * What a historical publish actually did, in a form that can be stored and compared.
 *
 * <p>An enum was not enough. When a restore is detected after the write, a compensation request
 * has to say <em>which</em> write it is compensating for — and it must do so without carrying a
 * catalog response body or a qualified name, both of which identify and locate the object.
 *
 * @param operationDigest identifies this write, so a retry of the same publish does not create
 *        a second compensation task
 * @param readBackVerdict what the catalog said when read back afterwards; a publish that
 *        returned success is not evidence the entity is there
 * @param subjectDigest which endpoint this was, as a digest
 */
public record LineageHistoricalPublishReceipt(
        LineageHistoricalEntityPublisher.Outcome outcome,
        String target,
        String subjectDigest,
        String operationDigest,
        LineageCatalogEntityProbe.Presence readBackVerdict) {

    public LineageHistoricalPublishReceipt {
        if (outcome == null) {
            throw new IllegalArgumentException("a publish receipt needs an outcome");
        }
        if (outcome == LineageHistoricalEntityPublisher.Outcome.PUBLISHED) {
            // PUBLISHED is a claim that the entity is in the catalog. It may only be made with
            // a read-back that saw it and an operation digest that identifies the write.
            if (readBackVerdict != LineageCatalogEntityProbe.Presence.PRESENT) {
                throw new IllegalArgumentException(
                        "PUBLISHED requires a read-back that saw the entity");
            }
            if (operationDigest == null || operationDigest.isBlank()) {
                throw new IllegalArgumentException(
                        "PUBLISHED requires an operation digest, or a compensation could not"
                                + " name the write it is undoing");
            }
        }
    }

    public static LineageHistoricalPublishReceipt retryable(String target, String subjectDigest,
            LineageCatalogEntityProbe.Presence readBack) {
        return new LineageHistoricalPublishReceipt(
                LineageHistoricalEntityPublisher.Outcome.RETRYABLE, target, subjectDigest, null,
                readBack);
    }

    /** Digests only; no qualified name, no catalog body. */
    @Override
    public String toString() {
        return "LineageHistoricalPublishReceipt[" + outcome + " target=" + target
                + " readBack=" + readBackVerdict + "]";
    }
}

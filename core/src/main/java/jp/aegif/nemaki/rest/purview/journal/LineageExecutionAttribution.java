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
 * Who executed an operation and on whose authority — resolved ONCE, carried everywhere.
 *
 * <p>P1-1(e) D7's defect was two independent resolutions of the same question: the lineage
 * event refused to guess ({@code "unknown: delegated profile …"}) while the capture-intent row
 * recorded the raw context username — which for a delegated autonomous run is the profile
 * CREATOR, the authority-as-actor confusion the event side existed to stop. A typed pair,
 * resolved once and handed verbatim to both records, makes their agreement structural
 * (Codex H2/L2 — a generic map could be built with the pair missing, blank, or halved).
 *
 * @param executedBy required, non-blank: the actor, or the defined service-actor form for an
 *        autonomous run ({@code "scheduler: delegated profile p1, schedule configured by x"})
 * @param onBehalfOf the authority when it differs from the actor (a delegated profile's
 *        creator); null otherwise. Blank normalizes to null so absence has one representation.
 */
public record LineageExecutionAttribution(String executedBy, String onBehalfOf) {

    public LineageExecutionAttribution {
        if (executedBy == null || executedBy.isBlank()) {
            throw new IllegalArgumentException("executedBy must not be blank — an unknown actor"
                    + " is stated as a defined form, never as absence");
        }
        onBehalfOf = onBehalfOf == null || onBehalfOf.isBlank() ? null : onBehalfOf;
    }
}

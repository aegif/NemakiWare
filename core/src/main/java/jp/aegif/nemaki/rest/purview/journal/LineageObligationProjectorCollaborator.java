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

import java.util.List;

/**
 * What the projector uses to ask about obligations — the only door between the two.
 *
 * <p>Named rather than {@code Object} for the same reason as the scanner: readiness must be able
 * to establish that the projector and the scanner drive the <em>same</em> service. Two instances
 * would each look wired while resolving into different stores' worth of state.
 */
public interface LineageObligationProjectorCollaborator {

    /** The service this collaborator uses. Compared by identity; readiness never calls it. */
    LineageCatalogObligationService service();

    /**
     * Whether an endpoint may be projected now, or an obligation is owed.
     *
     * @return empty to proceed; otherwise the task key the event must wait on
     */
    java.util.Optional<String> requireCatalogEntity(String target, String repositoryId,
            EndpointKind kind, String catalogQualifiedName);

    /** What a waiting event's obligations collectively say. Four answers, never a boolean. */
    LineageCatalogObligationService.Verdict verdictFor(List<String> taskKeys);
}

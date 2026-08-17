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
import java.util.Optional;

/**
 * The projector's one door to the obligation machine.
 *
 * <p>A pass-through by design. Its value is not behaviour but type: readiness can establish that
 * the projector and the scanner drive the same service, which a bare reference could not show.
 */
public class LineageObligationProjectorCollaboratorImpl
        implements LineageObligationProjectorCollaborator {

    private final LineageCatalogObligationService service;

    public LineageObligationProjectorCollaboratorImpl(LineageCatalogObligationService service) {
        this.service = service;
    }

    @Override
    public LineageCatalogObligationService service() {
        return service;
    }

    @Override
    public Optional<String> requireCatalogEntity(String target, String repositoryId,
            EndpointKind kind, String catalogQualifiedName) {
        if (service == null) {
            return Optional.empty();
        }
        return service.requireCatalogEntity(target, repositoryId, kind, catalogQualifiedName);
    }

    @Override
    public boolean isDurable(String taskKey) {
        return service != null && service.isDurable(taskKey);
    }

    @Override
    public LineageCatalogObligationService.Verdict verdictFor(List<String> taskKeys) {
        if (service == null) {
            // No machine means nothing can be established — never a resume.
            return new LineageCatalogObligationService.Verdict(
                    LineageCatalogObligationService.VerdictKind.INDETERMINATE,
                    "the obligation machine is not wired", taskKeys == null ? 0 : taskKeys.size());
        }
        return service.verdictFor(taskKeys);
    }
}

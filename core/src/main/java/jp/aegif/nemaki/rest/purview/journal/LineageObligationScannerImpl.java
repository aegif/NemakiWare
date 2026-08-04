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
 * Drives obligations forward, one bounded pass at a time.
 *
 * <p>Holds no gate of its own. {@link LineageCatalogObligationService#runOnce} returns
 * {@link LineageCatalogObligationService.Pass#INERT} while D-rest is off or readiness is red,
 * and a second gate here would be one more thing to keep in step with the first.
 */
public class LineageObligationScannerImpl implements LineageObligationScanner {

    /** Obligations per pass. Bounded so a scheduled tick is a unit of work, not a drain. */
    static final int DEFAULT_PASS_LIMIT = 50;

    private final LineageCatalogObligationService service;

    public LineageObligationScannerImpl(LineageCatalogObligationService service) {
        this.service = service;
    }

    @Override
    public LineageCatalogObligationService service() {
        return service;
    }

    @Override
    public LineageCatalogObligationService.Pass runBoundedPass(int limit) {
        if (service == null) {
            return LineageCatalogObligationService.Pass.INERT;
        }
        return service.runOnce(limit <= 0 ? DEFAULT_PASS_LIMIT : limit);
    }
}

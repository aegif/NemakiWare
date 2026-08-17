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
 * The thing that drives obligations forward on a schedule.
 *
 * <p>A named contract rather than {@code Object}, because readiness has to check more than
 * "something is there": a scanner driving a <em>different</em> service instance than the one
 * readiness knows about would resolve obligations the projector never sees, and both halves
 * would look wired.
 *
 * <p>{@link #service()} is an identity accessor. It reads no gate and performs no work, so
 * readiness can compare instances without the readiness → service → readiness recursion.
 */
public interface LineageObligationScanner {

    /**
     * The service this scanner drives. Compared by identity, never called by readiness.
     */
    LineageCatalogObligationService service();

    /**
     * One bounded pass. Returns what it did, so a caller can tell "nothing to do" from
     * "did not run".
     *
     * <p>Gating is the service's, not the scanner's: a scanner that decided for itself whether
     * D-rest was on would be a second gate to keep in step with the first.
     */
    LineageCatalogObligationService.Pass runBoundedPass(int limit);
}

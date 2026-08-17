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

import java.util.Optional;

/**
 * v2.3.18 ④: the materializer's version binding, as a seam.
 *
 * <p>The D-rest default answers "unavailable" — with no resolved version there is no parent
 * decision and nothing is written (fail-closed). 4a supplies the real §6-a barrier
 * implementation (writeSchemaVersion + barrier generation from the rollout fence). A fact
 * whose VERIFIED parent decision already exists never consults the resolver again: the
 * decision froze the version and barrier generation, and rereading a live flag would weaken
 * crash convergence.
 */
public interface WriteVersionResolver {

    /** A resolved commitment: which schema version to materialize, under which barrier. */
    record ResolvedWrite(int materializeSchemaVersion, long barrierGeneration) {
        public ResolvedWrite {
            if (materializeSchemaVersion != 1 && materializeSchemaVersion != 2) {
                throw new IllegalArgumentException("materializeSchemaVersion must be 1 or 2");
            }
            if (barrierGeneration < 0) {
                throw new IllegalArgumentException("barrierGeneration must be >= 0");
            }
        }
    }

    /**
     * @return the resolved write commitment, or empty = unavailable (no decision is made;
     *         the fact stays in the spool untouched)
     */
    Optional<ResolvedWrite> resolve(LineageSpoolPayloadV1 payload);

    /** The D-rest default: unavailable until 4a's barrier implementation replaces it. */
    @org.springframework.stereotype.Component
    class Unavailable implements WriteVersionResolver {
        @Override
        public Optional<ResolvedWrite> resolve(LineageSpoolPayloadV1 payload) {
            return Optional.empty();
        }
    }
}

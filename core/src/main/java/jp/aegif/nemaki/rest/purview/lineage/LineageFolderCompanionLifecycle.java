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
package jp.aegif.nemaki.rest.purview.lineage;

import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.Content;

/**
 * A folder companion's life, from the folder's creation to long after its purge (増分 B, §3).
 *
 * <h2>The one rule everything else follows from</h2>
 *
 * <p><b>A companion is never deleted because its folder was.</b> Past lineage points at it, and
 * a Process whose input has vanished is strictly worse than one whose input is marked
 * {@code PURGED} — the first looks like a bug in the lineage, the second is the history it is
 * there to record. Deletion is only ever the retention path's business, and only for a companion
 * that is {@code PURGED} with no Process left referring to it.
 *
 * <h2>What changes and what does not</h2>
 *
 * <table border="1">
 * <caption>Folder events and the companion's response</caption>
 * <tr><th>event</th><th>companion</th></tr>
 * <tr><td>created</td><td>published in the same bulk as the folder, {@code ACTIVE}</td></tr>
 * <tr><td>renamed</td><td>{@code name} updated; the qualified name is id-derived, so unchanged</td></tr>
 * <tr><td>moved</td><td>unchanged — neither the name nor the identity depends on the parent</td></tr>
 * <tr><td>deleted (to archive)</td><td>kept; {@code active=false}, {@code sourceState=ARCHIVED}</td></tr>
 * <tr><td>restored</td><td>{@code active=true}, {@code sourceState=ACTIVE}</td></tr>
 * <tr><td>archive purged</td><td>kept; {@code active=false}, {@code sourceState=PURGED}</td></tr>
 * <tr><td>no record the folder ever existed</td><td>{@code sourceState=ORPHAN}, reported</td></tr>
 * </table>
 */
public interface LineageFolderCompanionLifecycle {

    /**
     * The companion entity for a folder, to publish in the <em>same bulk</em> as the folder.
     *
     * <p>Same bulk because §3 says a folder and its companion must not exist one without the
     * other; the response still has to be checked, since a bulk call can succeed partially.
     *
     * @return the entity payload, or {@code null} if {@code content} is not a folder
     */
    Map<String, Object> companionFor(String repositoryId, Content content);

    /**
     * Ties published companions to their folders, one relationship each.
     *
     * <p>Safe to repeat: the client turns the catalog's duplicate response into success, so a
     * retry after a partial batch converges rather than failing on what already succeeded.
     *
     * @return how many ties are in place, whether this call made them or found them
     */
    int tie(String repositoryId, List<Content> folders, Map<String, String> guidByQualifiedName);

    /**
     * Moves an existing companion to a lifecycle state, leaving its other attributes alone.
     *
     * <p>Read-merge-write rather than a blind publish: by the time a folder is archived its
     * {@code Content} is gone, and republishing from nothing would replace the recorded name
     * with a placeholder. What the companion said about the folder when the folder existed is
     * the only record left of it.
     *
     * <p>A companion that is not there is <b>not created</b>. Nothing here knows what the folder
     * was called, and a companion invented at purge time would assert a history that was never
     * observed.
     *
     * @return true if a companion was found and updated
     */
    boolean markState(String repositoryId, String objectId, String sourceState);
}

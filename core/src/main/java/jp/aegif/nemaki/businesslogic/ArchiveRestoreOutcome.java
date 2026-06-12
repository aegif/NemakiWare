/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - shared archive-restore outcome (#14 REST consolidation)
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic;

/**
 * Outcome of {@code ContentService.restoreArchiveGuarded}. Lets each REST
 * binding render the result in its own style (legacy JSON {@code errMsg} or an
 * api/v1 ProblemDetail) while sharing identical authorization, cold-storage and
 * parent-existence guards.
 */
public enum ArchiveRestoreOutcome {
    /** Restored successfully. */
    RESTORED,
    /** No such archive, or the caller is not allowed to see it (ID-enumeration safe). */
    NOT_FOUND,
    /** Archive is in cold storage and cannot be restored directly. */
    COLD_STORAGE,
    /** The original parent folder no longer exists. */
    PARENT_GONE
}

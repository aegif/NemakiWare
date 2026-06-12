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
 *     aegif - shared group-create validation reasons (#14 REST consolidation)
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic;

/**
 * Reasons a new-group creation request can be rejected. Returned as a list by
 * {@code ContentService.validateNewGroup} so each REST binding can render them
 * in its own style (legacy JSON {@code errMsg}, Spring MVC errors list, or an
 * api/v1 ProblemDetail) while sharing identical validation logic.
 */
public enum GroupValidation {
    ID_REQUIRED,
    NAME_REQUIRED,
    ALREADY_EXISTS
}

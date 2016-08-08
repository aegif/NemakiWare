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
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.cmis.service;

import java.util.List;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;

import jp.aegif.nemaki.util.spring.aspect.log.LogParam;

public interface VersioningService {

	public void checkOut(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("objectId") Holder<String> objectId, @LogParam("contentCopied") Holder<Boolean> contentCopied,
			@LogParam("extension") ExtensionsData extension);

	public void cancelCheckOut(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("objectId") String objectId, @LogParam("extension") ExtensionsData extension);

	public void checkIn(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("objectId") Holder<String> objectId, @LogParam("major") Boolean major, @LogParam("properties") Properties properties,
			@LogParam("contentStream") ContentStream contentStream, @LogParam("checkinComment") String checkinComment,
			@LogParam("policies") List<String> policies, @LogParam("addAces") Acl addAces, @LogParam("removeAces") Acl removeAces,
			@LogParam("extension") ExtensionsData extension);

	public List<ObjectData> getAllVersions(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("objectId") String objectId, @LogParam("versionSeriesId") String versionSeriesId, @LogParam("filter") String filter,
			@LogParam("includeAllowableActions") Boolean includeAllowableActions, @LogParam("extension") ExtensionsData extension);

	public ObjectData getObjectOfLatestVersion(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("objectId") String objectId, @LogParam("versionSeriesId") String versionSeriesId, @LogParam("major") Boolean major,
			@LogParam("filter") String filter, @LogParam("includeAllowableActions") Boolean includeAllowableActions,
			@LogParam("includeRelationships") IncludeRelationships includeRelationships, @LogParam("renditionFilter") String renditionFilter,
			@LogParam("includePolicyIds") Boolean includePolicyIds, @LogParam("includeAcl") Boolean includeAcl,
			@LogParam("extension") ExtensionsData extension);
}
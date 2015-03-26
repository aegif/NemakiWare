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

public interface VersioningService {

	public void checkOut(CallContext callContext, Holder<String> objectId,
			ExtensionsData extension, Holder<Boolean> contentCopied);

	public void cancelCheckOut(CallContext callContext, String objectId,ExtensionsData extension);
	
	public void checkIn(CallContext callContext, Holder<String> objectId,
			Boolean major, Properties properties, ContentStream contentStream,
			String checkinComment, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension);
	
	public List<ObjectData> getAllVersions(CallContext context,
			String objectId, String versionSeriesId, String filter,
			Boolean includeAllowableActions, ExtensionsData extension);
	
	public ObjectData getObjectOfLatestVersion(CallContext context,
			String objectId, String versionSeriesId, Boolean major,
			String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePolicyIds, Boolean includeAcl,
			ExtensionsData extension);
}
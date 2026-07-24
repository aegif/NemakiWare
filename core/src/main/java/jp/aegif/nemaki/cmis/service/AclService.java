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
package jp.aegif.nemaki.cmis.service;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import jp.aegif.nemaki.util.spring.aspect.log.LogParam;

/**
 * Discovery Service interface.
 */
public interface AclService {

	Acl getAcl(@LogParam("context") CallContext context, @LogParam("repositoryId") String repositoryId,
			@LogParam("objectId") String objectId, @LogParam("onlyBasicPermissions") Boolean onlyBasicPermissions, ExtensionsData extension);

	/**
	 * Applies a new ACL to an object. Since it is not possible to transmit an
	 * "add ACL" and a "remove ACL" via AtomPub, the merging has to be done on
	 * the client side. The ACEs provided here is supposed to the new complete
	 * ACL.<br/>
	 * 
	 * TODO re-design ACL system in Nemaki
	 * 
	 * @param repositoryId
	 *            TODO
	 */
	Acl applyAcl(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("objectId") String objectId, @LogParam("aces") Acl aces,
			@LogParam("aclPropagation") AclPropagation aclPropagation);

	/**
	 * ACL-in-Solr: after a move, refresh the search-index {@code readers} of the
	 * moved object's inheriting descendants (their effective inherited ACL changed
	 * with the new ancestor chain) AND of the relationships referencing the moved
	 * object (their readers derive from source/target readers). The moved object's
	 * own content/RAG readers are handled by {@code ContentServiceImpl.move}. A
	 * moved leaf still needs the relationship refresh; only a null content is a
	 * no-op. See {@code AclServiceImpl.refreshMovedSubtreeSearchIndexAcl}.
	 */
	void refreshMovedSubtreeSearchIndexAcl(String repositoryId, jp.aegif.nemaki.model.Content content);

	/**
	 * Reconciliation entry point: re-drive the search-index ACL refresh
	 * (content {@code readers} + RAG block + relationships + inheriting
	 * descendants) for a SINGLE object that a prior async refresh failed to
	 * complete. Called by the reconciliation scheduler out of the durable queue.
	 *
	 * @return {@code true} if the object no longer needs reconciliation (clean
	 *         re-drive, or the object was deleted / no search index is wired);
	 *         {@code false} if a failure was hit and the task should be retried.
	 */
	boolean reindexSearchIndexAclForObject(String repositoryId, String objectId);

	/**
	 * As {@link #reindexSearchIndexAclForObject(String, String)} but with a
	 * cooperative-fencing lease guard: {@code leaseStillHeld} is polled before each
	 * node's writes (it heartbeats/renews the reconciliation lease and returns
	 * {@code false} once the lease has been lost to a reclaiming worker). When it
	 * returns {@code false} the re-drive ABORTS (returns {@code false}, not clean) so
	 * a worker that outlived its lease stops overwriting the reclaimer's fresher
	 * readers. A {@code null} guard disables fencing (e.g. a short manual retry).
	 */
	boolean reindexSearchIndexAclForObject(String repositoryId, String objectId,
			java.util.function.BooleanSupplier leaseStillHeld);

}

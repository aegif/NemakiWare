package jp.aegif.nemaki.repository;

import org.apache.chemistry.opencmis.commons.enums.CapabilityAcl;
import org.apache.chemistry.opencmis.commons.enums.CapabilityChanges;
import org.apache.chemistry.opencmis.commons.enums.CapabilityContentStreamUpdates;
import org.apache.chemistry.opencmis.commons.enums.CapabilityJoin;
import org.apache.chemistry.opencmis.commons.enums.CapabilityOrderBy;
import org.apache.chemistry.opencmis.commons.enums.CapabilityQuery;
import org.apache.chemistry.opencmis.commons.enums.CapabilityRenditions;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RepositoryCapabilitiesImpl;

public class NemakiCapabilitiesImpl extends RepositoryCapabilitiesImpl {

	private static final long serialVersionUID = -7037495456587139344L;

	public void setup() {
		// Navigation Capabilities
		setSupportsGetDescendants(true);
		setSupportsGetFolderTree(true);
		setOrderByCapability(CapabilityOrderBy.NONE);
		
		// Object Capabilities
		setCapabilityContentStreamUpdates(CapabilityContentStreamUpdates.ANYTIME);
		setCapabilityChanges(CapabilityChanges.NONE);
		setCapabilityRendition(CapabilityRenditions.NONE);

		// Filling Capabilities
		setSupportsMultifiling(false);
		setSupportsUnfiling(true);
		setSupportsVersionSpecificFiling(false);

		// Versioning Capabilities
		setIsPwcUpdatable(true);
		setIsPwcSearchable(false);
		setAllVersionsSearchable(false);

		// Query Capabilities
		setCapabilityQuery(CapabilityQuery.BOTHCOMBINED);
		setCapabilityJoin(CapabilityJoin.NONE);

		// Changes Capabilities
		setCapabilityChanges(CapabilityChanges.OBJECTIDSONLY);
		
		// ACL Capabilities
		setCapabilityAcl(CapabilityAcl.MANAGE);
	}
}

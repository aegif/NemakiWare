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
package jp.aegif.nemaki.businesslogic.impl.delegate;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.VersionSeries;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Delegate for versioning-specific private helper methods extracted from ContentServiceImpl.
 * The main versioning operations (checkOut, cancelCheckOut, checkIn) remain in ContentServiceImpl
 * but delegate to these methods for version property management.
 */
public class VersioningServiceDelegate {

	private static final Logger log = LoggerFactory.getLogger(VersioningServiceDelegate.class);

	private final ContentDaoService contentDaoService;
	private final ContentServiceHelper helper;

	public VersioningServiceDelegate(ContentDaoService contentDaoService, ContentServiceHelper helper) {
		this.contentDaoService = contentDaoService;
		this.helper = helper;
	}

	public void updateVersionProperties(CallContext callContext, String repositoryId, VersioningState versioningState,
			Document d, Document former) {
		updateVersionProperties(callContext, repositoryId, versioningState, d, former, true);
	}

	/**
	 * Set the new version's flags and (optionally) flip the former version's
	 * latest flags in the database.
	 *
	 * @param updateFormerNow when {@code false}, the in-memory flags on {@code d}
	 *        are set but the former version is NOT written to the database. The
	 *        caller is then responsible for calling
	 *        {@link #updateFormerVersionFlags(String, Document, boolean)} AFTER it
	 *        has durably persisted the new version. checkIn uses this so the former
	 *        version is not flipped to non-latest before the new version exists —
	 *        otherwise a failure to create the new version would leave the version
	 *        series with no latest version.
	 */
	public void updateVersionProperties(CallContext callContext, String repositoryId, VersioningState versioningState,
			Document d, Document former, boolean updateFormerNow) {
		d.setVersionSeriesId(former.getVersionSeriesId());

		switch (versioningState) {
		case MAJOR:
			d.setLatestVersion(true);
			d.setMajorVersion(true);
			d.setLatestMajorVersion(true);
			d.setVersionLabel(helper.increasedVersionLabel(former, versioningState));
			d.setPrivateWorkingCopy(false);
			d.setVersionSeriesCheckedOut(false);
			d.setVersionSeriesCheckedOutBy(null);
			d.setVersionSeriesCheckedOutId(null);
			// Update former version flags (refresh from DB to avoid CouchDB conflicts)
			if (updateFormerNow) {
				updateFormerVersionFlags(repositoryId, former, true);
			}
			break;
		case MINOR:
			d.setLatestVersion(true);
			d.setMajorVersion(false);
			d.setLatestMajorVersion(false);
			d.setVersionLabel(helper.increasedVersionLabel(former, versioningState));
			d.setPrivateWorkingCopy(false);
			d.setVersionSeriesCheckedOut(false);
			d.setVersionSeriesCheckedOutBy(null);
			d.setVersionSeriesCheckedOutId(null);
			// Update former version flags (refresh from DB to avoid CouchDB conflicts)
			if (updateFormerNow) {
				updateFormerVersionFlags(repositoryId, former, false);
			}
			break;
		case CHECKEDOUT:
			d.setLatestVersion(false);
			d.setMajorVersion(false);
			d.setLatestMajorVersion(false);
			d.setPrivateWorkingCopy(true);
			// CRITICAL TCK FIX: Set versioning properties required by VersioningStateCreateTest
			d.setVersionSeriesCheckedOut(true); // Mark as checked out
			d.setVersionSeriesCheckedOutBy(callContext.getUsername());
			d.setVersionSeriesCheckedOutId(d.getId()); // PWC's own ID
			// former latestVersion/latestMajorVersion remains unchanged
			break;
		default:
			break;
		}
	}

	/**
	 * Updates version flags for the former version document.
	 * Refreshes the document from database to avoid CouchDB revision conflicts.
	 *
	 * REFACTORING (2025-10-22): Extracted common logic from MAJOR/MINOR version handling
	 * to eliminate code duplication and improve maintainability.
	 *
	 * @param repositoryId Repository ID
	 * @param former Former version document
	 * @param updateMajorFlag Whether to update latestMajorVersion flag (true for MAJOR, false for MINOR)
	 * @throws CmisObjectNotFoundException if former document not found (indicates database corruption)
	 */
	public void updateFormerVersionFlags(String repositoryId, Document former, boolean updateMajorFlag) {
		// Refresh document from database to avoid CouchDB revision conflicts
		// The 'former' document may have been retrieved earlier and could have a stale _rev value
		Document refreshedFormer = contentDaoService.getDocument(repositoryId, former.getId());
		if (refreshedFormer == null) {
			log.error("Cannot refresh former version document: {} in repository: {}", former.getId(), repositoryId);
			throw new CmisObjectNotFoundException("Former version document not found: " + former.getId());
		}
		refreshedFormer.setLatestVersion(false);
		if (updateMajorFlag) {
			refreshedFormer.setLatestMajorVersion(false);
		}
		contentDaoService.update(repositoryId, refreshedFormer);
	}

	/**
	 * Update versionSeriesId#versionSeriesCheckedOutId after creating a PWC
	 */
	public void updateVersionSeriesWithPwc(CallContext callContext, String repositoryId, VersionSeries versionSeries,
			Document pwc) {

		// CRITICAL FIX: Fetch latest VersionSeries from DB to ensure _rev synchronization
		// This prevents Cloudant SDK revision conflicts during update operations
		log.debug("Fetching latest VersionSeries from DB for revision synchronization: {}", versionSeries.getId());
		VersionSeries latestVersionSeries = contentDaoService.getVersionSeries(repositoryId, versionSeries.getId());

		if (latestVersionSeries == null) {
			log.warn("VersionSeries {} not found in database during checkout - document may have been deleted concurrently", versionSeries.getId());
			return;
		}

		log.error("VersionSeries found: id={}, revision={}", latestVersionSeries.getId(), latestVersionSeries.getRevision());

		// Apply updates to the fresh object with current _rev
		latestVersionSeries.setVersionSeriesCheckedOut(true);
		latestVersionSeries.setVersionSeriesCheckedOutId(pwc.getId());
		latestVersionSeries.setVersionSeriesCheckedOutBy(callContext.getUsername());

		if (log.isDebugEnabled()) {
			log.debug("Updating VersionSeries with current revision: {} for PWC: {}",
				latestVersionSeries.getRevision(), pwc.getId());
		}

		try {
			contentDaoService.update(repositoryId, latestVersionSeries);
			log.debug("VersionSeries update completed successfully for PWC: {}", pwc.getId());
		} catch (IllegalArgumentException e) {
			if (e.getMessage() != null && e.getMessage().contains("not found in database")) {
				log.warn("VersionSeries {} was deleted during update - document may have been deleted concurrently", latestVersionSeries.getId());
				return;
			}
			throw e;
		}
	}

	/**
	 * Merge secondary type IDs and aspects from the latest version into the new
	 * version being created during checkIn, if the PWC-based copy is missing them.
	 * This preserves secondary type properties (e.g. cloud drive metadata) that
	 * were added to the original document but not to the PWC.
	 */
	public void mergeSecondaryTypesFromLatest(Document checkedIn, Document latest) {
		// Merge secondaryIds
		List<String> pwcSecIds = checkedIn.getSecondaryIds();
		List<String> latestSecIds = latest.getSecondaryIds();
		if (latestSecIds != null && !latestSecIds.isEmpty()) {
			if (pwcSecIds == null) {
				pwcSecIds = new ArrayList<>();
			}
			for (String secId : latestSecIds) {
				if (!pwcSecIds.contains(secId)) {
					pwcSecIds.add(secId);
				}
			}
			checkedIn.setSecondaryIds(pwcSecIds);
		}

		// Merge aspects
		List<Aspect> pwcAspects = checkedIn.getAspects();
		List<Aspect> latestAspects = latest.getAspects();
		if (latestAspects != null && !latestAspects.isEmpty()) {
			if (pwcAspects == null) {
				pwcAspects = new ArrayList<>();
			}
			Set<String> existingAspectNames = new HashSet<>();
			for (Aspect a : pwcAspects) {
				if (a.getName() != null) {
					existingAspectNames.add(a.getName());
				}
			}
			for (Aspect latestAspect : latestAspects) {
				if (latestAspect.getName() != null && !existingAspectNames.contains(latestAspect.getName())) {
					pwcAspects.add(latestAspect);
				}
			}
			checkedIn.setAspects(pwcAspects);
		}
	}
}

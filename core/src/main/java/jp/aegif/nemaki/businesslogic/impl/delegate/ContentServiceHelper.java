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

import jp.aegif.nemaki.businesslogic.WebhookService;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.constant.NodeType;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import jp.aegif.nemaki.model.Acl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.GregorianCalendar;
import java.util.concurrent.CompletableFuture;

/**
 * Shared utility methods extracted from ContentServiceImpl.
 * Used by ContentServiceImpl and its delegates (VersioningServiceDelegate,
 * AttachmentServiceDelegate, etc.) to avoid circular dependencies.
 */
public class ContentServiceHelper {

	private static final Logger log = LoggerFactory.getLogger(ContentServiceHelper.class);

	private final ContentDaoService contentDaoService;
	private volatile WebhookService webhookService;

	public ContentServiceHelper(ContentDaoService contentDaoService, WebhookService webhookService) {
		this.contentDaoService = contentDaoService;
		this.webhookService = webhookService;
	}

	/**
	 * Spring DIの注入順序に依存しないよう、webhookServiceを後から設定可能にする。
	 * initDelegates()時にwebhookServiceがまだnullの場合、setWebhookService()経由で後から伝播される。
	 */
	public void setWebhookService(WebhookService webhookService) {
		this.webhookService = webhookService;
	}

	public void setSignature(CallContext callContext, NodeBase n) {
		n.setCreator(callContext.getUsername());
		n.setCreated(getTimeStamp());
		n.setModifier(callContext.getUsername());
		n.setModified(getTimeStamp());

		// CRITICAL TCK FIX: Initialize change token on creation
		// CMIS spec requires change tokens for optimistic locking
		if (n instanceof Content) {
			Content content = (Content) n;
			String initialChangeToken = String.valueOf(System.currentTimeMillis());
			content.setChangeToken(initialChangeToken);
		}
	}

	public void setModifiedSignature(CallContext callContext, NodeBase n) {
		n.setModifier(callContext.getUsername());
		n.setModified(getTimeStamp());

		// CRITICAL TCK FIX: Update change token on modification
		// CMIS spec requires updating change token after any modification
		if (n instanceof Content) {
			Content content = (Content) n;
			String newChangeToken = String.valueOf(System.currentTimeMillis());
			content.setChangeToken(newChangeToken);
		}
	}

	public GregorianCalendar getTimeStamp() {
		return DataUtil.millisToCalendar(System.currentTimeMillis());
	}

	public String generateChangeToken(NodeBase node) {
		return String.valueOf(node.getCreated().getTimeInMillis());
	}

	public String writeChangeEvent(CallContext callContext, String repositoryId, Content content,
			ChangeType changeType) {
		return writeChangeEvent(callContext, repositoryId, content, null, changeType);
	}

	public String writeChangeEvent(CallContext callContext, String repositoryId, Content content, Acl acl,
			ChangeType changeType) {
		Change change = new Change();
		change.setAcl(acl);
		change.setObjectId(content.getId());
		change.setChangeType(changeType);
		switch (changeType) {
		case CREATED:
			change.setTime(content.getCreated());
			break;
		case UPDATED:
			change.setTime(content.getModified());
			break;
		case SECURITY:
			change.setTime(content.getModified());
			break;
		default:
			break;
		}

		change.setType(NodeType.CHANGE.value());
		change.setName(content.getName());
		change.setBaseType(content.getType());
		change.setObjectType(content.getObjectType());
		change.setParentId(content.getParentId());

		/*
		 * //Policy List<String> policyIds = new ArrayList<String>();
		 * List<Policy> policies = getAppliedPolicies(repositoryId,
		 * content.getId(), null); if (!CollectionUtils.isEmpty(policies)) { for
		 * (Policy p : policies) { policyIds.add(p.getId()); } }
		 * change.setPolicyIds(policyIds);
		 */

		if (content.isDocument()) {
			Document d = (Document) content;
			change.setVersionSeriesId(d.getVersionSeriesId());
			change.setVersionLabel(d.getVersionLabel());
		}

		setSignature(callContext, change);
		change.setToken(generateChangeToken(change));

		// Create change event record (no content modification needed)
		Change created = contentDaoService.create(repositoryId, change);
		log.debug("Change event created successfully - ID=" + created.getId() +
			", token=" + created.getToken() + ", objectId=" + content.getId());

		// Trigger webhook notifications asynchronously
		if (webhookService != null) {
			final String contentId = content.getId();
			CompletableFuture.runAsync(() -> {
				try {
					webhookService.triggerWebhook(callContext, repositoryId, content, changeType, null);
				} catch (Exception e) {
					log.warn("Failed to trigger webhook for content " + contentId + ": " + e.getMessage());
				}
			});
		}

		return change.getToken();
	}

	// ///////////////////////////////////////
	// Utility
	// ///////////////////////////////////////
	public String increasedVersionLabel(Document document, VersioningState versioningState) {
		// e.g. #{major}(.{#minor})
		String label = document.getVersionLabel();

		// CRITICAL TCK FIX: Handle null version label (document without initial version)
		if (label == null || label.isEmpty()) {
			// Default to 0.0 if no version label exists
			label = "0.0";
		}

		int major = 0;
		int minor = 0;

		int point = label.lastIndexOf(".");
		if (point == -1) {
			major = Integer.parseInt(label);
		} else {
			major = Integer.parseInt(label.substring(0, point));
			minor = Integer.parseInt(label.substring(point + 1));
		}

		String newLabel = label;
		if (versioningState == VersioningState.MAJOR) {
			newLabel = String.valueOf(major + 1) + ".0";
		} else if (versioningState == VersioningState.MINOR) {
			newLabel = String.valueOf(major) + "." + String.valueOf(minor + 1);
		}
		return newLabel;
	}
}

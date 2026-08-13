package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.PatchHistory;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.PropertyManager;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
// Removed Ektorp imports - functionality temporarily disabled

import java.util.List;
import java.util.Map;

public class PatchUtil {
	private static final Log log = LogFactory.getLog(PatchUtil.class);
	protected PropertyManager propertyManager;
	protected CloudantClientPool connectorPool;

	/**
	 * Below this many documents a repository is treated as fresh rather than broken.
	 *
	 * <p>A brand-new repository legitimately has empty views and nothing to lose; the seed data
	 * puts it well above this within the first patch run.
	 */
	private static final long VIEW_CANARY_FLOOR = 10L;
	protected ContentService contentService;
	protected ContentDaoService contentDaoService;
	protected RepositoryInfoMap repositoryInfoMap;
	protected RepositoryService repositoryService;
	protected TypeService typeService;
	protected TypeManager typeManager;
	protected SolrUtil solrUtil;

	public PatchUtil() {
		if (log.isDebugEnabled()) {
			log.debug("PatchUtil constructor called");
		}
	}

	protected boolean isApplied(String repositoryId, String name){
		try {
			if (log.isDebugEnabled()) {
				log.debug("Checking if patch '" + name + "' is applied in repository '" + repositoryId + "'");
			}
			
			// Use ContentDaoService to query patch history by name
			PatchHistory patchHistory = contentDaoService.getPatchHistoryByName(repositoryId, name);
			
			if (patchHistory == null) {
				if (log.isDebugEnabled()) {
					log.debug("No patch history found for '" + name + "' - patch not applied");
				}
				return false;
			}
			
			boolean applied = patchHistory.isApplied();
			if (log.isDebugEnabled()) {
				log.debug("Patch '" + name + "' status: " + (applied ? "APPLIED" : "NOT APPLIED"));
			}
			return applied;
			
		} catch (Exception e) {
			// "I could not check" is NOT "not applied". Assuming the latter re-runs the patch,
			// and patches are not all idempotent: bedroom grew a second `.system` folder and a
			// second history record for system-folder-setup-20250805 that way. A duplicate
			// system folder breaks CMIS path resolution — two objects answer to /.system and
			// the one fetched by path stops matching the one fetched by id.
			//
			// Reporting APPLIED skips the patch for this startup instead. That is the cheap
			// mistake: a patch that really has not run yet will be applied on the next startup,
			// once whatever made the history unreadable (a design document mid-rebuild, most
			// likely) has settled. Running a non-idempotent patch twice cannot be undone by
			// waiting.
			org.apache.commons.logging.LogFactory.getLog(PatchUtil.class).error(
					"Could not determine whether patch '" + name + "' is applied in repository '"
							+ repositoryId + "' — SKIPPING it this time rather than risking a"
							+ " second application. It will be reconsidered on the next startup.",
					e);
			return true;
		}
	}

	protected void createPathHistory(String repositoryId, String name){
		try {
			if (log.isDebugEnabled()) {
				log.debug("Creating patch history for '" + name + "' in repository '" + repositoryId + "'");
			}
			
			// Check if patch history already exists
			PatchHistory existingHistory = contentDaoService.getPatchHistoryByName(repositoryId, name);
			if (existingHistory != null) {
				if (log.isDebugEnabled()) {
					log.debug("Patch history for '" + name + "' already exists - updating status to applied");
				}
				existingHistory.setIsApplied(true);
				contentDaoService.update(repositoryId, existingHistory);
			} else {
				if (log.isDebugEnabled()) {
					log.debug("Creating new patch history for '" + name + "'");
				}
				PatchHistory patchHistory = new PatchHistory(name, true);
				contentDaoService.create(repositoryId, patchHistory);
			}
			
			if (log.isDebugEnabled()) {
				log.debug("Patch history for '" + name + "' successfully created/updated");
			}
		} catch (Exception e) {
			// Do NOT swallow. If the patch ran but its history did not persist, the patch is
			// "not applied" as far as the next startup can tell — so it runs again. For the
			// fourteen patches whose existence checks go through a view, a second run is how
			// duplicates appear (bedroom grew a second .system folder exactly once, and a second
			// history record for the same patch name alongside it).
			//
			// Propagating makes AbstractNemakiPatch.apply record the repository as failed and log
			// it, so the operator sees "this patch did not complete" instead of a silent line in
			// an error log that nothing acts on.
			log.error("Error creating patch history for '" + name + "': " + e.getMessage(), e);
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
					"Patch '" + name + "' was applied to repository '" + repositoryId
							+ "' but its history could not be recorded, so it would be applied"
							+ " again on the next startup: " + e.getMessage(), e);
		}
	}

	protected void addDb(String dbName){
		// Check if database already exists using Cloudant SDK
		try {
			CloudantClientWrapper client = connectorPool.getClient(dbName);
			if (client != null) {
				// Database already exists - no need to create
				if (log.isDebugEnabled()) {
					log.debug("Database '" + dbName + "' already exists, skipping creation");
				}
				return;
			}
		} catch (Exception e) {
			// Database doesn't exist or connection failed
			if (log.isDebugEnabled()) {
				log.debug("Database '" + dbName + "' doesn't exist or connection failed: " + e.getMessage());
			}
		}
		
		// For Docker environments, databases are already created during initialization
		// This method is primarily for non-Docker deployments
		if (log.isDebugEnabled()) {
			log.debug("addDb for '" + dbName + "' - assuming database exists in Docker environment");
		}
	}

	protected void addView(String repositoryId, String viewName, String map){
		addView(repositoryId, viewName, map, false);
	}

	protected void addView(String repositoryId, String viewName, String map, boolean force){
		// NOTE: View creation is now handled by Patch_StandardCmisViews using direct CouchDB API
		// This legacy method is kept for backward compatibility but does nothing
		if (log.isDebugEnabled()) {
			log.debug("addView for '" + viewName + "' - views are managed by Patch_StandardCmisViews");
		}
	}

	protected void deleteView(String repositoryId, String viewName){
		// NOTE: View deletion is now handled by Patch_StandardCmisViews using direct CouchDB API
		// This legacy method is kept for backward compatibility but does nothing
		if (log.isDebugEnabled()) {
			log.debug("deleteView for '" + viewName + "' - views are managed by Patch_StandardCmisViews");
		}
	}


	protected void addSimpleProperty(Map<String, PropertyDefinition<?>> props, String id, Cardinality cardinality, Updatability updatability, boolean required, boolean orderable){
		PropertyStringDefinitionImpl pdf = new PropertyStringDefinitionImpl();
		pdf.setId(id);
		pdf.setPropertyType(PropertyType.STRING);
		pdf.setDisplayName(id);
		pdf.setQueryName(id);
		pdf.setDescription(null);
		pdf.setIsInherited(false);
		pdf.setCardinality(cardinality);
		pdf.setUpdatability(updatability);
		pdf.setIsRequired(required);
		pdf.setIsOrderable(orderable);
		pdf.setIsQueryable(true);
		pdf.setLocalName(id);
		pdf.setLocalNamespace(DataUtil.NAMESPACE);

		pdf.setIsOpenChoice(false);
		pdf.setChoices(null);
		pdf.setDefaultValue(null);
		props.put(id, pdf);
	}

	// Consolidated into ContentService.getOrCreateSystemSubFolder (thin delegate).
	protected Folder getOrCreateSystemSubFolder(String repositoryId, String name){
		return contentService.getOrCreateSystemSubFolder(repositoryId, name);
	}

	public PropertyManager getPropertyManager() {
		return propertyManager;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	/**
	 * Are this repository's CMIS views answering truthfully right now?
	 *
	 * <p>Fourteen of the forty-one patches decide "does this already exist?" by querying a view in
	 * {@code _design/_repo}, and then create the missing thing with a CouchDB-generated id. Both
	 * halves matter: a view being rebuilt answers <b>HTTP 200 with zero rows and no exception</b>
	 * (measured), so the check says "absent"; and because the create has no stable id, CouchDB
	 * cannot reject the duplicate with a conflict. That is how {@code bedroom} grew a second
	 * {@code .system} folder and a second patch-history record on 2026-08-13.
	 *
	 * <p>{@code Patch_JoinedGroupsSingleEmit} rewrites that very design document during a v3.3.0
	 * upgrade, so this is not a hypothetical window — it is the one the documented upgrade opens.
	 *
	 * <p>Rather than repair fourteen existence checks one at a time, this gate refuses to apply a
	 * patch to a repository whose views are not currently answering. <b>It is not universal:</b>
	 * {@code applySystemPatch()} runs before any per-repository gating, and a patch that overrides
	 * {@code apply()} (currently {@code Patch_WebAuthnCredentialViews}) bypasses it. Neither
	 * exception mutates repository objects today, but a future one would slip past — so this is a
	 * gate on the ordinary path, not a guarantee about every path. The test is the same
	 * shape as the reindex wipe guard: <b>if the database holds documents but a core view returns
	 * nothing at all, the views are not built</b>. A genuinely fresh repository has neither, so it
	 * is not blocked.
	 *
	 * <p>Failing this way costs one startup: the patch is applied on the next one, once the
	 * rebuild has finished. Applying a non-idempotent patch twice cannot be undone by waiting.
	 */
	public boolean cmisViewsAreAnswering(String repositoryId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			if (client == null) {
				return false;
			}
			long documents = client.getDatabaseInfo() == null
					? 0L : client.getDatabaseInfo().getDocCount();
			if (documents <= VIEW_CANARY_FLOOR) {
				// Too small to tell a fresh repository from a broken one — and too small to lose
				// anything either.
				return true;
			}
			// A MISSING view is not the same as a SILENT one, and conflating them deadlocks the
			// system: Patch_StandardCmisViews is the patch that CREATES childrenNames, and it is
			// gated like every other patch. A legacy repository that has content but not that
			// view would fail the gate, so the patch that would repair it never runs — on this
			// startup or any other. Absence is a job for the patches; only a view that EXISTS and
			// answers nothing means the views are not to be trusted.
			if (!cmisViewExists(client, "childrenNames")) {
				log.warn("Repository '" + repositoryId + "' has no _repo/childrenNames view yet."
						+ " Letting patches run — installing that view is a patch's job.");
				return true;
			}
			// childrenNames, NOT children: `children` carries a _count reduce, and queryViewCount
			// reads total_rows, which a reduce response does not carry — so it answers 0 for a
			// perfectly healthy repository. That mistake was caught on the running stack, where
			// this gate refused 312 times against a database that was fine.
			long viewRows = client.queryViewCount("_repo", "childrenNames");
			if (viewRows > 0) {
				return true;
			}
			log.error("Refusing to apply patches to repository '" + repositoryId + "': it holds "
					+ documents + " documents but the _repo/childrenNames view returned no rows at all."
					+ " The views are not answering (a design document being rebuilt replies 200"
					+ " with zero rows), and every patch that checks 'does this already exist?'"
					+ " through a view would create a duplicate. Patches will be reconsidered on"
					+ " the next startup.");
			return false;
		} catch (Exception e) {
			log.error("Refusing to apply patches to repository '" + repositoryId
					+ "': could not establish whether its views are answering", e);
			return false;
		}
	}

	/** Is this view declared in {@code _design/_repo}? Read from the design document itself. */
	private boolean cmisViewExists(CloudantClientWrapper client, String viewName) {
		try {
			tools.jackson.databind.JsonNode design =
					client.get(tools.jackson.databind.JsonNode.class, "_design/_repo");
			if (design == null) {
				return false;
			}
			tools.jackson.databind.JsonNode views = design.get("views");
			return views != null && views.has(viewName);
		} catch (Exception e) {
			// Cannot tell — treat the view as present so the "exists but silent" branch decides.
			// Guessing "absent" here would wave patches through on exactly the unreadable
			// repository this gate exists to protect.
			return true;
		}
	}

	public CloudantClientPool getConnectorPool() {
		return connectorPool;
	}

	public void setConnectorPool(CloudantClientPool connectorPool) {
		this.connectorPool = connectorPool;
	}

	public ContentService getContentService() {
		return contentService;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public ContentDaoService getContentDaoService() {
		return contentDaoService;
	}

	public void setContentDaoService(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}

	public RepositoryInfoMap getRepositoryInfoMap() {
		return repositoryInfoMap;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}

	public RepositoryService getRepositoryService() {
		return repositoryService;
	}

	public void setRepositoryService(RepositoryService repositoryService) {
		this.repositoryService = repositoryService;
	}

	public TypeService getTypeService() {
		return typeService;
	}

	public void setTypeService(TypeService typeService) {
		this.typeService = typeService;
	}

	public TypeManager getTypeManager() {
		return typeManager;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public SolrUtil getSolrUtil() {
		return solrUtil;
	}

	public void setSolrUtil(SolrUtil solrUtil) {
		this.solrUtil = solrUtil;
	}
}

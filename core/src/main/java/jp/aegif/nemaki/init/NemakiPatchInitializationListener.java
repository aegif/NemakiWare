package jp.aegif.nemaki.init;

import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jp.aegif.nemaki.patch.AbstractNemakiPatch;

/**
 * Servlet Context Listener for NemakiWare Patch Initialization
 *
 * CRITICAL FIX: ServletContextListener approach for reliable patch execution
 *
 * Problem: ApplicationListener<ContextRefreshedEvent> was not being invoked
 * due to custom NemakiApplicationContextLoader behavior.
 *
 * Solution: Use standard Servlet lifecycle listener which is guaranteed to execute
 * AFTER Spring context is fully initialized. ServletContextListener.contextInitialized()
 * is called after all Spring beans are created and ready.
 *
 * This listener retrieves patch beans from the initialized Spring context and
 * executes them manually, ensuring reliable initialization regardless of Spring
 * event propagation issues.
 *
 * Execution Order:
 * 1. Servlet container starts
 * 2. Spring ContextLoaderListener initializes ApplicationContext
 * 3. THIS LISTENER executes (after Spring context is ready)
 * 4. Patches are applied using fully initialized CMIS services
 *
 * Registration: This listener is registered in web.xml with explicit ordering
 * to ensure it executes after ContextLoaderListener.
 */
public class NemakiPatchInitializationListener implements ServletContextListener {

    private static final Log log = LogFactory.getLog(NemakiPatchInitializationListener.class);

    // AtomicBoolean to ensure patches are applied only once
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * Execute patch initialization when servlet context is initialized
     *
     * CRITICAL: This executes AFTER Spring context is fully initialized,
     * ensuring all CMIS services are available.
     */
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        // Ensure this runs only once
        if (!initialized.compareAndSet(false, true)) {
            log.info("NemakiPatchInitializationListener: Patches already applied (own guard), skipping");
            return;
        }

        // Skip if CMISPostInitializer already applied patches via ContextRefreshedEvent
        if (CMISPostInitializer.isPatchesApplied()) {
            log.info("NemakiPatchInitializationListener: CMISPostInitializer already applied patches, skipping");
            return;
        }

        log.warn("NemakiPatchInitializationListener: STARTING PATCH INITIALIZATION (CMISPostInitializer did NOT succeed)");

        try {
            ServletContext servletContext = sce.getServletContext();

            // Get Spring WebApplicationContext
            WebApplicationContext springContext = WebApplicationContextUtils.getWebApplicationContext(servletContext);

            // Setup Mode guard: skip patch execution when CouchDB is not ready.
            // Reset `initialized` so patches can be triggered after setup completes.
            if (springContext != null) {
                try {
                    StartupProbeService probeService = springContext.getBean(StartupProbeService.class);
                    if (probeService != null && probeService.isSetupRequired()) {
                        log.info("NemakiPatchInitializationListener: Setup Mode detected — suppressing patch execution");
                        initialized.set(false);
                        return;
                    }
                } catch (Exception e) {
                    log.debug("StartupProbeService not available, proceeding normally: " + e.getMessage());
                }
            }

            if (springContext == null) {
                log.error("Spring WebApplicationContext not found - cannot apply patches");
                return;
            }

            log.info("Spring WebApplicationContext retrieved successfully");

            // Apply patches in order
            applyPatchesFromSpringContext(springContext);

            log.info("NemakiPatchInitializationListener: PATCH INITIALIZATION COMPLETED");

        } catch (Exception e) {
            log.error("Failed to apply patches during servlet context initialization", e);
            // Don't fail the entire startup - log error and continue
        }
    }

    /**
     * Patches that have a guaranteed dependency order (must run first /
     * in this sequence). Anything not listed here is appended in
     * Spring-bean-name alphabetical order after these run.
     *
     * <p>RC4 (R1): the hardcoded array used to be the source of truth
     * for the entire patch set. Adding a new patch to
     * {@code cmisPostInitializer.cmisPatchList} without also remembering
     * to add it here meant the fallback path silently skipped it (and 8
     * patches lacked top-level bean ids so they couldn't even be
     * referenced this way). The listener now uses
     * {@link WebApplicationContext#getBeansOfType} to collect every
     * {@link AbstractNemakiPatch} bean, then runs the ordered seeds
     * below first followed by all remaining patches. PatchHistory
     * still dedupes per repository, so re-running an already-applied
     * patch in either order is a no-op.
     */
    private static final String[] ORDERED_SEED_PATCHES = {
        "patch_SystemFolderSetup",        // .system folder — must be first
        "patch_InitialContentSetup",      // Sites / Technical Documents
        "patch_StandardCmisViews",        // 38+ standard CMIS views
        "patch_NarrowUserGroupViews",     // 3.1 upgrade: objectType filter
        "patch_ChildrenViewReduceCount",  // 2.4 upgrade: _count reduce
        "patch_TestUserInitialization",
        "patch_NemakiwareStandardTypes",  // base + secondary type registration
        "patch_WebhookableSecondaryType", // depends on standard types
        // RC4.1 (F1): SourceFields adds properties to the
        // ExternalIntegration secondary type and emits a WARN when the
        // type is missing (see Patch_ExternalIntegrationSourceFields:66).
        // Alphabetical ordering happens to preserve this dependency
        // today, but a future patch named e.g. "patch_ExternalIntegrationFoo"
        // sorting between the two would silently break it. Pin both as
        // seeds so the order is explicit.
        "patch_ExternalIntegrationSecondaryType",
        "patch_ExternalIntegrationSourceFields",
        // Retention: SecondaryTypes registers the retention secondary type;
        // the Expiration/LastModification views index its properties and must
        // run AFTER it. MigrationLogViews is independent. Pin all four as seeds
        // so the SecondaryTypes -> views order is explicit in the fallback path
        // (mirrors patchContext.xml), not left to alphabetical chance.
        "patch_RetentionMigrationLogViews",
        "patch_RetentionSecondaryTypes",
        "patch_RetentionExpirationView",
        "patch_RetentionLastModificationView",
    };

    private void applyPatchesFromSpringContext(WebApplicationContext springContext) {
        try {
            // 1. Pull EVERY patch from the live context — no hardcoded list
            //    can drift out of sync with cmisPostInitializer's inline list.
            java.util.Map<String, AbstractNemakiPatch> allPatches =
                    springContext.getBeansOfType(AbstractNemakiPatch.class);
            if (allPatches.isEmpty()) {
                log.warn("No AbstractNemakiPatch beans found in Spring context — nothing to apply");
                return;
            }

            // 2. Build ordered execution list: seed-order first, then the
            //    remainder in alphabetical bean-name order for determinism.
            java.util.LinkedHashMap<String, AbstractNemakiPatch> ordered = new java.util.LinkedHashMap<>();
            for (String seedName : ORDERED_SEED_PATCHES) {
                AbstractNemakiPatch p = allPatches.get(seedName);
                if (p != null) {
                    ordered.put(seedName, p);
                }
                // Missing seed beans aren't fatal — they may legitimately
                // be absent in custom builds. PatchHistory dedupes if both
                // CMISPostInitializer (primary) and this listener fire.
            }
            allPatches.entrySet().stream()
                    .filter(e -> !ordered.containsKey(e.getKey()))
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> ordered.put(e.getKey(), e.getValue()));

            log.info("NemakiPatchInitializationListener: collected " + ordered.size()
                    + " patches from Spring context (" + ORDERED_SEED_PATCHES.length
                    + " ordered seeds + " + (ordered.size() - countPresentSeeds(allPatches))
                    + " remainder)");

            // 3. Apply.
            for (java.util.Map.Entry<String, AbstractNemakiPatch> e : ordered.entrySet()) {
                String beanName = e.getKey();
                AbstractNemakiPatch patch = e.getValue();
                try {
                    log.info("Applying patch: " + patch.getClass().getSimpleName() + " (bean: " + beanName + ")");
                    boolean success = patch.apply();
                    if (success) {
                        log.info("Successfully applied patch: " + patch.getClass().getSimpleName());
                    } else {
                        log.warn("Patch returned failure: " + patch.getClass().getSimpleName());
                    }
                } catch (Exception ex) {
                    log.error("Failed to apply patch: " + beanName, ex);
                    // Continue with other patches even if one fails
                }
            }

        } catch (Exception e) {
            log.error("Error during patch application", e);
        }
    }

    private int countPresentSeeds(java.util.Map<String, AbstractNemakiPatch> allPatches) {
        int n = 0;
        for (String seedName : ORDERED_SEED_PATCHES) {
            if (allPatches.containsKey(seedName)) n++;
        }
        return n;
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        log.info("NemakiPatchInitializationListener: Context destroyed");
        // No cleanup needed
    }
}

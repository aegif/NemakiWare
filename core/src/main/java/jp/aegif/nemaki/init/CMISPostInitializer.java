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
package jp.aegif.nemaki.init;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.Order;

import jp.aegif.nemaki.patch.AbstractNemakiPatch;

/**
 * PHASE 2: CMISPostInitializer - CMIS Patch Application
 *
 * CRITICAL FIX (2025-11-10): Added @Order(2) annotation to ensure proper execution order
 *
 * Execution order:
 * - @Order(1): DatabasePreInitializer (creates databases, loads dump files)
 * - @Order(2): CMISPostInitializer (applies CMIS patches) ← THIS CLASS
 * - @Order(3): PatchService (creates system property definitions)
 *
 * Previous issue: Without @Order annotation, Spring did not enforce execution order,
 * causing PatchService to execute before CMISPostInitializer completed, resulting in
 * NullPointerException when trying to access uninitialized TypeManager.
 *
 * CRITICAL FIX: Execution timing changed from InitializingBean to ApplicationListener<ContextRefreshedEvent>
 *
 * Problem: InitializingBean.afterPropertiesSet() executes too early - when bean properties
 * are set but other services may not be fully initialized yet. This caused patches to fail
 * with "TYPES is empty" errors because TypeManager wasn't ready.
 *
 * Solution: Implement ApplicationListener<ContextRefreshedEvent> to wait until the ENTIRE
 * Spring context is fully initialized and all services are operational.
 *
 * ApplicationListener is used instead of @EventListener because it works reliably with
 * XML-based bean definitions in patchContext.xml.
 *
 * This class handles CMIS-level initialization operations that require
 * fully initialized Spring services. It executes AFTER Spring context
 * initialization is complete and all service beans are available.
 *
 * Phase 2 Operations (CMIS-based):
 * - Create initial folders using CMIS services (.system, Sites, Technical Documents)
 * - Register sample documents (CMIS specification PDFs)
 * - Set up type definitions and relationships
 * - Apply CMIS-specific configuration patches
 *
 * This phase executes AFTER Phase 1 (database setup) and full Spring
 * context initialization, ensuring all required services are available.
 *
 * NOTE: This class is registered as a Spring Bean in patchContext.xml (NOT via @Component)
 * to allow proper dependency injection of cmisPatchList property.
 */
@Order(2)  // Execute after DatabasePreInitializer (@Order(1)) and before PatchService (@Order(3))
public class CMISPostInitializer implements ApplicationListener<ContextRefreshedEvent> {

    private static final Log log = LogFactory.getLog(CMISPostInitializer.class);

    private List<AbstractNemakiPatch> cmisPatchList;

    // Guard to ensure onApplicationEvent runs at most once
    private static final AtomicBoolean started = new AtomicBoolean(false);
    // Set to true only after all patches have been applied successfully
    private static final AtomicBoolean completedSuccessfully = new AtomicBoolean(false);

    /**
     * Check if CMISPostInitializer has successfully applied all patches.
     * Used by NemakiPatchInitializationListener to avoid duplicate patch execution.
     * Returns true only when patches completed without exception, so the Listener
     * can still act as a fallback if CMISPostInitializer fails.
     */
    public static boolean isPatchesApplied() {
        return completedSuccessfully.get();
    }

    /**
     * Constructor - log bean instantiation for debugging
     */
    public CMISPostInitializer() {
    }

    /**
     * Execute Phase 2 CMIS initialization
     *
     * CRITICAL: This method is called by Spring AFTER the entire ApplicationContext is fully
     * initialized, ensuring all CMIS services, TypeManager, and other dependencies are ready.
     *
     * Uses AtomicBoolean to ensure execution happens exactly once, even if ContextRefreshedEvent
     * fires multiple times (e.g., parent/child context scenarios).
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // Setup Mode guard: skip patch execution when CouchDB is not ready.
        // Do NOT set `started` yet — let executePatches() handle the one-shot guard
        // so that SetupApplyResource can trigger it later.
        try {
            StartupProbeService probeService = event.getApplicationContext().getBean(StartupProbeService.class);
            if (probeService != null && probeService.isSetupRequired()) {
                log.info("=== PHASE 2: Setup Mode detected — suppressing CMIS patch execution ===");
                return;
            }
        } catch (Exception e) {
            log.debug("StartupProbeService not available, proceeding normally: " + e.getMessage());
        }

        executePatches();
    }

    /**
     * Execute deferred CMIS patches.  Called from onApplicationEvent() during
     * normal startup and also from SetupApplyResource after setup completes
     * (to initialise patches that were skipped in Setup Mode).
     *
     * Thread-safe: uses started AtomicBoolean to guard against concurrent
     * execution. On failure the guard is reset so that a subsequent retry
     * (e.g. via /apply/mark-complete) can re-execute the patches.
     *
     * @return true if all patches succeeded, false if any failed
     */
    public boolean executePatches() {
        // Already completed successfully — idempotent, no re-execution needed
        if (completedSuccessfully.get()) {
            log.info("=== PHASE 2: executePatches() — already completed successfully, skipping ===");
            return true;
        }

        // Guard against concurrent execution (but allow retry after failure)
        if (!started.compareAndSet(false, true)) {
            log.info("=== PHASE 2: executePatches() — execution in progress, skipping ===");
            return false;
        }

        log.info("=== PHASE 2: CMIS POST-INITIALIZATION STARTED ===");

        try {
            boolean allSucceeded = true;
            if (cmisPatchList != null && !cmisPatchList.isEmpty()) {
                log.info("Applying " + cmisPatchList.size() + " CMIS patches");
                allSucceeded = applyCMISPatches();
            } else {
                log.info("No CMIS patches to apply");
            }

            // Mark as successfully completed ONLY when all patches succeeded
            // NemakiPatchInitializationListener checks this flag — if false, it retries
            if (allSucceeded) {
                completedSuccessfully.set(true);
                log.info("=== PHASE 2: CMIS POST-INITIALIZATION COMPLETED (allSucceeded=true) ===");
            } else {
                log.warn("=== PHASE 2: CMIS POST-INITIALIZATION COMPLETED WITH FAILURES — retry allowed ===");
                started.set(false);  // Reset guard to allow retry
            }
            return allSucceeded;

        } catch (Exception e) {
            log.error("Phase 2 CMIS post-initialization failed — retry allowed", e);
            started.set(false);  // Reset guard to allow retry
            return false;
        }
    }
    
    /**
     * Apply CMIS-specific patches using fully initialized services.
     * @return true if ALL patches succeeded, false if any patch failed
     */
    private boolean applyCMISPatches() {
        boolean allSucceeded = true;
        for (AbstractNemakiPatch patch : cmisPatchList) {
            try {
                if (log.isDebugEnabled()) {
                    log.debug("Applying CMIS patch: " + patch.getClass().getSimpleName());
                }
                boolean patchSuccess = patch.apply();
                if (!patchSuccess) {
                    log.warn("CMIS patch returned failure: " + patch.getClass().getSimpleName());
                    allSucceeded = false;
                } else if (log.isDebugEnabled()) {
                    log.debug("Successfully applied CMIS patch: " + patch.getClass().getSimpleName());
                }
            } catch (Exception e) {
                log.error("Failed to apply CMIS patch: " + patch.getClass().getSimpleName(), e);
                allSucceeded = false;
                // Continue with other patches even if one fails
            }
        }
        return allSucceeded;
    }

    /**
     * Set the list of CMIS patches to apply during initialization
     */
    public void setCmisPatchList(List<AbstractNemakiPatch> cmisPatchList) {
        if (log.isDebugEnabled()) {
            log.debug("setCmisPatchList called with " +
                     (cmisPatchList != null ? cmisPatchList.size() + " patches" : "null"));
        }
        this.cmisPatchList = cmisPatchList;
    }
}
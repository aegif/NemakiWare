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

import jp.aegif.nemaki.patch.AbstractNemakiPatch;

/**
 * Phase 2 CMIS Post-Initializer for NemakiWare
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
public class CMISPostInitializer implements ApplicationListener<ContextRefreshedEvent> {

    private static final Log log = LogFactory.getLog(CMISPostInitializer.class);

    private List<AbstractNemakiPatch> cmisPatchList;

    // AtomicBoolean to ensure patches are applied only once even if ContextRefreshedEvent fires multiple times
    private final AtomicBoolean initialized = new AtomicBoolean(false);

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
        log.error("*** CMISPostInitializer.onApplicationEvent() CALLED ***");
        log.error("*** Event source: " + event.getSource().getClass().getName() + " ***");

        // Ensure this runs only once
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        log.error("=== CMIS POST-INITIALIZATION (Phase 2) STARTED ===");
        log.error("Triggered by ContextRefreshedEvent - All Spring services are now fully initialized");

        try {
            // Phase 2: CMIS-specific patches that require running services
            // These patches should ONLY contain operations that require CMIS API access
            // Database-level operations should be handled by DatabasePreInitializer (Phase 1)

            if (cmisPatchList != null && !cmisPatchList.isEmpty()) {
                log.error("Applying " + cmisPatchList.size() + " CMIS patches (Phase 2 only)");
                applyCMISPatches();
            } else {
                log.error("No CMIS patches to apply in Phase 2");
            }

            log.error("=== CMIS POST-INITIALIZATION (Phase 2) COMPLETED ===");

        } catch (Exception e) {
            log.error("Phase 2 CMIS post-initialization failed", e);
            // Don't fail the entire startup process for CMIS patch failures
            // This allows the application to start even if some patches fail
        }
    }
    
    /**
     * Apply CMIS-specific patches using fully initialized services
     */
    private void applyCMISPatches() {
        for (AbstractNemakiPatch patch : cmisPatchList) {
            try {
                log.error("Applying CMIS patch: " + patch.getClass().getSimpleName());
                patch.apply();
                log.error("Successfully applied CMIS patch: " + patch.getClass().getSimpleName());
            } catch (Exception e) {
                log.error("Failed to apply CMIS patch: " + patch.getClass().getSimpleName(), e);
                // Continue with other patches even if one fails
            }
        }
    }

    /**
     * Set the list of CMIS patches to apply during initialization
     */
    public void setCmisPatchList(List<AbstractNemakiPatch> cmisPatchList) {
        log.error("*** CMISPostInitializer.setCmisPatchList() CALLED with " +
                 (cmisPatchList != null ? cmisPatchList.size() + " patches" : "NULL") + " ***");
        this.cmisPatchList = cmisPatchList;
    }
}
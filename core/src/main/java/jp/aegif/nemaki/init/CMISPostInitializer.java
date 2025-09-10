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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;

import jp.aegif.nemaki.patch.AbstractNemakiPatch;

/**
 * Phase 2 CMIS Post-Initializer for NemakiWare
 * 
 * This class handles CMIS-level initialization operations that require
 * fully initialized Spring services. It executes AFTER Spring context
 * initialization is complete and all service beans are available.
 * 
 * Phase 2 Operations (CMIS-based):
 * - Create initial folders using CMIS services
 * - Set up type definitions and relationships
 * - Apply CMIS-specific configuration patches
 * 
 * This phase executes AFTER Phase 1 (database setup) and full Spring
 * context initialization, ensuring all required services are available.
 */
public class CMISPostInitializer implements InitializingBean {
    
    private static final Log log = LogFactory.getLog(CMISPostInitializer.class);
    
    private List<AbstractNemakiPatch> cmisPatchList;
    
    /**
     * Execute Phase 2 CMIS initialization
     * 
     * This method is called by Spring after all bean properties have been set
     * and the Spring context is fully initialized, ensuring all CMIS services
     * are available for use.
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        log.info("=== CMIS POST-INITIALIZATION (Phase 2) STARTED ===");
        
        try {
            // Phase 2: CMIS-specific patches that require running services
            // These patches should ONLY contain operations that require CMIS API access
            // Database-level operations should be handled by DatabasePreInitializer (Phase 1)
            
            if (cmisPatchList != null && !cmisPatchList.isEmpty()) {
                log.info("Applying " + cmisPatchList.size() + " CMIS patches (Phase 2 only)");
                applyCMISPatches();
            } else {
                log.info("No CMIS patches to apply in Phase 2");
            }
            
            log.info("=== CMIS POST-INITIALIZATION (Phase 2) COMPLETED ===");
            
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
                log.info("Applying CMIS patch: " + patch.getClass().getSimpleName());
                patch.apply();
                log.info("Successfully applied CMIS patch: " + patch.getClass().getSimpleName());
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
        this.cmisPatchList = cmisPatchList;
    }
}
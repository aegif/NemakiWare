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
            log.info("*** NemakiPatchInitializationListener: Patches already applied, skipping ***");
            return;
        }

        log.info("=== NemakiPatchInitializationListener: STARTING PATCH INITIALIZATION ===");

        try {
            ServletContext servletContext = sce.getServletContext();

            // Get Spring WebApplicationContext
            WebApplicationContext springContext = WebApplicationContextUtils.getWebApplicationContext(servletContext);

            if (springContext == null) {
                log.error("Spring WebApplicationContext not found - cannot apply patches");
                return;
            }

            log.info("Spring WebApplicationContext retrieved successfully");

            // Apply patches in order
            applyPatchesFromSpringContext(springContext);

            log.info("=== NemakiPatchInitializationListener: PATCH INITIALIZATION COMPLETED ===");

        } catch (Exception e) {
            log.error("Failed to apply patches during servlet context initialization", e);
            // Don't fail the entire startup - log error and continue
        }
    }

    /**
     * Apply patches by retrieving them from Spring context
     */
    private void applyPatchesFromSpringContext(WebApplicationContext springContext) {
        try {
            // Patch execution order (CRITICAL: System folder must be first)
            String[] patchBeanNames = {
                "patch_SystemFolderSetup",       // Creates .system folder
                "patch_InitialContentSetup",     // Creates Sites and Technical Documents folders
                "patch_StandardCmisViews",       // Creates CMIS views
                "patch_TestUserInitialization",  // Creates test users
                "patch_McpServiceAccount"        // Creates MCP service account for API access
            };

            for (String beanName : patchBeanNames) {
                try {
                    log.info("Retrieving patch bean: " + beanName);

                    // Check if bean exists
                    if (!springContext.containsBean(beanName)) {
                        log.warn("Patch bean not found: " + beanName + " - skipping");
                        continue;
                    }

                    // Get patch bean
                    Object patchBean = springContext.getBean(beanName);

                    if (!(patchBean instanceof AbstractNemakiPatch)) {
                        log.error("Bean " + beanName + " is not an AbstractNemakiPatch - skipping");
                        continue;
                    }

                    AbstractNemakiPatch patch = (AbstractNemakiPatch) patchBean;

                    log.info("Applying patch: " + patch.getClass().getSimpleName());
                    patch.apply();
                    log.info("Successfully applied patch: " + patch.getClass().getSimpleName());

                } catch (Exception e) {
                    log.error("Failed to apply patch: " + beanName, e);
                    // Continue with other patches even if one fails
                }
            }

        } catch (Exception e) {
            log.error("Error during patch application", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        log.info("NemakiPatchInitializationListener: Context destroyed");
        // No cleanup needed
    }
}

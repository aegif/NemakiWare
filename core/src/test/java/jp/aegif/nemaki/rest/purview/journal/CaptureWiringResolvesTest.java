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
package jp.aegif.nemaki.rest.purview.journal;

import jp.aegif.nemaki.rest.ingest.capture.CaptureIntentStore;
import jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * The capture beans must actually resolve in a Spring context.
 *
 * <p>Every other test in this feature wires the collaborators with setters, so none of them ever
 * builds a context — and a wiring defect is invisible to all of them. That is not hypothetical:
 * registering the store under its concrete type AND under each of its two interfaces gave every
 * interface type two candidates, and {@code @Autowired(required = false)} tolerates absence but
 * not ambiguity. Injection points whose field name did not happen to match a bean name failed
 * with {@code NoUniqueBeanDefinitionException} — and since {@code serviceContext.xml} is loaded
 * by re-refreshing the root context, that is a WAR that does not start, with a green test suite
 * (external review).
 *
 * <h2>What this does NOT cover</h2>
 *
 * <p>It builds a context with the capture beans and consumers, not the product's real one. It
 * establishes that these definitions are resolvable and that both seams get the same instance;
 * it does not establish that {@code serviceContext.xml} as a whole loads.
 */
class CaptureWiringResolvesTest {

    /** The capture half of {@code LineageObligationWiringConfig}, with its collaborator stubbed. */
    @Configuration
    static class CaptureBeans {

        @Bean
        LineageJournalStore lineageJournalStore() {
            // An interface mock with the support interface mixed in. A mock of the CONCRETE class
            // would carry its @Autowired fields, and Spring would try to satisfy them — turning
            // this into a test of the journal store's own dependencies rather than of the capture
            // wiring.
            return mock(LineageJournalStore.class,
                    org.mockito.Mockito.withSettings().extraInterfaces(LineageStoreSupport.class));
        }

        @Bean
        LineageConfig lineageConfig() {
            return new LineageConfig();
        }

        @Bean
        CouchCaptureIntentStore couchCaptureIntentStore(LineageJournalStore journalStore,
                LineageConfig lineageConfig) {
            return new CouchCaptureIntentStore((LineageStoreSupport) journalStore, lineageConfig);
        }

        // NC: the shape that broke startup. Uncomment to see both tests fail.
        // @Bean CaptureIntentStore captureIntentStore(CouchCaptureIntentStore s) { return s; }
        // @Bean CaptureMaintenanceStore captureMaintenanceStore(CouchCaptureIntentStore s) { return s; }
    }

    /** Stands in for the three real consumers, using their field names. */
    @Configuration
    static class Consumers {

        @Autowired(required = false)
        CaptureIntentStore captureIntentStore;

        /** Deliberately NOT named after any bean: the name fallback must not be what saves it. */
        @Autowired(required = false)
        CaptureMaintenanceStore maintenanceStore;
    }

    @Test
    @DisplayName("both seams resolve, and to the same instance")
    void bothSeamsResolve() {
        try (AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(CaptureBeans.class, Consumers.class)) {

            Consumers consumers = ctx.getBean(Consumers.class);

            assertNotNull(consumers.captureIntentStore, "the ingest seam must resolve");
            assertNotNull(consumers.maintenanceStore, "the maintenance seam must resolve");
            assertSame(consumers.captureIntentStore, consumers.maintenanceStore,
                    "both seams must see the same instance, which is the whole reason the store "
                            + "implements both interfaces");
        }
    }

    @Test
    @DisplayName("resolving by interface type finds exactly one candidate")
    void exactlyOneCandidatePerInterface() {
        // The direct statement of what went wrong: two candidates, not zero.
        try (AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(CaptureBeans.class)) {

            org.junit.jupiter.api.Assertions.assertEquals(1,
                    ctx.getBeanNamesForType(CaptureIntentStore.class).length,
                    java.util.Arrays.toString(ctx.getBeanNamesForType(CaptureIntentStore.class)));
            org.junit.jupiter.api.Assertions.assertEquals(1,
                    ctx.getBeanNamesForType(CaptureMaintenanceStore.class).length,
                    java.util.Arrays.toString(
                            ctx.getBeanNamesForType(CaptureMaintenanceStore.class)));
        }
    }
}

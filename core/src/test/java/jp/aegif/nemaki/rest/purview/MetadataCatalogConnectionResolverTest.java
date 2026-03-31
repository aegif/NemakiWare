package jp.aegif.nemaki.rest.purview;

import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.journal.AtlasConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public class MetadataCatalogConnectionResolverTest {

    private PurviewConfig purviewConfig;
    private AtlasConfig atlasConfig;
    private MetadataCatalogConnectionResolver resolver;

    @BeforeEach
    public void setUp() {
        purviewConfig = mock(PurviewConfig.class);
        atlasConfig = mock(AtlasConfig.class);
        resolver = new MetadataCatalogConnectionResolver(purviewConfig, atlasConfig);
    }

    @Nested
    class IsAnyEnabled {

        @Test
        public void testReturnsFalseWhenBothDisabled() {
            when(purviewConfig.isEnabled()).thenReturn(false);
            when(atlasConfig.isEnabled()).thenReturn(false);
            assertFalse(resolver.isAnyEnabled());
        }

        @Test
        public void testReturnsTrueWhenPurviewEnabled() {
            when(purviewConfig.isEnabled()).thenReturn(true);
            when(atlasConfig.isEnabled()).thenReturn(false);
            assertTrue(resolver.isAnyEnabled());
        }

        @Test
        public void testReturnsTrueWhenAtlasEnabled() {
            when(purviewConfig.isEnabled()).thenReturn(false);
            when(atlasConfig.isEnabled()).thenReturn(true);
            assertTrue(resolver.isAnyEnabled());
        }

        @Test
        public void testReturnsTrueWhenBothEnabled() {
            when(purviewConfig.isEnabled()).thenReturn(true);
            when(atlasConfig.isEnabled()).thenReturn(true);
            assertTrue(resolver.isAnyEnabled());
        }
    }

    @Nested
    class ActiveBackend {

        @Test
        public void testReturnsPurviewWhenPurviewEnabled() {
            when(purviewConfig.isEnabled()).thenReturn(true);
            assertEquals(CatalogBackendKind.PURVIEW, resolver.activeBackend());
        }

        @Test
        public void testReturnsAtlasWhenOnlyAtlasEnabled() {
            when(purviewConfig.isEnabled()).thenReturn(false);
            when(atlasConfig.isEnabled()).thenReturn(true);
            assertEquals(CatalogBackendKind.ATLAS, resolver.activeBackend());
        }

        @Test
        public void testReturnsNoneWhenBothDisabled() {
            when(purviewConfig.isEnabled()).thenReturn(false);
            when(atlasConfig.isEnabled()).thenReturn(false);
            assertEquals(CatalogBackendKind.NONE, resolver.activeBackend());
        }

        @Test
        public void testPurviewTakesPrecedenceWhenBothEnabled() {
            when(purviewConfig.isEnabled()).thenReturn(true);
            when(atlasConfig.isEnabled()).thenReturn(true);
            assertEquals(CatalogBackendKind.PURVIEW, resolver.activeBackend());
        }
    }

    @Nested
    class BuildConnectionRequest {

        @Test
        public void testBuildsPurviewRequestWhenPurviewEnabled() {
            when(purviewConfig.isEnabled()).thenReturn(true);
            when(purviewConfig.getEndpoint()).thenReturn("https://account.purview.azure.com");
            when(purviewConfig.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
            when(purviewConfig.getTenantId()).thenReturn("tenant-123");
            when(purviewConfig.getClientId()).thenReturn("client-123");
            when(purviewConfig.getClientSecret()).thenReturn("secret-123");
            when(purviewConfig.getConnectTimeoutMs()).thenReturn(5000);
            when(purviewConfig.getReadTimeoutMs()).thenReturn(30000);

            PurviewConnectionRequest request = resolver.buildConnectionRequest();

            assertEquals("https://account.purview.azure.com", request.getEndpoint());
            assertEquals("datamap/api/atlas/v2", request.getAtlasBasePath());
            assertFalse(request.isBasicAuth());
            assertEquals("tenant-123", request.getTenantId());
            assertEquals("client-123", request.getClientId());
            assertEquals("secret-123", request.getClientSecret());
        }

        @Test
        public void testBuildsAtlasRequestWhenAtlasEnabled() {
            when(purviewConfig.isEnabled()).thenReturn(false);
            when(atlasConfig.isEnabled()).thenReturn(true);
            when(atlasConfig.getEndpoint()).thenReturn("http://localhost:21000");
            when(atlasConfig.getUsername()).thenReturn("admin");
            when(atlasConfig.getPassword()).thenReturn("admin");
            when(atlasConfig.getConnectTimeoutMs()).thenReturn(5000);
            when(atlasConfig.getReadTimeoutMs()).thenReturn(30000);

            PurviewConnectionRequest request = resolver.buildConnectionRequest();

            assertEquals("http://localhost:21000", request.getEndpoint());
            assertEquals("api/atlas/v2", request.getAtlasBasePath());
            assertTrue(request.isBasicAuth());
            assertEquals("admin", request.getBasicUsername());
            assertEquals("admin", request.getBasicPassword());
        }

        @Test
        public void testThrowsWhenNoBackendEnabled() {
            when(purviewConfig.isEnabled()).thenReturn(false);
            when(atlasConfig.isEnabled()).thenReturn(false);

            assertThrows(IllegalStateException.class, () -> resolver.buildConnectionRequest());
        }

        @Test
        public void testBuildsRequestWithCustomAtlasBasePath() {
            when(purviewConfig.isEnabled()).thenReturn(true);
            when(purviewConfig.getEndpoint()).thenReturn("https://account.purview.azure.com");
            when(purviewConfig.getTenantId()).thenReturn("tenant-123");
            when(purviewConfig.getClientId()).thenReturn("client-123");
            when(purviewConfig.getClientSecret()).thenReturn("secret-123");
            when(purviewConfig.getConnectTimeoutMs()).thenReturn(5000);
            when(purviewConfig.getReadTimeoutMs()).thenReturn(30000);

            PurviewConnectionRequest request = resolver.buildConnectionRequest("catalog/api/atlas/v2");

            assertEquals("catalog/api/atlas/v2", request.getAtlasBasePath());
        }
    }

    @Nested
    class IsAtlasOnPrem {

        @Test
        public void testReturnsTrueWhenAtlasIsActive() {
            when(purviewConfig.isEnabled()).thenReturn(false);
            when(atlasConfig.isEnabled()).thenReturn(true);
            assertTrue(resolver.isAtlasOnPrem());
        }

        @Test
        public void testReturnsFalseWhenPurviewIsActive() {
            when(purviewConfig.isEnabled()).thenReturn(true);
            assertFalse(resolver.isAtlasOnPrem());
        }

        @Test
        public void testReturnsFalseWhenNoneIsActive() {
            when(purviewConfig.isEnabled()).thenReturn(false);
            when(atlasConfig.isEnabled()).thenReturn(false);
            assertFalse(resolver.isAtlasOnPrem());
        }
    }

    @Nested
    class GetCollection {

        @Test
        public void testReturnsPurviewCollectionWhenPurviewActive() {
            when(purviewConfig.isEnabled()).thenReturn(true);
            when(purviewConfig.getCollection()).thenReturn("PurviewCollection");
            assertEquals("PurviewCollection", resolver.getCollection());
        }

        @Test
        public void testReturnsAtlasCollectionWhenAtlasActive() {
            when(purviewConfig.isEnabled()).thenReturn(false);
            when(atlasConfig.isEnabled()).thenReturn(true);
            when(atlasConfig.getCollection()).thenReturn("AtlasCollection");
            assertEquals("AtlasCollection", resolver.getCollection());
        }

        @Test
        public void testReturnsDefaultWhenNoneActive() {
            when(purviewConfig.isEnabled()).thenReturn(false);
            when(atlasConfig.isEnabled()).thenReturn(false);
            assertEquals("NemakiWare", resolver.getCollection());
        }
    }
}

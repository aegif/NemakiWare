package jp.aegif.nemaki.cmis.factory.info;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.BuildInfoResource;
import jp.aegif.nemaki.util.SpringPropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

/**
 * CMIS {@code productVersion} is the version this build was made from, not a literal in a
 * repositories file.
 *
 * <p>It used to be {@code product.version} in {@code repositories-default.yml}: written as 3.3.0
 * for the 3.3.0 release and never bumped, so 3.3.1 and 3.4.0 both told every CMIS client
 * (repositoryInfo, and the REST repository endpoint that copies it) that they were 3.3.0. Nothing
 * checked the literal against the build.
 *
 * <p>The fixture sets {@code product.version} in the default block (9.9.9) and in one repository
 * (8.8.8). A reading that honours the default block leaves no build version to compare with; one
 * that honours the per-repository value fails on {@code canopy}.
 */
class CmisProductVersionIsTheBuildVersionTest {

    @Test
    @DisplayName("every repository reports the build's version, whatever repositories.yml says")
    void productVersionIsTheBuildVersion() {
        String built = BuildInfoResource.getVersion();
        assertTrue(built.matches("\\d+\\.\\d+\\.\\d+.*"),
                "version.properties was not filtered on this classpath (got '" + built + "'), so "
                        + "this test cannot tell the build's version from a fallback");

        SpringPropertyManager pm = mock(SpringPropertyManager.class);
        when(pm.readValue(PropertyKey.REPOSITORY_DEFINITION_DEFAULT))
                .thenReturn("repository-info/repositories-default.yml");
        when(pm.readValue(PropertyKey.REPOSITORY_DEFINITION))
                .thenReturn("repository-info/repositories.yml");
        RepositoryInfoMap map = new RepositoryInfoMap();
        map.setPropertyManager(pm);
        map.init();

        for (String id : new String[] {"bedroom", "canopy"}) {
            RepositoryInfo info = map.get(id);
            assertNotNull(info, id + " was not loaded from the fixture");
            assertEquals(built, info.getProductVersion(),
                    id + " reports productVersion '" + info.getProductVersion() + "' but this build is "
                            + built);
        }
    }
}

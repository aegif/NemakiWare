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
package jp.aegif.nemaki.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * Whether this instance can actually serve requests yet.
 *
 * <h2>Why "the port is open" is not the same as "ready"</h2>
 *
 * <p>Tomcat accepts connections well before this application can answer a CMIS call. Measured on
 * a restarted core: sequential probes saw the first response after 10.3 seconds, and sixteen
 * concurrent requests arriving fourteen seconds in were all made to wait 8.3 seconds — the type
 * cache is built on first use, under a process-wide lock, so everyone queues behind whoever
 * triggered it. A load balancer whose health check is a TCP connect (which is what
 * docker-compose shipped) puts a replica into rotation right into that window, so a rolling
 * deploy hands real users the stall.
 *
 * <p>So this endpoint does not report liveness, it reports readiness, and it reports it by doing
 * the work rather than by asking a flag: resolving a base type definition forces the same
 * initialisation a real request would, so the first probe pays the cost and later probes are
 * cheap. Until that succeeds the answer is 503, which is what keeps the replica out of rotation.
 *
 * <h2>Why it says so little</h2>
 *
 * <p>This is reachable without credentials — a load balancer has none — so it answers with a
 * status and nothing else. The detailed check (CouchDB URL, memory, lineage) stays behind
 * authentication at {@code /api/v1/cmis/health}; a probe needs a boolean, not an inventory of
 * the deployment.
 *
 * <p>{@code GET /rest/all/readiness} → {@code 200 {"status":"ready"}} or
 * {@code 503 {"status":"starting"}}.
 */
@Path("/all/readiness")
public class ReadinessResource extends ResourceBase {

    private static final Log log = LogFactory.getLog(ReadinessResource.class);

    /** Any base type will do; it is resolved through the same cache every response uses. */
    private static final String PROBE_TYPE = "cmis:document";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getReadiness() {
        try {
            RepositoryInfoMap repositoryInfoMap = SpringContext.getApplicationContext()
                    .getBean("repositoryInfoMap", RepositoryInfoMap.class);
            String repositoryId = repositoryInfoMap.getDefaultRepositoryId();
            if (repositoryId == null || repositoryId.trim().isEmpty()) {
                return notReady("repositories are not configured yet");
            }

            TypeManager typeManager = SpringContext.getApplicationContext()
                    .getBean("typeManager", TypeManager.class);
            if (typeManager.getTypeDefinition(repositoryId, PROBE_TYPE) == null) {
                return notReady("the type cache is not built yet");
            }

            return Response.ok("{\"status\":\"ready\"}").build();
        } catch (Exception e) {
            // Beans not wired, CouchDB unreachable, type generation failing — all of them mean the
            // same thing to a load balancer, and none of them should leak out of here.
            log.info("Readiness probe answered 'starting': " + e.getClass().getSimpleName());
            return notReady("initialisation is still in progress");
        }
    }

    private Response notReady(String reason) {
        if (log.isDebugEnabled()) {
            log.debug("Readiness probe answered 'starting': " + reason);
        }
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity("{\"status\":\"starting\"}")
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}

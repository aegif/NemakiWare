package jp.aegif.nemaki.api.v1.resource;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.v1.model.response.JobControlResponse;
import org.apache.chemistry.opencmis.commons.server.CallContext;

@Path("/repo/{repositoryId}/jobs")
public class JobControlResource {

    private static volatile boolean jobsPaused = false;

    private boolean isAdmin(HttpServletRequest request) {
        CallContext callContext = (CallContext) request.getAttribute("CallContext");
        if (callContext == null) return false;
        Boolean isAdmin = (Boolean) callContext.get(jp.aegif.nemaki.util.constant.CallContextKey.IS_ADMIN);
        return isAdmin != null && isAdmin;
    }

    @POST
    @Path("/pause")
    @Produces(MediaType.APPLICATION_JSON)
    public Response pauseJobs(@PathParam("repositoryId") String repositoryId,
            @Context HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity("{\"error\":\"Admin access required\"}").build();
        }
        jobsPaused = true;
        
        JobControlResponse response = new JobControlResponse();
        response.setStatus("paused");
        response.setMessage("Jobs have been paused for repository: " + repositoryId);
        response.setPendingJobs(getPendingJobCount());
        response.setRunningJobs(getRunningJobCount());
        
        return Response.ok(response).build();
    }

    @POST
    @Path("/resume")
    @Produces(MediaType.APPLICATION_JSON)
    public Response resumeJobs(@PathParam("repositoryId") String repositoryId,
            @Context HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity("{\"error\":\"Admin access required\"}").build();
        }
        jobsPaused = false;
        
        JobControlResponse response = new JobControlResponse();
        response.setStatus("running");
        response.setMessage("Jobs have been resumed for repository: " + repositoryId);
        response.setPendingJobs(getPendingJobCount());
        response.setRunningJobs(getRunningJobCount());
        
        return Response.ok(response).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getJobStatus(@PathParam("repositoryId") String repositoryId,
            @Context HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity("{\"error\":\"Admin access required\"}").build();
        }
        JobControlResponse response = new JobControlResponse();
        response.setStatus(jobsPaused ? "paused" : "running");
        response.setMessage("Job status for repository: " + repositoryId);
        response.setPendingJobs(getPendingJobCount());
        response.setRunningJobs(getRunningJobCount());
        
        return Response.ok(response).build();
    }

    public static boolean isJobsPaused() {
        return jobsPaused;
    }

    private int getPendingJobCount() {
        // TODO: Integrate with actual job queue
        return 0;
    }

    private int getRunningJobCount() {
        // TODO: Integrate with actual job execution tracking
        return 0;
    }
}

package jp.aegif.nemaki.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/test")
public class SimpleTestResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response test() {
        return Response.ok("Jersey REST endpoint is working!").build();
    }

    @GET
    @Path("/json")
    @Produces(MediaType.APPLICATION_JSON)
    public Response testJson() {
        return Response.ok("{\"status\":\"working\",\"message\":\"Jersey REST is functional\"}").build();
    }
}
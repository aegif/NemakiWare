package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.PasswordPolicyService;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/config/password-policy")
@Tag(name = "config", description = "Configuration operations")
@Produces(MediaType.APPLICATION_JSON)
public class PasswordPolicyResource {

    private static final Logger logger = Logger.getLogger(PasswordPolicyResource.class.getName());
    private static final String KEY_MIN_LENGTH = "password.policy.minLength";

    @Autowired
    private PasswordPolicyService passwordPolicyService;

    @Autowired
    private ContentDaoService contentDaoService;

    @Context
    private HttpServletRequest httpRequest;

    @GET
    @Operation(summary = "Get password policy", description = "Returns the current password policy settings")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Password policy returned")
    })
    public Response getPolicy(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {

        int minLength = passwordPolicyService.getMinLength(repositoryId);
        return Response.ok(Map.of("minLength", minLength)).build();
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update password policy", description = "Updates the password policy settings (admin only)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Password policy updated"),
            @ApiResponse(responseCode = "400", description = "Invalid value"),
            @ApiResponse(responseCode = "403", description = "Admin authorization required")
    })
    public Response updatePolicy(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            Map<String, Object> body) {

        checkAdminAuthorization();

        Object minLengthObj = body.get("minLength");
        if (minLengthObj == null) {
            throw ApiException.invalidArgument("minLength is required");
        }

        int minLength;
        try {
            minLength = ((Number) minLengthObj).intValue();
        } catch (ClassCastException e) {
            throw ApiException.invalidArgument("minLength must be a number");
        }

        if (minLength < 0) {
            throw ApiException.invalidArgument("minLength must be >= 0");
        }

        try {
            Configuration conf = contentDaoService.getConfiguration(repositoryId);
            Map<String, Object> map = conf.getConfiguration();
            map.put(KEY_MIN_LENGTH, String.valueOf(minLength));
            conf.setConfiguration(map);
            contentDaoService.update(repositoryId, conf);
        } catch (Exception e) {
            logger.severe("Failed to update password policy: " + e.getMessage());
            throw ApiException.internalError("Failed to update password policy: " + e.getMessage(), e);
        }

        return Response.ok(Map.of("minLength", minLength)).build();
    }

    private void checkAdminAuthorization() {
        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null) {
            throw ApiException.permissionDenied("Authentication required");
        }
        Boolean isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
        if (isAdmin == null || !isAdmin) {
            throw ApiException.permissionDenied("Admin authorization required");
        }
    }
}

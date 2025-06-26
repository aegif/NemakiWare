package jp.aegif.nemaki.rest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Path("/repo/{repositoryId}/actions")
@Component
public class ActionResource extends ResourceBase {
    
    private static final Logger log = LoggerFactory.getLogger(ActionResource.class);

    @GET
    @Path("/discover/{objectId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response discoverActions(@PathParam("repositoryId") String repositoryId,
                                   @PathParam("objectId") String objectId,
                                   @Context HttpServletRequest httpRequest) {
        try {
            List<Map<String, Object>> actionDefinitions = new ArrayList<>();
            
            Map<String, Object> sampleAction = new HashMap<>();
            sampleAction.put("id", "sample-document-action");
            sampleAction.put("title", "サンプルのアクション");
            sampleAction.put("description", "アクション機能のためのサンプルです");
            sampleAction.put("triggerType", "UserButton");
            sampleAction.put("canExecute", true);
            sampleAction.put("fontAwesome", "fa fa-fire");
            actionDefinitions.add(sampleAction);
            
            return Response.ok(actionDefinitions).build();
            
        } catch (Exception e) {
            log.error("Error discovering actions", e);
            return Response.status(500).entity("Internal server error").build();
        }
    }

    @GET
    @Path("/{actionId}/form/{objectId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getActionForm(@PathParam("repositoryId") String repositoryId,
                                 @PathParam("actionId") String actionId,
                                 @PathParam("objectId") String objectId,
                                 @Context HttpServletRequest httpRequest) {
        try {
            if (!"sample-document-action".equals(actionId)) {
                return Response.status(404).entity("Action not found").build();
            }
            
            Map<String, Object> formDef = new HashMap<>();
            formDef.put("actionId", actionId);
            formDef.put("title", "サンプルのアクション");
            
            List<Map<String, Object>> fields = new ArrayList<>();
            
            Map<String, Object> selectField = new HashMap<>();
            selectField.put("name", "sampleFormData");
            selectField.put("type", "select");
            selectField.put("label", "サンプル選択");
            selectField.put("required", true);
            
            List<Map<String, String>> options = new ArrayList<>();
            Map<String, String> option1 = new HashMap<>();
            option1.put("value", "1");
            option1.put("label", "テスト1");
            options.add(option1);
            
            Map<String, String> option2 = new HashMap<>();
            option2.put("value", "2");
            option2.put("label", "テスト2");
            options.add(option2);
            
            Map<String, String> option3 = new HashMap<>();
            option3.put("value", "3");
            option3.put("label", "テスト3");
            options.add(option3);
            
            selectField.put("options", options);
            fields.add(selectField);
            
            Map<String, Object> textField = new HashMap<>();
            textField.put("name", "sampleTextboxData");
            textField.put("type", "text");
            textField.put("label", "テキスト入力");
            textField.put("required", false);
            fields.add(textField);
            
            formDef.put("fields", fields);
            
            return Response.ok(formDef).build();
            
        } catch (Exception e) {
            log.error("Error getting action form", e);
            return Response.status(500).entity("Internal server error").build();
        }
    }

    @POST
    @Path("/{actionId}/execute/{objectId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response executeAction(@PathParam("repositoryId") String repositoryId,
                                 @PathParam("actionId") String actionId,
                                 @PathParam("objectId") String objectId,
                                 String formData,
                                 @Context HttpServletRequest httpRequest) {
        try {
            if (!"sample-document-action".equals(actionId)) {
                return Response.status(404).entity("Action not found").build();
            }
            
            log.info("Executing sample action for object: " + objectId + " with data: " + formData);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "CMISオブジェクトアクションが実行されました オブジェクトID: " + objectId);
            response.put("data", formData);
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            log.error("Error executing action", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Action execution failed: " + e.getMessage());
            return Response.status(500).entity(errorResponse).build();
        }
    }
}

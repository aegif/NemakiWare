package jp.aegif.nemaki.rest;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import jp.aegif.nemaki.AppConfig;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.plugin.action.JavaBackedAction;


@Path("/repo/{repositoryId}/action/{actionId}")
public class ActionResource extends ResourceBase {
	private static final Log log = LogFactory
            .getLog(ActionResource.class);

	private ContentService contentService;

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	private CompileService compileService;

	public void setCompileService(CompileService compileService) {
		this.compileService = compileService;
	}

	@SuppressWarnings("unchecked")
	@POST
	@Path("/do")
	@Produces(MediaType.APPLICATION_JSON)
	public String execute(
			@PathParam("repositoryId") String repositoryId,
			@PathParam("actionId") String actionId,
			@QueryParam("objectId") String objectId,
			@QueryParam("param") String param,
			@Context HttpServletRequest httpRequest){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray list = new JSONArray();
		JSONArray errMsg = new JSONArray();

		//読みこまれたプラグインからActionIdが等しいものをさがす
        try (GenericApplicationContext context = new AnnotationConfigApplicationContext(AppConfig.class)) {
        	JavaBackedAction product = context.getBean(JavaBackedAction.class);


		//無ければスキップ
		CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
		ObjectData object = compileService.compileObjectData(callContext,
				repositoryId, contentService.getContent(repositoryId, objectId), null,
				false, IncludeRelationships.NONE, null, false);

		//実行して結果を返す


        product.executeAction(object);
    }


		return "hoge";
	}
}

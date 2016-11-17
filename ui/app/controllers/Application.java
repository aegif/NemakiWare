package controllers;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;

import com.fasterxml.jackson.databind.JsonNode;

import constant.Token;
import model.Login;
import play.Routes;
import play.data.*;
import play.mvc.Controller;
import play.mvc.Result;
import util.Util;
import views.html.login;

public class Application extends Controller{

	public static Result login(String repositoryId) {
	    return ok(login.render(repositoryId, Form.form(Login.class)));
	}

	public static Result authenticate(String repositoryId){
		Form<Login> formData = Form.form(Login.class);
		formData = formData.bindFromRequest();
		if(formData.hasErrors())
			return badRequest(login.render(repositoryId, formData));

		Login loginModel = formData.get();
		session().clear();
		session(Token.LOGIN_USER_ID, loginModel.id);
		session(Token.LOGIN_USER_PASSWORD, loginModel.password);
		session(Token.LOGIN_USER_IS_ADMIN, String.valueOf(isAdmin(repositoryId, loginModel.id)));
		session(Token.LOGIN_REPOSITORY_ID, repositoryId);
		session(Token.NEMAKIWARE_VERSION,getVersion(repositoryId));
		return redirect(routes.Node.index(repositoryId));
	}
	public static String getVersion(String repositoryId){
		Session session = CmisSessions.getCmisSession(repositoryId, session());
		RepositoryInfo repo = session.getRepositoryInfo();
		return repo.getProductVersion();
	}

	private static boolean isAdmin(String repositoryId, String id){
		boolean isAdmin = false;

		String coreRestUri = Util.buildNemakiCoreUri() + "rest/";
		String endPoint = coreRestUri + "repo/" + repositoryId + "/user/";

		try{
			JsonNode result = Util.getJsonResponse(session(), endPoint + "show/" + id);
			if("success".equals(result.get("status").asText())){
				JsonNode _user = result.get("user");
				model.User user = new model.User(_user);

				isAdmin = user.isAdmin;
			}
		}catch(Exception e){
			//TODO logging
			System.out.println("This user is not returned in REST API:" + id);
		}

		return isAdmin;
	}

	public static Result logout(String repositoryId){
		//CMIS session
		CmisSessions.disconnect(repositoryId, session());

		//Play session
		session().remove("loginUserId");

		return redirect(routes.Application.login(repositoryId));
	}

	public static Result error(){
		return ok(views.html.error.render());
	}

	public static Result jsRoutes() {
		response().setContentType("text/javascript");
		return ok(
			Routes.javascriptRouter("jsRoutes",
				controllers.routes.javascript.Node.showDetail(),
				controllers.routes.javascript.Node.showProperty(),
				controllers.routes.javascript.Node.showFile(),
				controllers.routes.javascript.Node.showPreview(),
				controllers.routes.javascript.Node.showVersion(),
				controllers.routes.javascript.Node.showPermission(),
				controllers.routes.javascript.Node.showRelationship(),
				controllers.routes.javascript.Node.showRelationshipCreate(),
				controllers.routes.javascript.Node.showAction(),

				controllers.routes.javascript.Node.getAce(),
				controllers.routes.javascript.Node.update(),
				controllers.routes.javascript.Node.delete(),

				controllers.routes.javascript.Archive.index(),
				controllers.routes.javascript.Archive.restore(),
				controllers.routes.javascript.Archive.destroy(),

				controllers.routes.javascript.Node.deleteByBatch(),
				controllers.routes.javascript.Node.checkOut(),
				controllers.routes.javascript.Node.checkOutByBatch(),
				controllers.routes.javascript.Node.cancelCheckOut(),
				controllers.routes.javascript.Node.cancelCheckOutByBatch(),
				controllers.routes.javascript.Node.downloadAsCompressedFile(),
				controllers.routes.javascript.Node.downloadAsCompressedFileByBatch(),

				controllers.routes.javascript.Node.createRelationToNew(),
				controllers.routes.javascript.Node.createRelationToExisting(),

				controllers.routes.javascript.Type.showBlank(),
				controllers.routes.javascript.Type.edit(),
				controllers.routes.javascript.Type.delete(),

				controllers.routes.javascript.User.showDetail(),
				controllers.routes.javascript.User.delete(),
				controllers.routes.javascript.User.showPasswordChanger(),

				controllers.routes.javascript.Group.showDetail(),
				controllers.routes.javascript.Group.delete(),

				controllers.routes.javascript.SearchEngine.index(),
				controllers.routes.javascript.SearchEngine.init(),
				controllers.routes.javascript.SearchEngine.reindex(),

				controllers.routes.javascript.Config.index(),
				controllers.routes.javascript.Config.showDetail()

			)
		);
	}

}

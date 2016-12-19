package controllers;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;

import constant.PropertyKey;
import constant.Token;
import model.Login;
import play.Routes;
import play.data.*;
import play.mvc.Controller;
import play.mvc.Result;
import util.NemakiConfig;
import util.Util;
import views.html.login;

public class Application extends Controller {

	public static Result login(String repositoryId) {
		return ok(login.render(repositoryId, Form.form(Login.class)));
	}

	public static Result authenticate(String repositoryId) {
		Form<Login> formData = Form.form(Login.class);
		formData = formData.bindFromRequest();
		if (formData.hasErrors())
			return badRequest(login.render(repositoryId, formData));

		Login loginModel = formData.get();
		session().clear();
		session(Token.LOGIN_USER_ID, loginModel.id);
		session(Token.LOGIN_USER_PASSWORD, loginModel.password);
		session(Token.LOGIN_USER_IS_ADMIN, String.valueOf(Util.isAdmin(repositoryId, loginModel.id, session())));
		session(Token.LOGIN_REPOSITORY_ID, repositoryId);
		session(Token.NEMAKIWARE_VERSION, Util.getVersion(repositoryId, session()));
		return redirect(routes.Node.index(repositoryId));
	}

	public static Result logout(String repositoryId) {
		// CMIS session
		CmisSessions.disconnect(repositoryId, session());

		// Play session
		session().remove("loginUserId");

		String logoutUri = NemakiConfig.getValue(PropertyKey.SSO_LOGOUT_REDIRECT_URI);
		if(StringUtils.isBlank(logoutUri)){
			return redirect(routes.Application.login(repositoryId));
		}else{
			return redirect(logoutUri);
		}
	}

	public static Result error() {
		return ok(views.html.error.render());
	}

	public static Result jsRoutes() {
		response().setContentType("text/javascript");
		return ok(Routes.javascriptRouter("jsRoutes", controllers.routes.javascript.Node.showDetail(),
				controllers.routes.javascript.Node.showProperty(), controllers.routes.javascript.Node.showFile(),
				controllers.routes.javascript.Node.showPreview(), controllers.routes.javascript.Node.showVersion(),
				controllers.routes.javascript.Node.showPermission(),
				controllers.routes.javascript.Node.showRelationship(),
				controllers.routes.javascript.Node.showRelationshipCreate(),
				controllers.routes.javascript.Node.showAction(),

				controllers.routes.javascript.Node.getAce(), controllers.routes.javascript.Node.update(),
				controllers.routes.javascript.Node.delete(),

				controllers.routes.javascript.Archive.index(), controllers.routes.javascript.Archive.restore(),
				controllers.routes.javascript.Archive.destroy(),

				controllers.routes.javascript.Node.deleteByBatch(), controllers.routes.javascript.Node.checkOut(),
				controllers.routes.javascript.Node.checkOutByBatch(),
				controllers.routes.javascript.Node.cancelCheckOut(),
				controllers.routes.javascript.Node.cancelCheckOutByBatch(),
				controllers.routes.javascript.Node.checkIn(), controllers.routes.javascript.Node.checkInPWC(),
				controllers.routes.javascript.Node.checkInPWCByBatch(),
				controllers.routes.javascript.Node.downloadWithRelationTargetAsCompressedFile(),
				controllers.routes.javascript.Node.downloadAsCompressedFile(),
				controllers.routes.javascript.Node.downloadAsCompressedFileByBatch(),

				controllers.routes.javascript.Node.createRelationToNew(),
				controllers.routes.javascript.Node.createRelationToExisting(),

				controllers.routes.javascript.Type.showBlank(), controllers.routes.javascript.Type.edit(),
				controllers.routes.javascript.Type.delete(),

				controllers.routes.javascript.User.showDetail(), controllers.routes.javascript.User.delete(),
				controllers.routes.javascript.User.showPasswordChanger(),

				controllers.routes.javascript.Group.showDetail(), controllers.routes.javascript.Group.delete(),

				controllers.routes.javascript.SearchEngine.index(), controllers.routes.javascript.SearchEngine.init(),
				controllers.routes.javascript.SearchEngine.reindex(),

				controllers.routes.javascript.Config.index(), controllers.routes.javascript.Config.showDetail()

		));
	}

}

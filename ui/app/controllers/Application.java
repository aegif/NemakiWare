package controllers;

import com.google.inject.Inject;

import java.util.List;
import java.util.Optional;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.commons.lang3.StringUtils;
import org.pac4j.http.client.indirect.FormClient;

import play.Logger;
import play.Logger.ALogger;

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
import org.pac4j.core.config.Config;
import org.pac4j.core.profile.ProfileManager;
import org.pac4j.play.PlayWebContext;
import org.pac4j.play.java.Secure;
import play.mvc.Http.Context;
public class Application extends Controller {
	private static final ALogger logger = Logger.of(Application.class);

    @Inject
    public  Config config;

	public Result login(String repositoryId) {
		final FormClient formClient = (FormClient) config.getClients().findClient("FormClient");

		return ok(login.render(repositoryId, formClient.getCallbackUrl()));
	}


	public Result logout(String repositoryId) {
		// CMIS session
		CmisSessions.disconnect(repositoryId, ctx());

		// Play session
		session().clear();

		return redirect(routes.Application.login(repositoryId));
	}

	public Result error() {
		return ok(views.html.error.render());
	}

	public Result jsRoutes() {
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

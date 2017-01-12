package controllers;

import com.google.inject.Inject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
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
import util.authentication.NemakiProfile;

import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.client.RedirectAction;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.Pac4jConstants;
import org.pac4j.core.exception.HttpAction;
import org.pac4j.play.PlayWebContext;
import org.pac4j.play.java.Secure;
import org.pac4j.saml.client.SAML2Client;
import org.pac4j.saml.metadata.SAML2MetadataResolver;

import play.mvc.Http.Context;

public class Application extends Controller {
	private static final ALogger logger = Logger.of(Application.class);

	@Inject
	public Config config;

	@Secure(clients = "SAML2Client")
	public Result samlLogin() {
		final PlayWebContext context = new PlayWebContext(ctx());
		String repositoryId = util.Util.getRepositoryId(context);
		return redirect(routes.Node.index(repositoryId));
	}

	public Result adminlogin(String repositoryId) {
		final PlayWebContext context = new PlayWebContext(ctx());

		//ログイン画面に直接来た場合など、ログイン後のリダイレクト先の設定をしておかないとエラーになる
		final Object uri = context.getSessionAttribute(Pac4jConstants.REQUESTED_URL);
		if (uri == null){
			String redircetURL = routes.Node.index(repositoryId).absoluteURL(request());
			context.setSessionAttribute(Pac4jConstants.REQUESTED_URL, redircetURL);
		}

		Clients clients = config.getClients();

		final FormClient formClient = (FormClient) clients.findClient("FormClient");
		String message = ctx().request().getQueryString("error");

		return ok(views.html.login.render(repositoryId, formClient.getCallbackUrl() , message));
	}
	public Result login() {
		return login(NemakiConfig.getDefualtRepositoryId());
	}

	public Result adminlogin() {
		return adminlogin(NemakiConfig.getDefualtRepositoryId());
	}

	public Result login(String repositoryId) {
		final PlayWebContext context = new PlayWebContext(ctx());

		//ログイン画面に直接来た場合など、ログイン後のリダイレクト先の設定をしておかないとエラーになる
		final Object uri = context.getSessionAttribute(Pac4jConstants.REQUESTED_URL);
		if (uri == null){
			String redircetURL = routes.Node.index(repositoryId).absoluteURL(request());
			context.setSessionAttribute(Pac4jConstants.REQUESTED_URL, redircetURL);
		}

		Clients clients = config.getClients();

		List<Client> clientList = clients.findAllClients();
		final SAML2Client samlClient = clientList.stream().filter(p -> p.getName().equals("SAML2Client")).map(p -> (SAML2Client)p).findFirst().orElse(null);
		if( samlClient != null){
			return redirect(routes.Application.samlLogin());
		}
		final FormClient formClient = (FormClient) clients.findClient("FormClient");
		String message = ctx().request().getQueryString("error");

		return ok(views.html.login.render(repositoryId, formClient.getCallbackUrl() , message));
	}

	public Result getSaml2ServiceProviderMetadata() {
		final PlayWebContext context = new PlayWebContext(ctx());
		final SAML2Client saml2Client = (SAML2Client) config.getClients().findClient("SAML2Client");
		saml2Client.init(context);

		SAML2MetadataResolver resolver = saml2Client.getServiceProviderMetadataResolver();
		if (resolver != null) {
			resolver.resolve();
			String metadata = resolver.getMetadata();

			response().setContentType(ContentType.APPLICATION_XML.getMimeType());
			return ok(metadata);
		} else {
			return notFound();
		}
	}
	public Result logout(String repositoryId) {
		ctx().session().clear();

		return ok(views.html.logout.render(repositoryId));
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

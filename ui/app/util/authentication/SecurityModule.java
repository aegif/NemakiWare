package util.authentication;

import com.google.inject.AbstractModule;
import java.io.File;

import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;
import org.pac4j.core.authorization.authorizer.RequireAnyRoleAuthorizer;
import org.pac4j.play.ApplicationLogoutController;
import org.pac4j.play.CallbackController;
import org.pac4j.http.client.indirect.FormClient;
import org.pac4j.saml.client.SAML2Client;
import org.pac4j.saml.client.SAML2ClientConfiguration;
import play.Configuration;
import play.Environment;
import play.cache.CacheApi;

public class SecurityModule extends AbstractModule{
    private final Configuration configuration;

    public SecurityModule(final Environment environment, final Configuration configuration) {
        this.configuration = configuration;
    }

	@Override
	protected void configure() {

		FormClient formClient = new FormClient("http://localhost:9001/ui/repo/bedroom/login", new NemakiAuthenticator());
	    formClient.setUsernameParameter("userId");

	    /*
	    SAML2ClientConfiguration cfg = new SAML2ClientConfiguration("resource:samlKeystore.jks",
	                    "pac4j-demo-passwd", "pac4j-demo-passwd", "resource:openidp-feide.xml");
	    cfg.setMaximumAuthenticationLifetime(3600);
	    cfg.setServiceProviderEntityId("urn:mace:saml:pac4j.org");
	    cfg.setServiceProviderMetadataPath(new File("target", "sp-metadata.xml").getAbsolutePath());
	    SAML2Client saml2Client = new SAML2Client(cfg);
	    */

	    Clients clients = new Clients("http://localhost:9001/ui/callback", formClient /* ,saml2Client */);

        final Config config = new Config(clients);
        config.addAuthorizer("admin", new RequireAnyRoleAuthorizer<>("ROLE_ADMIN"));
        config.setHttpActionAdapter(new NemakiHttpActionAdapter());
        bind(Config.class).toInstance(config);

        // callback
        final CallbackController callbackController = new CallbackController();
        callbackController.setDefaultUrl("http://localhost:9001/ui/repo/bedroom/");
        callbackController.setMultiProfile(true);
        bind(CallbackController.class).toInstance(callbackController);

        // logout
        final ApplicationLogoutController logoutController = new ApplicationLogoutController();
        logoutController.setDefaultUrl("/?defaulturlafterlogout");
        bind(ApplicationLogoutController.class).toInstance(logoutController);

	}

}

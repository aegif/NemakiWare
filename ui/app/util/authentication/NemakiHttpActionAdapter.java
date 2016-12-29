package util.authentication;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.commons.lang3.StringUtils;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.HttpConstants;
import org.pac4j.core.context.Pac4jConstants;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.ProfileManager;
import org.pac4j.http.client.indirect.FormClient;
import org.pac4j.play.PlayWebContext;
import org.pac4j.play.http.DefaultHttpActionAdapter;
import org.pac4j.saml.profile.SAML2Profile;

import com.google.inject.Inject;

import constant.Token;
import controllers.routes;
import play.mvc.Result;
import util.Util;

import static play.mvc.Results.*;

import java.util.Optional;

public class NemakiHttpActionAdapter extends DefaultHttpActionAdapter {
	@Inject
	public Config config;

    @Override
    public Result adapt(int code, PlayWebContext context) {
    	String uri = context.getFullRequestURL();
    	String repositoryId = (String)context.getSessionAttribute(Token.LOGIN_REPOSITORY_ID);
    	if (StringUtils.isBlank(repositoryId)){
    		repositoryId = Util.extractRepositoryId(uri);
    		context.setSessionAttribute(Token.LOGIN_REPOSITORY_ID, repositoryId);
    	}

        if (code == HttpConstants.UNAUTHORIZED) {
            context.setSessionAttribute(Pac4jConstants.REQUESTED_URL, uri);
            return  redirect(routes.Application.login(repositoryId));
        } else if (code == HttpConstants.FORBIDDEN) {
            return forbidden("403 FORBIDDEN");
        } else {
        	//formClientの場合、NemakiAuthenticatorで認証した瞬間にProfileを作ることが出来るがSMALの場合はそういうタイミングがないため、ここで無理矢理コンバートしている。
    		final ProfileManager<CommonProfile> profileManager = new ProfileManager<>(context);
    		final Optional<CommonProfile> profile = profileManager.get(true);
    		CommonProfile commonProfile = profile.orElse(null);
    		if(commonProfile instanceof SAML2Profile){
    			profileManager.save(true, NemakiProfile.ConvertSAML2ToNemakiProfile((SAML2Profile)commonProfile, repositoryId), false);
    		}

            return super.adapt(code, context);
        }
    }

}

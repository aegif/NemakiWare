package util.authentication;



import static play.mvc.Results.*;

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.HttpConstants;
import org.pac4j.core.context.Pac4jConstants;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.ProfileManager;
import org.pac4j.play.PlayWebContext;
import org.pac4j.play.http.DefaultHttpActionAdapter;
import org.pac4j.saml.profile.SAML2Profile;

import com.google.inject.Inject;

import constant.Token;
import controllers.routes;
import play.Logger;
import play.Logger.ALogger;
import play.i18n.Messages;
import play.mvc.Result;
import util.Util;

public class NemakiHttpActionAdapter extends DefaultHttpActionAdapter {
	private static final ALogger logger = Logger.of(NemakiHttpActionAdapter.class);

	@Inject
	public Config config;

	@Override
	public Result adapt(int code, PlayWebContext context) {
		String uri = context.getFullRequestURL();
		String repositoryId = (String) context.getSessionAttribute(Token.LOGIN_REPOSITORY_ID);
		if (StringUtils.isBlank(repositoryId)) {
			repositoryId = Util.getRepositoryId(context);
			context.setSessionAttribute(Token.LOGIN_REPOSITORY_ID, repositoryId);
		}

		if (code == HttpConstants.UNAUTHORIZED) {
			context.setSessionAttribute(Pac4jConstants.REQUESTED_URL, uri);
			return redirect(routes.Application.login(repositoryId));
		} else if (code == HttpConstants.FORBIDDEN) {
			return forbidden("403 FORBIDDEN");
		} else {
			// formClientの場合、NemakiAuthenticatorで認証した瞬間にProfileを作ることが出来るがSMALの場合はそういうタイミングがないため、ここで無理矢理コンバートしている。
			final ProfileManager<CommonProfile> profileManager = new ProfileManager<>(context);
			final Optional<CommonProfile> profile = profileManager.get(true);
			CommonProfile commonProfile = profile.orElse(null);
			if (commonProfile instanceof SAML2Profile) {
				try {
					NemakiProfile nemakiProfile = NemakiProfile.ConvertSAML2ToNemakiProfile((SAML2Profile) commonProfile, repositoryId);
					profileManager.save(true, nemakiProfile ,false);
				} catch (Exception ex) {
					String message = Messages.get("view.auth.login.error.saml.mismatch");
					logger.error(message, ex);
					return redirect(routes.Application.logout(repositoryId, message));
				}
			}
		}
		return super.adapt(code, context);

	}

}

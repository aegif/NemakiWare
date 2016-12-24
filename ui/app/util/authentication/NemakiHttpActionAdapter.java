package util.authentication;

import org.apache.chemistry.opencmis.client.api.Session;
import org.pac4j.core.context.HttpConstants;
import org.pac4j.core.context.Pac4jConstants;
import org.pac4j.play.PlayWebContext;
import org.pac4j.play.http.DefaultHttpActionAdapter;
import play.mvc.Result;
import util.Util;

import static play.mvc.Results.*;

public class NemakiHttpActionAdapter extends DefaultHttpActionAdapter {

    @Override
    public Result adapt(int code, PlayWebContext context) {
        if (code == HttpConstants.UNAUTHORIZED) {
            context.getJavaContext().session().remove(Pac4jConstants.SESSION_ID);
            String uri = context.getFullRequestURL();
            String repositoryId = Util.extractRepositoryId(uri);

            //ログイン画面へ飛ばす
            context.setSessionAttribute(Pac4jConstants.REQUESTED_URL, uri);
            return  redirect("http://localhost:9001/ui/login");
        } else if (code == HttpConstants.FORBIDDEN) {
            return forbidden("403 FORBIDDEN");
        } else {
            return super.adapt(code, context);
        }
    }

}

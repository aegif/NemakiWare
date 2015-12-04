package global;
import java.util.HashMap;
import java.util.Map;

import play.Application;
import play.GlobalSettings;
import play.Logger;
import play.libs.F.Promise;
import play.mvc.Action;
import play.mvc.Http;
import play.mvc.Result;

public class Global extends GlobalSettings {

    private class ActionWrapper extends Action.Simple {
        public ActionWrapper(Action<?> action) {
            this.delegate = action;
        }

        @Override
        public Promise<Result> call(Http.Context ctx) throws java.lang.Throwable {
            Promise<Result> result = this.delegate.call(ctx);
            Http.Response response = ctx.response();
            response.setHeader("Access-Control-Allow-Origin", "*");
            response.setHeader("Cache-Control",  "no-cache, no-store, must-revalidate, max-age=0, post-check=0, pre-check=0"); // HTTP 1.1
            response.setHeader("Pragma", "no-cache"); // HTTP 1.0.
            response.setHeader("EXPIRES", "0"); // Proxies
            return result;
        }
    }

    @Override
    public Action<?> onRequest(Http.Request request, java.lang.reflect.Method actionMethod) {
        return new ActionWrapper(super.onRequest(request, actionMethod));
    }



	private Map<String, String>test = new HashMap<String, String>();

	@Override
	public void onStart(Application app) {
		Logger.info("Application has started");
	}

	@Override
	public void onStop(Application arg0) {
		Logger.info("Application shutdown...");
	}


	public String getValue(String key){
		return test.get(key);
	}

}
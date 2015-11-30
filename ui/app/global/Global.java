package global;
import java.util.HashMap;
import java.util.Map;

import filters.NoCacheFilter;
import play.Application;
import play.GlobalSettings;
import play.Logger;
import play.api.mvc.EssentialFilter;

public class Global extends GlobalSettings {

	private Map<String, String>test = new HashMap<String, String>();

	@Override
	public void onStart(Application app) {
		Logger.info("Application has started");
	}

	@Override
	public void onStop(Application arg0) {
		Logger.info("Application shutdown...");
	}

    @SuppressWarnings("unchecked")
	@Override
    public <T extends EssentialFilter> Class<T>[] filters() {
        return new Class[] {NoCacheFilter.class};
    }


	public String getValue(String key){
		return test.get(key);
	}

}
package global;
import java.util.HashMap;
import java.util.Map;

import play.Application;
import play.GlobalSettings;
import play.Logger;

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

	public String getValue(String key){
		return test.get(key);
	}

}
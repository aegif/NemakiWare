package global;
import play.Application;
import play.GlobalSettings;
import play.Logger;

public class Global extends GlobalSettings {

	public String aho = "baka";

	@Override
	public void onStart(Application app) {



		Logger.info("Application has started");
	    }

	@Override
	public void onStop(Application arg0) {
		Logger.info("Application shutdown...");
	}


}
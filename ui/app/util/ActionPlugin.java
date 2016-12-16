package util;

import java.util.List;
import java.util.Set;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import jp.aegif.nemaki.plugin.action.JavaBackedUIAction;

@Singleton
public class ActionPlugin {

	@Inject
	private Set<JavaBackedUIAction> actions;
	
	public Set<JavaBackedUIAction> getActions() {
		return actions;
	}
}

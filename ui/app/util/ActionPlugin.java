package util;

import java.util.HashSet;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import jp.aegif.nemaki.plugin.action.JavaBackedUIAction;

@Singleton
public class ActionPlugin {

	@Inject
	private HashSet<JavaBackedUIAction> actions;

	public HashSet<JavaBackedUIAction> getActions() {
		return actions;
	}
}

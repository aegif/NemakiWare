package jp.aegif.nemaki.util.action;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.springframework.beans.factory.annotation.Autowired;

import jp.aegif.nemaki.plugin.action.JavaBackedAction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NemakiActionPlugin {

	@Autowired(required=false)
	Map<String, JavaBackedAction> pluginMap = new HashMap<String, JavaBackedAction>();

	public Map<String, JavaBackedAction> getPluginsMap() {
		return pluginMap;
	}

	public JavaBackedAction getPlugin(String actionId) {
		return pluginMap.get(actionId);
	}
}

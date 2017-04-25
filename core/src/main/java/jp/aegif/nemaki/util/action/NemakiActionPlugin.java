package jp.aegif.nemaki.util.action;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ImportResource;
import org.springframework.plugin.core.PluginRegistry;

import jp.aegif.nemaki.plugin.action.JavaBackedAction;


@ImportResource("classpath*:META-INF/plugins.xml")
public class NemakiActionPlugin {
	@Autowired(required=false)
	PluginRegistry<JavaBackedAction, String> pluginRegistry;

	@Autowired(required=false)
	Map<String, JavaBackedAction> pluginMap = new HashMap<String, JavaBackedAction>();

	public Map<String, JavaBackedAction> getPluginsMap() {
		return pluginMap;
	}

	public JavaBackedAction getPlugin(String actionId) {
		return pluginMap.get(actionId);
	}
}

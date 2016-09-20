package jp.aegif.nemaki.util.action;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ImportResource;

import jp.aegif.nemaki.plugin.action.JavaBackedAction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.springframework.plugin.core.PluginRegistry;


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

package jp.aegif.nemaki.util.yaml;

import java.util.Collection;
import java.util.Map;

public class RepositorySettings {
	
	private Map<String, RepositorySetting> settings;
	
	public RepositorySettings(){
		
	}

	public Collection<String> getIds(){
		return settings.keySet();
	}
	
	public RepositorySetting get(String repositoryId){
		return settings.get(repositoryId);
	}
	
	public Map<String, RepositorySetting> getSettings() {
		return settings;
	}

	public void setSettings(Map<String, RepositorySetting> settings) {
		this.settings = settings;
	}
}

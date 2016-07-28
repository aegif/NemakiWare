package jp.aegif.nemaki.model.couch;

import java.util.Map;

import jp.aegif.nemaki.model.Configuration;

public class CouchConfiguration extends CouchNodeBase{
	private Map<String, Object> configuration;
	
	public CouchConfiguration(){
		super();
	}
	
	public CouchConfiguration(Configuration configuration){
		super(configuration);
		setConfiguration(configuration.getConfiguration());
	}

	public Map<String, Object> getConfiguration() {
		return configuration;
	}

	public void setConfiguration(Map<String, Object> configuration) {
		this.configuration = configuration;
	}
	
	@Override
	public Configuration convert(){
		Configuration configuration = new Configuration(super.convert());
		configuration.setConfiguration(getConfiguration());

		return configuration;
	}
}

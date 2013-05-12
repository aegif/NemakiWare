package jp.aegif.nemaki.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.ConfigurationException;

import jp.aegif.nemaki.service.dao.impl.ContentDaoServiceImpl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ho.yaml.Yaml;


//TODO Yamlに限定せず外部ファイルのManagerにする
public class YamlManager {
	
	private String baseModelFile = null;
	private static final Log log = LogFactory.getLog(YamlManager.class);
	
	public YamlManager(String baseModelFile){
		this.baseModelFile = baseModelFile;
	}
	
	public synchronized Object loadYml(){
		InputStream input = getClass().getClassLoader().getResourceAsStream(baseModelFile);
		if (null == input) {
			log.error("yaml file not found");
		}
		try{
			Object o = Yaml.load(input);
			return o;
		}catch(Exception e){
			log.error("yaml load failed", e);
		}
		return null;
	}
}

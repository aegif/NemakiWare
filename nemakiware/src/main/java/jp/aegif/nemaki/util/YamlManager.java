/*******************************************************************************
 * Copyright (c) 2013 aegif.
 * 
 * This file is part of NemakiWare.
 * 
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     linzhixing - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.util;

import java.io.InputStream;

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

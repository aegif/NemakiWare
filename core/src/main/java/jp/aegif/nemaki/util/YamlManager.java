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
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.esotericsoftware.yamlbeans.YamlReader;

public class YamlManager {

	private String baseModelFile = null;
	private static final Log log = LogFactory.getLog(YamlManager.class);

	public YamlManager(String baseModelFile){
		this.baseModelFile = baseModelFile;
	}

	public Object loadYml(){
		log.info("YamlManager.loadYml() called for file: " + baseModelFile);
		InputStream is = getClass().getClassLoader().getResourceAsStream(baseModelFile);
		if (is == null) {
			log.error("yaml file not found: " + baseModelFile);
			return null;
		}
		log.info("InputStream found for file: " + baseModelFile);

		Reader reader = null;
		YamlReader yamlReader = null;
		try {
			reader = new InputStreamReader(is, "UTF-8");
			reader = new BufferedReader(reader);
			log.info("Created BufferedReader for file: " + baseModelFile);
			
			yamlReader = new YamlReader(reader);
			log.info("Created YamlReader for file: " + baseModelFile);
			Object ydoc = yamlReader.read();
			log.info("YamlReader.read() completed for file: " + baseModelFile + ", result: " + (ydoc != null ? ydoc.getClass().getName() : "null"));
			
			return ydoc;
		} catch (Exception e) {
			log.error(baseModelFile + " load failed with yamlbeans", e);
		} finally {
			try {
				if (yamlReader != null) {
					yamlReader.close();
				}
				if (reader != null) {
					reader.close();
				}
			} catch (Exception e) {
				log.warn("Error closing YAML reader resources", e);
			}
		}
		return null;
	}
}

/*******************************************************************************
 * Copyright (c) 2014 aegif.
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
package jp.aegif.nemaki.installer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

public class FileUtil {
	public static String replaceAllKeys(File templateFile, List<File> propertiesFiles, String pattern){
		//Read and override properties key-value
		Properties config = new Properties();
		for(File propertiesFile : propertiesFiles){
			System.out.println("Read config from " + propertiesFile.getAbsolutePath());
			Properties _config = readProperties(propertiesFile);
			for(Object _key : _config.keySet()){
				String key = (String)_key;
				String value = _config.getProperty(key);
				config.setProperty(key, value);
			}
		}

		//Replace place holders in template file
		String body = readFile(templateFile);
		String replaced = body;

		if(config != null && config.keySet() != null){
			for(Object _key : config.keySet()){
				String key = (String)_key;
				replaced = replaceWithKey(config, replaced, key, pattern);
			}
		}

		//Write to new file
		String newFilePath = addSuffix(templateFile.getAbsolutePath());
		writeFile(newFilePath, replaced);

		return newFilePath;
	}

	public static String replaceWithKey(Properties config, String body,
			String key, String pattern) {
		// Read proeprties file
		String value = config.getProperty(key);

		// Substitute variables in template file
		String replaced = body;
		if(StringPool.PATTERN_USER_INPUT_SPEC.equals(pattern)){
			replaced = body.replaceAll("\\Qset=\"${" + key + "}\"\\E",
					"set=\"" + value + "\"");
		}

		return replaced;
	}

	public static String readProperties(File file, String key) {
		Properties config = readProperties(file);
		String value = config.getProperty(key);
		return value;
	}

	public static Properties readProperties(File file) {
		Properties config = new Properties();

		InputStream inputStream = null;
		try {
			inputStream = new FileInputStream(file);
			config.load(inputStream);
			inputStream.close();
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}finally{
			if(inputStream != null){
				try{
					inputStream.close();
				}catch(Exception e){

				}
			}
		}
		return config;
	}

	public static String readFile(String filePath){
		File file = new File(filePath);
		return readFile(file);
	}

	public static String readFile(File file) {
		try {
			// Read
			FileReader fr = new FileReader(file);
			BufferedReader br = new BufferedReader(fr);

			StringBuffer fileRead = new StringBuffer("");
			String str = "";
			while ((str = br.readLine()) != null) {
				fileRead.append(str + "\r\n");
			}
			br.close();

			String body = fileRead.toString();
			return body;
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return null;

	}

	public static void writeFile(String filePath, String body) {
		File file = new File(filePath);
		try {
			FileWriter fw = new FileWriter(file);
			fw.write(body);
			fw.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public static void deleteNode(String path){
		File node = new File(path);
		if(node.exists()){
			deleteNode(node);
		}else{
			System.out.println("path=" + path + " does not exist");
		}
	}

	/**
	 * Delete node(file/folder including its descendants)
	 * @param node
	 * @return
	 */
	public static boolean deleteNode(File node){
        if (node.isDirectory()) {
            String[] children = node.list();
            for (int i=0; i<children.length; i++) {
                boolean success = deleteNode(new File(node, children[i]));
                if (!success) {
                	System.out.println("path=" + node.getAbsolutePath() + " failed to delete");
                    return false;
                }
            }
        }
        return node.delete();
	}

	public static String addSuffix(String filePath) {
		String result = null;

		int point = filePath.lastIndexOf(".");
		if (point != -1) {
			String extension = filePath.substring(point + 1);
			String body = filePath.substring(0, point);
			result =  body + "_modified." + extension;
		}

		return result;
	}
}

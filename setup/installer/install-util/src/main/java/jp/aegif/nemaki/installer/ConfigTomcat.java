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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigTomcat {

	private static String catalinaBase;

	private static final String SERVER_PORT = "server.port";
	private static final String SERVER_SHUTDOWN_PORT = "server.shutdown.port";
	private static final String SERVER_AJP_PORT = "server.ajp.port";

	public static void main(String[] args) throws IOException {

		if (args.length < 1) {
			System.out.println("Wrong number of arguments. Abort");
			return;
		}

		catalinaBase = args[0];

		configPermission();
		configCatalinaProperties();
		configPorts(args);
	}

	private static void configCatalinaProperties() {
		String filePath = catalinaBase + "/conf/catalina.properties";
		String body = FileUtil.readFile(filePath);

		String replaced = body.replaceAll("\\Qshared.loader=\\E",
				"shared.loader=\\${catalina.base}/shared/classes");
		FileUtil.writeFile(filePath, replaced);
	}

	private static void configPorts(String[] args) {
		Map<String, String> map = parsePorts(args);
		String serverPort = map.get(SERVER_PORT);
		String serverShutdownPort = map.get(SERVER_SHUTDOWN_PORT);
		String serverAjpPort = map.get(SERVER_AJP_PORT);

		String filePath = catalinaBase + "/conf/server.xml";
		String replaced = FileUtil.readFile(filePath);

		// Modify server port
		if (serverPort != null && checkInt(serverPort)
				&& !serverPort.equals("8080")) {
			replaced = replaced.replaceAll("port=\"8080\"", "port=\"" + serverPort
					+ "\"");
		}

		// Modify server shutdown port
		if (serverShutdownPort != null && checkInt(serverShutdownPort)
				&& !serverShutdownPort.equals("8005")) {
			replaced = replaced.replaceAll("port=\"8005\"", "port=\""
					+ serverShutdownPort + "\"");
		}

		// Modify server shutdown port
		if (serverAjpPort != null && checkInt(serverAjpPort)
				&& !serverAjpPort.equals("8009")) {
			replaced = replaced.replaceAll("port=\"8009\"", "port=\""
					+ serverShutdownPort + "\"");
		}

		FileUtil.writeFile(filePath, replaced);
	}

	private static Map<String, String> parsePorts(String[] args) {
		Map<String, String> map = new HashMap<String, String>();

		if (args.length >= 2) {
			map.put(SERVER_PORT, args[1]);
		}
		if (args.length >= 3) {
			map.put(SERVER_SHUTDOWN_PORT, args[2]);
		}
		if (args.length >= 4) {
			map.put(SERVER_AJP_PORT, args[3]);
		}
		return map;
	}

	public static boolean checkInt(String str) {
		try {
			Integer.parseInt(str);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static void configPermission() {
		// List up target scripts
		List<String> scripts = new ArrayList<String>();
		scripts.add("startup.sh");
		scripts.add("shutdown.sh");
		scripts.add("catalina.sh");
		scripts.add("setclasspath.sh");
		scripts.add("bootstrap.jar");

		// Modify permission to executable
		for (String s : scripts) {
			File file = new File(catalinaBase + "/bin/" + s);
			file.setExecutable(true);
		}
	}
}
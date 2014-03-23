package jp.aegif.nemaki.installer;

import java.io.File;
import java.io.IOException;

public class ConfigShare {
	public static void main(String[] args) throws IOException {

		if (args.length < 1) {
			System.out.println("Wrong number of arguments. Abort");
			return;
		}

		String configFilePath = args[0];
		File configFile = new File(configFilePath);

		String port = null;
		if(args.length >= 2){
			port = args[1];
		}
		String repositoryMainId = null;
		if(args.length >= 3){
			repositoryMainId = args[2];
		}

		processConfig(configFile, port, repositoryMainId);
	}

		/**
		 * As long as NemakiWare and Solr are on the same Tomcat, protocol etc. are the same.
		 * @param configFile
		 * @param protocol
		 * @param host
		 * @param port
		 */
		private static void processConfig(File configFile, String port, String repositoryMainId){
			String body = FileUtil.readFile(configFile);

			String replaced = body;

			//TODO Replace by specifying a key
			if(port != null){
				replaced =
						replaced.replaceAll("\\Qserver_port: '8080'\\E", "server_port: '" + port + "'");
				replaced =
						replaced.replaceAll("\\Qserver_port: '8983'\\E", "server_port: '" + port + "'");
			}
			if(repositoryMainId != null){
				replaced =
						replaced.replaceAll("\\Qrepository_main_id: 'bedroom'\\E", "repository_main_id: '" + repositoryMainId + "'");
			}

			FileUtil.writeFile(configFile.getAbsolutePath(), replaced);
		}
}

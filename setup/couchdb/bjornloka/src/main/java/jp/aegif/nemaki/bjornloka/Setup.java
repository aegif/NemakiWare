package jp.aegif.nemaki.bjornloka;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;



public class Setup {
	public static void main(String[] args) throws IOException {
		String host = null;
		String port = null;
		String mainRepositoryId = null;
		String archiveRepositoryId = null;
		String mainFilePath = null;
		String archiveFilePath = null;
		String suggestedMainFilePath = "";
		String suggestedArchiveFilePath = "";

		//Read arguments
		try{
			host = args[0];
			port = args[1];
			mainRepositoryId = args[2];
			archiveRepositoryId = args[3];
			mainFilePath = args[4];
			archiveFilePath = args[5];
		}catch(Exception e){

		}

		try{
			suggestedMainFilePath = args[6];
		}catch (Exception e){

		}
		try{
			suggestedArchiveFilePath = args[7];
		}catch (Exception e){

		}

		//Read input from console if it's not provided
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

		if(StringUtils.isBlank(host)){
			String defVal = "127.0.0.1";
			System.out.print("CouchDB Host[default:" + defVal + "]：");
			host = in.readLine();
			if(StringUtils.isBlank(host)){
				host = defVal;
			}
		}

		if(StringUtils.isBlank(port)){
			String defVal = "5984";
			System.out.print("CouchDB Port[default:" + defVal + "]：");
			port = in.readLine();
			if(StringUtils.isBlank(port)){
				port = defVal;
			}
		}

		if(StringUtils.isBlank(mainRepositoryId)){
			String defVal = "bedroom";
			System.out.print("Main repository ID[default:" + defVal + "]：");
			mainRepositoryId = in.readLine();
			if(StringUtils.isBlank(mainRepositoryId)){
				mainRepositoryId = defVal;
			}
		}

		if(StringUtils.isBlank(archiveRepositoryId)){
			String defVal = "archive";
			System.out.print("Archive repository ID[default:" + defVal + "]：");
			archiveRepositoryId = in.readLine();
			if(StringUtils.isBlank(archiveRepositoryId)){
				archiveRepositoryId = defVal;
			}
		}

		if(StringUtils.isBlank(mainFilePath)){
			String defVal = suggestedMainFilePath;
			System.out.print("Import file(main):" + defVal + "]：");
			mainFilePath = in.readLine();
			if(StringUtils.isBlank(mainFilePath)){
				mainFilePath = defVal;
			}
		}

		if(StringUtils.isBlank(archiveFilePath)){
			String defVal = suggestedArchiveFilePath;
			System.out.print("Import file(archive):" + defVal + "]：");
			archiveFilePath = in.readLine();
			if(StringUtils.isBlank(archiveFilePath)){
				archiveFilePath = defVal;
			}
		}

		//Build parameters
		List<String> mainParams = new ArrayList<String>();
		mainParams.add(host);
		mainParams.add(port);
		mainParams.add(mainRepositoryId);
		mainParams.add(mainFilePath);
		mainParams.add(StringPool.BOOLEAN_TRUE);
		String[] _mainParams = mainParams.toArray(new String[mainParams.size()]);
		System.out.println("mainParams:" + mainParams.toString());

		List<String> archiveParams = new ArrayList<String>();
		archiveParams.add(host);
		archiveParams.add(port);
		archiveParams.add(archiveRepositoryId);
		archiveParams.add(archiveFilePath);
		archiveParams.add(StringPool.BOOLEAN_TRUE);
		String[] _archiveParams = archiveParams.toArray(new String[archiveParams.size()]);
		System.out.println("archiveParams:" + archiveParams.toString());

		//Load
		Load.main(_mainParams);
		Load.main(_archiveParams);
	}
}
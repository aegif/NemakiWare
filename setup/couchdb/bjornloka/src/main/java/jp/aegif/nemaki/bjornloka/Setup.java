package jp.aegif.nemaki.bjornloka;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

public class Setup {
	public static void main(String[] args) throws IOException {
		String url = null;
		String mainRepositoryId = null;
		String archiveRepositoryId = null;
		String mainFilePath = null;
		String archiveFilePath = null;
		String suggestedMainFilePath = "";
		String suggestedArchiveFilePath = "";

		//Read arguments
		try{
			url = args[0];
			mainRepositoryId = args[1];
			archiveRepositoryId = args[2];
			mainFilePath = args[3];
			archiveFilePath = args[4];
		}catch(Exception e){

		}

		try{
			suggestedMainFilePath = args[5];
		}catch (Exception e){

		}
		try{
			suggestedArchiveFilePath = args[6];
		}catch (Exception e){

		}

		//Read input from console if it's not provided
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

		if(StringUtils.isBlank(url)){
			String defVal = "http://127.0.0.1:5984";
			System.out.print("CouchDB URL[default:" + defVal + "]：");
			url = in.readLine();
			if(StringUtils.isBlank(url)){
				url = defVal;
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
			String defVal = mainRepositoryId + "_closet";
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
		mainParams.add(url);
		mainParams.add(mainRepositoryId);
		mainParams.add(mainFilePath);
		mainParams.add(StringPool.BOOLEAN_TRUE);
		String[] _mainParams = mainParams.toArray(new String[mainParams.size()]);
		System.out.println("mainParams:" + mainParams.toString());

		List<String> archiveParams = new ArrayList<String>();
		archiveParams.add(url);
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
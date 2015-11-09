package jp.aegif.nemaki.bjornloka;

import java.io.File;
import jp.aegif.nemaki.bjornloka.load.LoadAction;
import jp.aegif.nemaki.bjornloka.load.LoadCloudant;
import jp.aegif.nemaki.bjornloka.load.LoadEktorp;
import jp.aegif.nemaki.bjornloka.util.Util;

public class Load {

	public static void main(String[]args){
		if(args.length < 3){
			System.err.println("Wrong number of arguments: url, repositoryId, filePath, force");
			return;
		}

		//url
		String url = args[0];

		//repositoryId
		String repositoryId = args[1];

		//filePath
		String filePath = args[2];
		File file = new File(filePath);

		//force(optional)
		boolean force = false;
		try{
			String _force = args[3];
			force = StringPool.BOOLEAN_TRUE.equals(_force);
		}catch(Exception e){

		}
		
		//Execute loading
		LoadAction loadAction = LoadAction.getInstance(url, repositoryId, file, force);
		boolean success = loadAction.load();
		
		if(success){
			System.out.println(repositoryId + ":Data imported successfully");
		}else{
			System.err.println(repositoryId + ":Data import failed");
		}

	}
}

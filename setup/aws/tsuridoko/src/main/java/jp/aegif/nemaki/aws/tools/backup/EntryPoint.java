package jp.aegif.nemaki.aws.tools.backup;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import org.apache.commons.cli.OptionGroup;

public class EntryPoint {

	public static void main(String[] args) {

		// create Options object
		Options options = new Options();

		// add t option
		options.addOption("b", true, "Backup S3 bucket name.");

		OptionGroup targetRepositoriesOpGroup = new OptionGroup();

		Option targetUrlOp = new Option("u", true, "Backup repository url.");
		Option targetLocalDirectoryPathOp = new Option("d", true, "Backup repository local dir path.");
		targetRepositoriesOpGroup.addOption(targetUrlOp);
		targetRepositoriesOpGroup.addOption(targetLocalDirectoryPathOp);
		options.addOptionGroup(targetRepositoriesOpGroup);
		options.addOption("p", true, "Backup AWS profile name.");
		options.addOption("t", true, "Backup target name.");

	    try {
	    	CommandLineParser parser = new DefaultParser();
	        CommandLine cmd = parser.parse( options, args );

	        BackupCouchDbToS3Util util = new BackupCouchDbToS3Util();
	        String bucketName = "";
	        URI url = new URI(BackupCouchDbToS3Util.DefaultCouchDbUrl);
	        String profile = "";
	        String targets = "";

			if(cmd.hasOption("b")) {
				bucketName = cmd.getOptionValue("b");
			}else{
				return;
			}

				if ( cmd.hasOption("d") ){
					String dParam =cmd.getOptionValue("d");
					url = new File(dParam).toURI();;
				}else{
					url = new URI( cmd.getOptionValue("u") );
				}
			profile = cmd.hasOption("p") ? cmd.getOptionValue("p") : BackupCouchDbToS3Util.DefaultProfileName;
			targets = cmd.hasOption("t") ? cmd.getOptionValue("t") : "";

			util.backup(bucketName, url, profile, targets);
	    }
	    catch( ParseException exp ) {
	        System.err.println( "Parsing failed.  Reason: " + exp.getMessage() );
	    }catch( URISyntaxException exp){
	        System.err.println( "Bad uri syntax.  Reason: " + exp.getMessage() );

	    }





	}

}

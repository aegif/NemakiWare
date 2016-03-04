package jp.aegif.nemaki.aws.tools.backup;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class EntryPoint {

	public static void main(String[] args) {

		// create Options object
		Options options = new Options();

		// add t option
		options.addOption("b", true, "Backup S3 bucket name.");

	    try {
	    	CommandLineParser parser = new DefaultParser();
	        CommandLine cmd = parser.parse( options, args );
			if(cmd.hasOption("b")) {
				String bucketName = cmd.getOptionValue("b");
				BackupCouchDbToS3Util util = new BackupCouchDbToS3Util();
				util.backup(bucketName);
			}
	    }
	    catch( ParseException exp ) {
	        System.err.println( "Parsing failed.  Reason: " + exp.getMessage() );
	    }





	}

}

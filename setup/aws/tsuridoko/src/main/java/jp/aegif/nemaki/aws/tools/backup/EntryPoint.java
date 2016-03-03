package jp.aegif.nemaki.aws.tools.backup;

public class EntryPoint {

	public static void main(String[] args) {
		BackupCouchDbToS3Util util = new BackupCouchDbToS3Util();
		util.backup();

	}

}

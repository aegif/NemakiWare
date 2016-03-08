package jp.aegif.nemaki.aws.tools.backup;

import com.amazonaws.event.ProgressEvent;
import com.amazonaws.event.ProgressListener;

public class BackupFileUploadProgressListener implements ProgressListener {

	private String _repositoryName;
	public BackupFileUploadProgressListener(String repositoryName){
		_repositoryName = repositoryName;
	}

	@Override
	public void progressChanged(ProgressEvent progressEvent) {
		//System.out.println("[" + _repositoryName + "] Transferred bytes: " + progressEvent.getBytesTransferred());
	}

}

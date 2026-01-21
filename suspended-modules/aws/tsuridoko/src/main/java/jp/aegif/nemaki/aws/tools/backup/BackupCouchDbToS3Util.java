package jp.aegif.nemaki.aws.tools.backup;

// DEPRECATED: This utility class depends on legacy bjornloka package which has been removed.
// This class is kept for reference but is non-functional.
// TODO: Migrate to use cloudant-init functionality for dump/restore operations.

/*
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.client.utils.URIUtils;

// REMOVED: import jp.aegif.nemaki.bjornloka.dump.*;
*/
import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.event.ProgressEvent;
import com.amazonaws.event.ProgressListener;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;

import org.json.*;
import javax.ws.rs.client.*;
import javax.ws.rs.core.*;

public class BackupCouchDbToS3Util {
	static String DefaultCouchDbUrl = "http://localhost:5984/";
	static String DefaultProfileName = "NemakiWare";

	public void backup(String bucketName) {
		try {
			URI uri = new URI(DefaultCouchDbUrl);
			backup(bucketName, uri, DefaultProfileName, new String[0]);
		} catch (Exception ex) {
		}
	}

	public void backup(String bucketName, URI url) {
		backup(bucketName, url, DefaultProfileName, new String[0]);
	}

	public void backup(String bucketName, URI url, String profileName) {
		backup(bucketName, url, profileName, new String[0]);
	}

	public void backup(String bucketName, URI url, String profileName, String targets) {
		if (targets == null | targets == "") {
			backup(bucketName, url, profileName, new String[0]);
		} else {
			backup(bucketName, url, profileName, targets.split(",", -1));
		}
	}

	public void backup(String bucketName, URI couchUrl, String profileName, String[] targets) {
		String[] targetDbs = targets;
		AmazonS3 s3client = new AmazonS3Client(new ProfileCredentialsProvider(profileName));
		TransferManager tm = new TransferManager(s3client);

		try{
		String uriScheme = couchUrl.getScheme();

		if (targetDbs == null || targetDbs.length == 0) {
			URI targetRestURL = couchUrl;
			if (uriScheme.equals("file")) {
				try {
					targetRestURL = new URI(DefaultCouchDbUrl);
				} catch (URISyntaxException e) {
				}
			}
			List<String> list = getAllTargets(targetRestURL);
			targetDbs = (String[]) list.toArray(new String[0]);
		}

		for (String repositoryName : targetDbs) {
			System.out.println("[" + repositoryName + "] Backup started. ");

			if (repositoryName == null || repositoryName == "")
				continue;

			File file;
			try {
				if (uriScheme.equals("http") || uriScheme.equals("https")) {
					file = File.createTempFile(repositoryName, ".bk.dump");
					String couchURI = couchUrl.toString();
					DumpAction action = DumpAction.getInstance(couchURI, repositoryName, file, false);
					action.dump();
				} else if (uriScheme.equals("file")) {
					couchUrl = couchUrl.resolve(repositoryName + ".couch");
					file = new File(couchUrl);
				} else {
					System.out.printf("Error : Invalid scheme :  %s \n", uriScheme);
					continue;
				}

				if (file.exists()) {
					uploadS3(tm, file, bucketName, repositoryName);
				} else {
					System.out.println("Error : Backup file not found");
					System.out.println(file.getPath());
					System.out.println(couchUrl.toString());
					continue;
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		}finally{
			tm.shutdownNow(true);
		}
	}

	public List<String> getAllTargets(URI couchUrl) {
		Client client = ClientBuilder.newClient();
		String jsonArrayString = client.target(couchUrl).path("_all_dbs").request(MediaType.APPLICATION_JSON_TYPE)
				.get(String.class);
		JSONArray jsonArray = new JSONArray(jsonArrayString);
		List<String> list = new ArrayList<String>();
		for (int i = 0; i < jsonArray.length(); i++) {
			list.add(jsonArray.getString(i));
		}
		return list;
	}

	public void uploadS3(TransferManager tm, File file, String bucketName, String repositoryName) {
		try {
			PutObjectRequest request = new PutObjectRequest(bucketName, repositoryName, file);
			request.setGeneralProgressListener(new BackupFileUploadProgressListener(repositoryName));
			System.out.println("[" + repositoryName + "] Upload started. ");
			Upload uploader = tm.upload(request);
			try {
				uploader.waitForCompletion();
				System.out.println("[" + repositoryName + "] Upload complete. ");
			} catch (AmazonClientException amazonClientException) {
				System.out.println("[" + repositoryName + "] Unable to upload file, upload was aborted. ");
				amazonClientException.printStackTrace();
			} catch (InterruptedException e) {
				System.out.println("[" + repositoryName + "] Unable to upload file, upload was interrupted. ");
				e.printStackTrace();
			}
			uploader = null;

		} catch (AmazonServiceException ase) {
			System.out.println("Caught an AmazonServiceException, which " + "means your request made it "
					+ "to Amazon S3, but was rejected with an error response" + " for some reason.");
			System.out.println("Error Message:    " + ase.getMessage());
			System.out.println("HTTP Status Code: " + ase.getStatusCode());
			System.out.println("AWS Error Code:   " + ase.getErrorCode());
			System.out.println("Error Type:       " + ase.getErrorType());
			System.out.println("Request ID:       " + ase.getRequestId());
		} catch (AmazonClientException ace) {
			System.out.println("Caught an AmazonClientException, which " + "means the client encountered "
					+ "an internal error while trying to " + "communicate with S3, "
					+ "such as not being able to access the network.");
			System.out.println("Error Message: " + ace.getMessage());
		}
	}
}

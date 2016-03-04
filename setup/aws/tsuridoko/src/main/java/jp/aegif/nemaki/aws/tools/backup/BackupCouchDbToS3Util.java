package jp.aegif.nemaki.aws.tools.backup;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.client.utils.URIUtils;

import jp.aegif.nemaki.bjornloka.dump.*;
import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.PutObjectRequest;

import org.json.*;
import javax.ws.rs.client.*;
import javax.ws.rs.core.*;

public class BackupCouchDbToS3Util {
	String defaultCouchDbUrl = "http://localhost:5984/";
	String dafaultProfileName = "NemakiWare";

	public void backup(String bucketName) {
		backup(bucketName, dafaultProfileName, defaultCouchDbUrl, null);
	}

	public void backup(String bucketName, String profileName, String couchUrl, String[] targets) {
		String[] targetDbs = targets;

		if (targetDbs == null || targetDbs.length == 0) {
			Client client = ClientBuilder.newClient();
			String jsonArrayString = client.target(couchUrl).path("_all_dbs").request(MediaType.APPLICATION_JSON_TYPE)
					.get(String.class);
			JSONArray jsonArray = new JSONArray(jsonArrayString);
			List<String> list = new ArrayList<String>();
			for (int i = 0; i < jsonArray.length(); i++) {
				list.add(jsonArray.getString(i));
			}
			targetDbs = (String[]) list.toArray(new String[0]);
		}

		AmazonS3 s3client = new AmazonS3Client(new ProfileCredentialsProvider(profileName));

		for (String repositoryName : targetDbs) {
			File file;
			try {
				file = File.createTempFile(repositoryName, ".bk.dump");

				DumpAction action = DumpAction.getInstance(couchUrl, repositoryName, file, false);
				action.dump();

				if (file.exists()) {

					try {
						s3client.putObject(new PutObjectRequest(bucketName, repositoryName, file));
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

				} else {
					System.out.println("Error : Dump failed.");
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}

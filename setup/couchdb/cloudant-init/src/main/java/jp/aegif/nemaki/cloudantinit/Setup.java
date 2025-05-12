package jp.aegif.nemaki.cloudantinit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * Main entry point for CouchDB 3.x initialization
 */
public class Setup {
    public static void main(String[] args) throws IOException {
        String url = null;
        String username = null;
        String password = null;
        String mainRepositoryId = null;
        String archiveRepositoryId = null;
        String mainFilePath = null;
        String archiveFilePath = null;
        String suggestedMainFilePath = "";
        String suggestedArchiveFilePath = "";

        try {
            url = args[0];
            username = args[1];
            password = args[2];
            mainRepositoryId = args[3];
            archiveRepositoryId = args[4];
            mainFilePath = args[5];
            archiveFilePath = args[6];
        } catch (Exception e) {
        }

        try {
            suggestedMainFilePath = args[7];
        } catch (Exception e) {
        }
        try {
            suggestedArchiveFilePath = args[8];
        } catch (Exception e) {
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

        if (StringUtils.isBlank(url)) {
            String defVal = "http://127.0.0.1:5984";
            System.out.print("CouchDB URL [default:" + defVal + "]: ");
            url = in.readLine();
            if (StringUtils.isBlank(url)) {
                url = defVal;
            }
        }

        if (StringUtils.isBlank(username)) {
            String defVal = "admin";
            System.out.print("CouchDB Username [default:" + defVal + "]: ");
            username = in.readLine();
            if (StringUtils.isBlank(username)) {
                username = defVal;
            }
        }

        if (StringUtils.isBlank(password)) {
            System.out.print("CouchDB Password: ");
            password = in.readLine();
            if (StringUtils.isBlank(password)) {
                System.err.println("Password cannot be empty for CouchDB 3.x");
                System.exit(1);
            }
        }

        if (StringUtils.isBlank(mainRepositoryId)) {
            String defVal = "bedroom";
            System.out.print("Main repository ID [default:" + defVal + "]: ");
            mainRepositoryId = in.readLine();
            if (StringUtils.isBlank(mainRepositoryId)) {
                mainRepositoryId = defVal;
            }
        }

        if (StringUtils.isBlank(archiveRepositoryId)) {
            String defVal = mainRepositoryId + "_closet";
            System.out.print("Archive repository ID [default:" + defVal + "]: ");
            archiveRepositoryId = in.readLine();
            if (StringUtils.isBlank(archiveRepositoryId)) {
                archiveRepositoryId = defVal;
            }
        }

        if (StringUtils.isBlank(mainFilePath)) {
            String defVal = suggestedMainFilePath;
            System.out.print("Import file (main) [" + defVal + "]: ");
            mainFilePath = in.readLine();
            if (StringUtils.isBlank(mainFilePath)) {
                mainFilePath = defVal;
            }
        }

        if (StringUtils.isBlank(archiveFilePath)) {
            String defVal = suggestedArchiveFilePath;
            System.out.print("Import file (archive) [" + defVal + "]: ");
            archiveFilePath = in.readLine();
            if (StringUtils.isBlank(archiveFilePath)) {
                archiveFilePath = defVal;
            }
        }

        List<String> mainParams = new ArrayList<>();
        mainParams.add(url);
        mainParams.add(username);
        mainParams.add(password);
        mainParams.add(mainRepositoryId);
        mainParams.add(mainFilePath);
        mainParams.add("true"); // force
        String[] _mainParams = mainParams.toArray(new String[0]);
        System.out.println("Main repository parameters: " + mainParams.toString());

        List<String> archiveParams = new ArrayList<>();
        archiveParams.add(url);
        archiveParams.add(username);
        archiveParams.add(password);
        archiveParams.add(archiveRepositoryId);
        archiveParams.add(archiveFilePath);
        archiveParams.add("true"); // force
        String[] _archiveParams = archiveParams.toArray(new String[0]);
        System.out.println("Archive repository parameters: " + archiveParams.toString());

        CouchDBInitializer.main(_mainParams);
        CouchDBInitializer.main(_archiveParams);
    }
}

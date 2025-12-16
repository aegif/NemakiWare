/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.fit.tck;

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNullOrEmpty;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestProgressMonitor;
import org.apache.chemistry.opencmis.tck.CmisTestReport;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.CmisTestResultStatus;
import org.apache.chemistry.opencmis.tck.impl.TestParameters;
import org.apache.chemistry.opencmis.tck.report.TextReport;
import org.apache.chemistry.opencmis.tck.runner.AbstractRunner;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public abstract class AbstractTckIT extends AbstractRunner {
    public static final String TEST = "org.apache.chemistry.opencmis.tck.test";
    public static final String TEST_CMIS_1_0 = "org.apache.chemistry.opencmis.tck.testCmis10";
    public static final String TEST_CMIS_1_1 = "org.apache.chemistry.opencmis.tck.testCmis11";
    public static final String TEST_ATOMPUB = "org.apache.chemistry.opencmis.tck.testAtomPub";
    public static final String TEST_WEBSERVICES = "org.apache.chemistry.opencmis.tck.testWebServices";
    public static final String TEST_BROWSER = "org.apache.chemistry.opencmis.tck.testBrowser";
    public static final String TEST_NOT_VERSIONABLE = "org.apache.chemistry.opencmis.tck.testNotVersionable";
    public static final String TEST_VERSIONABLE = "org.apache.chemistry.opencmis.tck.testVersionable";

    public static final String DEFAULT_VERSIONABLE_DOCUMENT_TYPE = "org.apache.chemistry.opencmis.tck.default.versionableDocumentType";
    public static final String DEFAULT_VERSIONABLE_DOCUMENT_TYPE_VALUE = "VersionableType"; // InMemory

    public static final String HOST = "localhost";
    private static final int BASEPORT = 19080;
    private static int portCounter = -1;

    public static final String REPOSITORY_ID = "test";
    public static final String USER = "test";
    public static final String PASSWORD = "test";

    public abstract Map<String, String> getSessionParameters();

    public abstract BindingType getBindingType();

    public abstract CmisVersion getCmisVersion();

    public abstract boolean usesVersionableDocumentType();

    public static int getPort() {
        return BASEPORT + portCounter;
    }

    public Map<String, String> getBaseSessionParameters() {
        Map<String, String> parameters = new HashMap<String, String>();

        parameters.put(SessionParameter.REPOSITORY_ID,
                System.getProperty(SessionParameter.REPOSITORY_ID, REPOSITORY_ID));
        parameters.put(SessionParameter.USER, System.getProperty(SessionParameter.USER, USER));
        parameters.put(SessionParameter.PASSWORD, System.getProperty(SessionParameter.PASSWORD, PASSWORD));

        if (usesVersionableDocumentType()) {
            parameters.put(TestParameters.DEFAULT_DOCUMENT_TYPE,
                    System.getProperty(DEFAULT_VERSIONABLE_DOCUMENT_TYPE, DEFAULT_VERSIONABLE_DOCUMENT_TYPE_VALUE));
        } else {
            parameters.put(TestParameters.DEFAULT_DOCUMENT_TYPE, System
                    .getProperty(TestParameters.DEFAULT_DOCUMENT_TYPE, TestParameters.DEFAULT_DOCUMENT_TYPE_VALUE));
        }

        parameters.put(TestParameters.DEFAULT_FOLDER_TYPE,
                System.getProperty(TestParameters.DEFAULT_FOLDER_TYPE, "cmis:folder"));

        return parameters;
    }

    private static Tomcat tomcat;
    private static File tomcateBaseDir;

    @BeforeClass
    public static void startTomcat() throws LifecycleException, InterruptedException {
        File targetDir = new File(System.getProperty("project.build.directory", "./target"));

        File warFile = null;
        for (File child : targetDir.listFiles()) {
            if (child.getName().endsWith(".war")) {
                warFile = child;
            }
        }

        if (warFile == null) {
            throw new RuntimeException("OpenCMIS WAR file not found!");
        }

        portCounter++;

        tomcateBaseDir = new File(targetDir, "tomcat.base." + getPort());
        if (!tomcateBaseDir.exists()) {
            tomcateBaseDir.mkdir();
        }

        // Logger.getLogger("").setLevel(Level.INFO);
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");

        tomcat = new Tomcat();
        tomcat.setBaseDir(tomcateBaseDir.getAbsolutePath());
        tomcat.setPort(getPort());
        // tomcat.setSilent(true);
        tomcat.getHost().setCreateDirs(true);
        tomcat.getHost().setDeployOnStartup(true);
        tomcat.getHost().setAutoDeploy(false);

        tomcat.getServer().addLifecycleListener(new LifecycleListener() {
            @Override
            public void lifecycleEvent(LifecycleEvent event) {
                if (event.getLifecycle().getState() == LifecycleState.DESTROYED) {
                    if (!deleteDirectory(tomcateBaseDir)) {
                        markDirectoryForDelete(tomcateBaseDir);
                    }
                }
            }
        });

        File appDir = new File(tomcateBaseDir, tomcat.getHost().getAppBase());
        if (!appDir.exists()) {
            appDir.mkdir();
        }

        tomcat.addWebapp(null, "/opencmis", warFile.getAbsolutePath());
        tomcat.init();
        tomcat.start();

        int count = 60;
        while (count > 0) {
            count--;
            if (tomcat.getServer().getState() == LifecycleState.STARTED) {
                break;
            }
            Thread.sleep(500);
        }

        Thread.sleep(5000);
    }

    @AfterClass
    public static void stopTomcat() throws LifecycleException, InterruptedException {
        tomcat.stop();
        tomcat.destroy();
    }

    private static boolean deleteDirectory(File dir) {
        if (!dir.exists()) {
            return false;
        }

        for (File file : dir.listFiles()) {
            if (file.isDirectory()) {
                deleteDirectory(file);
            } else {
                file.delete();
            }
        }

        return dir.delete();
    }

    private static void markDirectoryForDelete(File dir) {
        if (!dir.exists()) {
            return;
        }

        dir.deleteOnExit();

        for (File file : dir.listFiles()) {
            if (file.isDirectory()) {
                markDirectoryForDelete(file);
            } else {
                file.deleteOnExit();
            }
        }
    }

    @Before
    public void checkTest() {
        assumeTrue("Skipping all TCK tests.", getSystemPropertyBoolean(TEST));

        if (getCmisVersion() == CmisVersion.CMIS_1_0) {
            assumeTrue("Skipping CMIS 1.0 TCK tests.", getSystemPropertyBoolean(TEST_CMIS_1_0));
        } else if (getCmisVersion() == CmisVersion.CMIS_1_1) {
            assumeTrue("Skipping CMIS 1.1 TCK tests.", getSystemPropertyBoolean(TEST_CMIS_1_1));
        }

        if (getBindingType() == BindingType.ATOMPUB) {
            assumeTrue("Skipping AtomPub binding TCK tests.", getSystemPropertyBoolean(TEST_ATOMPUB));
        } else if (getBindingType() == BindingType.WEBSERVICES) {
            assumeTrue("Skipping Web Services binding TCK tests.", getSystemPropertyBoolean(TEST_WEBSERVICES));
        } else if (getBindingType() == BindingType.BROWSER) {
            assumeTrue("Skipping Browser binding TCK tests.", getSystemPropertyBoolean(TEST_BROWSER));
        }

        if (usesVersionableDocumentType()) {
            assumeTrue("Skipping TCK tests with versionable document types.",
                    getSystemPropertyBoolean(TEST_VERSIONABLE));
        } else {
            assumeTrue("Skipping TCK tests with non-versionable document types.",
                    getSystemPropertyBoolean(TEST_NOT_VERSIONABLE));
        }
    }

    protected boolean getSystemPropertyBoolean(String propName) {
        return "true".equalsIgnoreCase(System.getProperty(propName, "true"));
    }

    @Test
    public void runTck() throws Exception {
        // set up TCK and run it
        setParameters(getSessionParameters());
        loadDefaultTckGroups();

        run(new TestProgressMonitor());

        // write report
        File target = new File("target");
        target.mkdir();

        CmisTestReport report = new TextReport();
        report.createReport(getParameters(), getGroups(),
                new File(target, "tck-result-" + getBindingType().value() + "-" + getCmisVersion().value() + "-"
                        + (usesVersionableDocumentType() ? "versionable" : "nonversionable") + ".txt"));

        // find failures
        for (CmisTestGroup group : getGroups()) {
            for (CmisTest test : group.getTests()) {
                for (CmisTestResult result : test.getResults()) {
                    assertNotNull("The test '" + test.getName() + "' returned an invalid result.", result);
                    assertTrue("The test '" + test.getName() + "' returned a failure: " + result.getMessage(),
                            result.getStatus() != CmisTestResultStatus.FAILURE);
                    assertTrue(
                            "The test '" + test.getName() + "' returned at an unexcepted exception: "
                                    + result.getMessage(),
                            result.getStatus() != CmisTestResultStatus.UNEXPECTED_EXCEPTION);
                }
            }
        }
    }

    public static CmisTestResultStatus getWorst(List<CmisTestResult> results) {
        if (isNullOrEmpty(results)) {
            return CmisTestResultStatus.OK;
        }

        int max = 0;

        for (CmisTestResult result : results) {
            if (max < result.getStatus().getLevel()) {
                max = result.getStatus().getLevel();
            }
        }

        return CmisTestResultStatus.fromLevel(max);
    }

    private static class TestProgressMonitor implements CmisTestProgressMonitor {
        @Override
        public void startGroup(CmisTestGroup group) {
            System.out.println();
            System.out.println(group.getName() + " (" + group.getTests().size() + " tests)");
        }

        @Override
        public void endGroup(CmisTestGroup group) {
            System.out.println();
        }

        @Override
        public void startTest(CmisTest test) {
            System.out.print("  " + test.getName());
        }

        @Override
        public void endTest(CmisTest test) {
            System.out.print(" (" + test.getTime() + "ms): ");
            System.out.println(getWorst(test.getResults()));
        }

        @Override
        public void message(String msg) {
            System.out.println(msg);
        }
    }
}

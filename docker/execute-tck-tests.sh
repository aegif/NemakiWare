#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "=========================================="
echo "NemakiWare TCK Test Execution"
echo "=========================================="

if ! docker ps | grep -q "docker-core-1.*Up"; then
    echo "ERROR: NemakiWare core container is not running"
    echo "Please run './test-all.sh --auto-fix' first to start the Docker environment"
    exit 1
fi

if ! docker ps | grep -q "docker-couchdb-1.*Up"; then
    echo "ERROR: CouchDB container is not running"
    echo "Please run './test-all.sh --auto-fix' first to start the Docker environment"
    exit 1
fi

echo "✓ Docker containers are running"

mkdir -p $SCRIPT_DIR/tck-reports

echo "Testing connectivity to NemakiWare core service..."
if ! docker exec docker-core-1 curl -f -s http://localhost:8080/core > /dev/null; then
    echo "ERROR: Cannot connect to NemakiWare core service"
    echo "Please ensure the Docker environment is fully started and healthy"
    exit 1
fi
echo "✓ Core service is accessible"

echo "Preparing TCK configuration for Docker environment..."
cp $SCRIPT_DIR/cmis-tck-parameters-docker.properties $NEMAKI_HOME/core/src/test/resources/cmis-tck-parameters-docker.properties
cp $SCRIPT_DIR/cmis-tck-filters-docker.properties $NEMAKI_HOME/core/src/test/resources/cmis-tck-filters-docker.properties

cat > $NEMAKI_HOME/core/src/test/java/jp/aegif/nemaki/cmis/tck/DockerTckRunner.java << 'EOF'
package jp.aegif.nemaki.cmis.tck;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestReport;
import org.apache.chemistry.opencmis.tck.report.TextReport;
import org.apache.chemistry.opencmis.tck.report.XmlReport;
import org.apache.chemistry.opencmis.tck.runner.AbstractRunner;

import jp.aegif.nemaki.cmis.tck.tests.BasicsTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.ControlTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.CrudTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.FilingTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.QueryTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.TypesTestGroup;
import jp.aegif.nemaki.cmis.tck.tests.VersioningTestGroup;

public class DockerTckRunner extends AbstractRunner {
    
    private static final String PARAMETERS_FILE = "cmis-tck-parameters-docker.properties";
    private static final String FILTERS_FILE = "cmis-tck-filters-docker.properties";
    
    public DockerTckRunner() throws Exception {
        loadParameters(PARAMETERS_FILE);
        loadGroups(FILTERS_FILE);
    }
    
    public static void main(String[] args) throws Exception {
        System.out.println("Starting NemakiWare Docker TCK Test Execution...");
        
        DockerTckRunner runner = new DockerTckRunner();
        
        List<CmisTestGroup> groups = new ArrayList<>();
        
        BasicsTestGroup basicsGroup = new BasicsTestGroup();
        basicsGroup.init(runner.getParameters());
        groups.add(basicsGroup);
        
        ControlTestGroup controlGroup = new ControlTestGroup();
        controlGroup.init(runner.getParameters());
        groups.add(controlGroup);
        
        CrudTestGroup crudGroup = new CrudTestGroup();
        crudGroup.init(runner.getParameters());
        groups.add(crudGroup);
        
        FilingTestGroup filingGroup = new FilingTestGroup();
        filingGroup.init(runner.getParameters());
        groups.add(filingGroup);
        
        QueryTestGroup queryGroup = new QueryTestGroup();
        queryGroup.init(runner.getParameters());
        groups.add(queryGroup);
        
        TypesTestGroup typesGroup = new TypesTestGroup();
        typesGroup.init(runner.getParameters());
        groups.add(typesGroup);
        
        VersioningTestGroup versioningGroup = new VersioningTestGroup();
        versioningGroup.init(runner.getParameters());
        groups.add(versioningGroup);
        
        System.out.println("Running TCK tests...");
        runner.run(groups);
        
        File reportsDir = new File("../docker/tck-reports");
        reportsDir.mkdirs();
        
        System.out.println("Generating text report...");
        CmisTestReport textReport = new TextReport();
        try (PrintWriter textWriter = new PrintWriter(new FileWriter(new File(reportsDir, "tck-report.txt")))) {
            textReport.createReport(runner.getParameters(), groups, textWriter);
        }
        
        System.out.println("Generating XML report...");
        CmisTestReport xmlReport = new XmlReport();
        try (PrintWriter xmlWriter = new PrintWriter(new FileWriter(new File(reportsDir, "tck-report.xml")))) {
            xmlReport.createReport(runner.getParameters(), groups, xmlWriter);
        }
        
        System.out.println("TCK test execution completed!");
        System.out.println("Reports generated in: " + reportsDir.getAbsolutePath());
    }
}
EOF

echo "Building and executing TCK tests..."
cd $NEMAKI_HOME

echo "Compiling TCK test classes..."
mvn test-compile -f core/pom.xml -q

echo "Executing TCK tests against Docker environment..."
mvn exec:java -f core/pom.xml \
    -Dexec.mainClass="jp.aegif.nemaki.cmis.tck.DockerTckRunner" \
    -Dexec.classpathScope="test" \
    -q

echo "TCK test execution completed successfully!"
echo "Reports available in: $SCRIPT_DIR/tck-reports/"
ls -la $SCRIPT_DIR/tck-reports/

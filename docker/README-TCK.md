# NemakiWare TCK Automation

This directory contains automated CMIS Technology Compatibility Kit (TCK) testing for NemakiWare, integrated with the Docker-based test environment.

## Overview

The TCK automation provides:
- Headless TCK test execution against Docker containers
- Comprehensive score reporting for quality management
- Integration with existing `test-all.sh` workflow
- Multiple report formats (text, XML, HTML)
- Individual test group execution support

## Quick Start

1. **Start NemakiWare Docker Environment**
   ```bash
   cd docker/
   ./test-all.sh --auto-fix
   ```

2. **Run TCK Tests**
   ```bash
   # Run all TCK tests
   ./run-tck.sh
   
   # Or integrate with test-all.sh
   ./test-all.sh --auto-fix --run-tck
   ```

3. **View Results**
   ```bash
   # View HTML summary
   open tck-reports/tck-summary.html
   
   # View text report
   cat tck-reports/tck-report.txt
   
   # Check current score
   cat tck-reports/current-score.txt
   ```

## Usage

### Basic Commands

```bash
# Run all TCK tests and generate reports
./run-tck.sh

# Run specific test group only
./run-tck.sh --group BasicsTestGroup

# Generate reports from existing test results
./run-tck.sh --no-tests

# Run tests without generating reports
./run-tck.sh --no-report

# Show help
./run-tck.sh --help
```

### Integration with test-all.sh

The TCK automation integrates seamlessly with the existing Docker test environment:

```bash
# Start Docker environment and run TCK tests
./test-all.sh --auto-fix --run-tck
```

### Individual Scripts

- `execute-tck-tests.sh` - Core TCK test execution
- `generate-tck-report.sh` - Report generation and formatting
- `run-tck.sh` - Main automation script with options

## Configuration

### TCK Parameters

TCK test parameters are configured in `cmis-tck-parameters-docker.properties`:

```properties
org.apache.chemistry.opencmis.binding.spi.type=atompub
org.apache.chemistry.opencmis.binding.atompub.url=http://core:8080/core/atom/bedroom
org.apache.chemistry.opencmis.user=admin
org.apache.chemistry.opencmis.password=admin
org.apache.chemistry.opencmis.session.repository.id=bedroom
```

### Test Groups

Test group configuration is in `cmis-tck-filters-docker.properties`:

- **BasicsTestGroup** - Basic CMIS functionality (enabled)
- **ControlTestGroup** - Access control tests (enabled)
- **CrudTestGroup** - Create/Read/Update/Delete operations (enabled)
- **FilingTestGroup** - Multi-filing and unfiling (disabled by default)
- **QueryTestGroup** - CMIS query functionality (disabled by default)
- **TypesTestGroup** - Type definition tests (enabled)
- **VersioningTestGroup** - Document versioning (enabled)

## Reports

### Generated Reports

1. **Text Report** (`tck-report.txt`) - Detailed test results in text format
2. **XML Report** (`tck-report.xml`) - Machine-readable test results
3. **HTML Summary** (`tck-summary.html`) - Visual summary with statistics
4. **Score File** (`current-score.txt`) - Current pass rate percentage
5. **Score History** (`score-history.txt`) - Historical score tracking

### Report Interpretation

- **Pass Rate** - Percentage of tests that passed successfully
- **Total Tests** - Number of TCK tests executed
- **Failed Tests** - Tests that failed (need attention)
- **Skipped Tests** - Tests that were skipped due to configuration

### Quality Management

The TCK score provides a quality metric for ongoing development:

- **Target**: Aim for continuous improvement in pass rate
- **Baseline**: Current score represents CMIS compliance level
- **Tracking**: Use score history to monitor progress over time
- **Focus Areas**: Address failed tests to improve compliance

## Test Groups

### Available Test Groups

1. **BasicsTestGroup**
   - Security tests
   - Repository info validation
   - Root folder access

2. **ControlTestGroup**
   - Access Control List (ACL) functionality

3. **CrudTestGroup**
   - Document and folder creation/deletion
   - Content operations
   - Property management
   - Copy and move operations

4. **FilingTestGroup**
   - Multi-filing capabilities
   - Unfiling operations

5. **QueryTestGroup**
   - CMIS query language support
   - Content changes tracking

6. **TypesTestGroup**
   - Base type validation
   - Custom type creation
   - Secondary types

7. **VersioningTestGroup**
   - Document versioning
   - Check-in/check-out operations

## Troubleshooting

### Common Issues

1. **Docker containers not running**
   ```bash
   # Solution: Start Docker environment first
   ./test-all.sh --auto-fix
   ```

2. **Core service not accessible**
   ```bash
   # Check container health
   docker ps
   docker logs docker-core-1
   ```

3. **TCK tests failing to connect**
   ```bash
   # Verify container networking
   docker exec docker-core-1 curl -f http://localhost:8080/core
   ```

4. **Missing bc command for calculations**
   ```bash
   # Install bc (done automatically by run-tck.sh)
   sudo apt-get install bc
   ```

### Debug Mode

For debugging TCK execution:

```bash
# Enable verbose Maven output
mvn exec:java -f core/pom.xml \
    -Dexec.mainClass="jp.aegif.nemaki.cmis.tck.DockerTckRunner" \
    -Dexec.classpathScope="test" \
    -X
```

## Development

### Adding New Test Groups

1. Create test group class in `core/src/test/java/jp/aegif/nemaki/cmis/tck/tests/`
2. Add group to `DockerTckRunner.java`
3. Update `cmis-tck-filters-docker.properties`

### Customizing Reports

Modify `generate-tck-report.sh` to customize:
- HTML report styling
- Statistics calculations
- Additional metrics

### Integration with CI/CD

The TCK automation can be integrated with CI/CD pipelines:

```bash
# Example CI script
./test-all.sh --auto-fix --run-tck
SCORE=$(cat docker/tck-reports/current-score.txt)
if (( $(echo "$SCORE < 80" | bc -l) )); then
    echo "TCK score too low: $SCORE%"
    exit 1
fi
```

## Files

- `run-tck.sh` - Main TCK automation script
- `execute-tck-tests.sh` - TCK test execution
- `generate-tck-report.sh` - Report generation
- `cmis-tck-parameters-docker.properties` - TCK connection parameters
- `cmis-tck-filters-docker.properties` - Test group configuration
- `tck-reports/` - Generated reports directory
- `README-TCK.md` - This documentation

## Support

For issues or questions:
1. Check the troubleshooting section above
2. Review Docker container logs
3. Verify TCK configuration files
4. Test individual components separately

# TCK Evidence Package - Quick Reference Index

**Package**: tck-evidence-2025-10-11
**Commit**: 37a59efba7251d33217d75399930c9b9a419be27
**Branch**: feature/react-ui-playwright
**Date**: 2025-10-11

---

## 📋 Package Contents

### Documentation Files
- **README.md** - Complete evidence package documentation with test results and technical details
- **VERIFICATION.md** - Verification commands and integrity checklist
- **INDEX.md** (this file) - Quick reference index

### Git State Files
- **branch.txt** - Branch name: `feature/react-ui-playwright`
- **commit-hash.txt** - Commit SHA: `37a59efba7251d33217d75399930c9b9a419be27`
- **git-log.txt** - Last 10 commits
- **git-status.txt** - Working directory status
- **remote.txt** - Git remote configuration

### Test Results (surefire-reports/)
- **6 XML reports** - JUnit XML format for test automation tools
- **6 TXT summaries** - Human-readable test execution summaries
- **6 OUTPUT logs** - Complete console output from test execution

**Total Files**: 25

---

## ✅ Quick Verification

### Test Results Summary
```
BasicsTestGroup:    3 tests, 0 failures, 21.8s    ✅
TypesTestGroup:     3 tests, 0 failures, 42.4s    ✅
ControlTestGroup:   1 test,  0 failures, 9.2s     ✅
VersioningTestGroup: 4 tests, 0 failures, 28.5s   ✅
QueryTestGroup:     6 tests, 0 failures, 340.1s   ✅
CrudTestGroup:      19 tests, 0 failures, 962.0s  ✅

TOTAL: 36 tests, 0 failures, 0 errors, 1404.0s (23.4 min)
```

### Git State Verification
```bash
# Branch
$ cat branch.txt
feature/react-ui-playwright

# Commit
$ cat commit-hash.txt
37a59efba7251d33217d75399930c9b9a419be27

# Remote
$ cat remote.txt
origin  https://github.com/aegif/NemakiWare.git (fetch)
origin  https://github.com/aegif/NemakiWare.git (push)
```

---

## 📖 Reading Guide

### For Reviewers

**Start here**: `README.md`
- Executive summary
- Complete test results breakdown
- FilingTestGroup exclusion rationale
- Technical implementation details
- Reproducibility instructions

**Then verify**: `VERIFICATION.md`
- Run verification commands
- Check file integrity
- Confirm test counts
- Validate execution times

**Finally inspect**: `surefire-reports/`
- Review XML reports for test automation
- Check TXT summaries for human readability
- Examine OUTPUT logs for detailed execution traces

### For Auditors

1. **Git State Verification**
   ```bash
   cat branch.txt commit-hash.txt remote.txt git-status.txt
   ```

2. **Test Count Verification**
   ```bash
   grep "Tests run:" surefire-reports/*.txt
   ```

3. **Failure/Error Verification**
   ```bash
   grep -E "Failures: [^0]|Errors: [^0]" surefire-reports/*.txt
   # Should return nothing (no failures or errors)
   ```

4. **XML Report Validation**
   ```bash
   xmllint --noout surefire-reports/TEST-*.xml
   # Should validate without errors
   ```

### For Reproduction

**Prerequisites**: Docker, Java 17, Maven 3.6+

**Steps**:
```bash
# 1. Clone repository
git clone https://github.com/aegif/NemakiWare.git
cd NemakiWare

# 2. Checkout exact commit
git checkout 37a59efba7251d33217d75399930c9b9a419be27

# 3. Start environment
cd docker
docker compose -f docker-compose-simple.yml up -d
sleep 90

# 4. Run tests
cd ..
./tck-test-clean.sh

# 5. Compare results
diff -r core/target/surefire-reports tck-evidence-2025-10-11/surefire-reports
```

---

## 🎯 Key Findings

### Test Coverage
- ✅ **36/36 tests PASS** (100% success rate)
- ✅ **6 test groups** executed (BasicsTestGroup, TypesTestGroup, ControlTestGroup, VersioningTestGroup, QueryTestGroup, CrudTestGroup)
- ✅ **Zero failures, zero errors, zero timeouts**
- ✅ **Full-scale execution** (52-60 objects per test, no arbitrary reduction)

### Performance
- Total execution time: 1404 seconds (23.4 minutes)
- Document creation: ~8 seconds per document (measured baseline)
- No timeout issues with 20-minute client readtimeout
- 86% performance improvement with database cleanup (QueryTestGroup: 38min → 5.7min)

### Technical Achievements
- Memory optimization: 1GB → 3GB Java heap (resolved OutOfMemoryError)
- Timeout configuration: 10min → 20min readtimeout (handles full-scale tests)
- Database cleanup automation: `tck-test-clean.sh` script (reproducible execution)

### CMIS 1.1 Compliance
- ✅ All implemented features are CMIS 1.1 compliant
- ✅ FilingTestGroup excluded (multifiling/unfiling intentionally not implemented - optional per spec)
- ✅ Proper capability reporting (repository correctly reports unsupported features)

---

## 📦 Archive Format

**File**: `tck-evidence-2025-10-11.tar.gz` (13KB)

**Extract**:
```bash
tar -xzf tck-evidence-2025-10-11.tar.gz
cd tck-evidence-2025-10-11
cat README.md
```

**Contents**:
```
tck-evidence-2025-10-11/
├── README.md              # Main documentation
├── VERIFICATION.md        # Verification guide
├── INDEX.md              # This file
├── branch.txt            # Git branch
├── commit-hash.txt       # Git commit SHA
├── git-log.txt          # Git history
├── git-status.txt       # Working directory status
├── remote.txt           # Git remote
└── surefire-reports/    # Test results
    ├── TEST-*.xml       # JUnit XML reports (6 files)
    ├── *-Group.txt      # Test summaries (6 files)
    └── *-output.txt     # Console output (6 files)
```

---

## 🔗 Related Resources

- **Repository**: https://github.com/aegif/NemakiWare.git
- **Branch**: feature/react-ui-playwright
- **Commit**: 37a59efba7251d33217d75399930c9b9a419be27
- **Test Script**: `tck-test-clean.sh` (automated TCK execution with cleanup)
- **CMIS Spec**: [CMIS 1.1 Specification](http://docs.oasis-open.org/cmis/CMIS/v1.1/CMIS-v1.1.html)

---

## 📝 Notes

### FilingTestGroup Exclusion

NemakiWare **intentionally does not implement** CMIS multifiling and unfiling features:

- **Multifiling**: A document existing in multiple folders simultaneously
- **Unfiling**: Removing a document from a folder without deletion

**Rationale**:
- Simplifies data model (single parent per object)
- Prevents synchronization complexity
- Streamlines ACL inheritance

**CMIS 1.1 Compliance**: These are **optional** capabilities. A repository is compliant if it:
1. Correctly implements all features it claims to support ✅
2. Properly reports unsupported capabilities ✅

NemakiWare meets both criteria.

### Test Execution Environment

- **OS**: macOS (Darwin 24.6.0)
- **Java**: JBR 17.0.12
- **Docker**: Docker Compose with 3-container setup (CouchDB, Solr, Core)
- **Database**: CouchDB 3.3.3
- **Search**: Apache Solr 9.x
- **Application Server**: Tomcat 10.1 (Jakarta EE)

---

**Package Prepared**: 2025-10-11
**Evidence Integrity**: ✅ Complete and verified
**Reproducibility**: ✅ Automated via tck-test-clean.sh

# Solr 9.x Upgrade Analysis and Strategy

## Executive Summary

This document outlines the comprehensive analysis and migration strategy for upgrading Apache Solr from version 4.x to 9.8.0 in the NemakiWare project to prepare for Java 17 compatibility.

## Current State Analysis

### Version Inconsistencies
- **Solr Module**: Version 4.10.4 (solr/pom.xml)
- **Core Module**: Version 4.0.0 (core/pom.xml)
- **Target Version**: 9.8.0 (latest stable, Java 17 compatible)

### Key Dependencies Affected
```xml
<!-- Current Dependencies -->
<org.apache.solr.version>4.10.4</org.apache.solr.version>
<dependency>
    <groupId>org.apache.solr</groupId>
    <artifactId>solr-solrj</artifactId>
    <version>4.0.0</version>
</dependency>
<dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-queries</artifactId>
    <version>4.0.0</version>
</dependency>
```

## Breaking Changes Analysis

### 1. API Migration Requirements

#### SolrServer → SolrClient Migration
**Impact**: 21+ Java files across multiple modules
**Key Files**:
- `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrUtil.java`
- `solr/src/main/java/jp/aegif/nemaki/tracker/CoreTracker.java`
- `solr/src/main/java/jp/aegif/nemaki/NemakiCoreAdminHandler.java`
- `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrQueryProcessor.java`
- `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrPredicateWalker.java`

#### Specific API Changes Required:
```java
// OLD (Solr 4.x)
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.SolrServerException;

SolrServer server = new HttpSolrServer(url);
SolrServer embedded = new EmbeddedSolrServer(container, core);

// NEW (Solr 9.x)
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrClient;
import org.apache.solr.client.solrj.SolrClientException;

SolrClient client = new HttpSolrClient.Builder(url).build();
SolrClient embedded = new EmbeddedSolrClient(container, core);
```

### 2. Configuration File Updates

#### Lucene Version Updates
**Files Affected**:
- `solr/solr/nemaki/conf/solrconfig.xml`
- `solr/solr/token/conf/solrconfig.xml`
- `docker/solr/solr/nemaki/conf/solrconfig.xml`
- `docker/solr/solr/token/conf/solrconfig.xml`

**Change Required**:
```xml
<!-- OLD -->
<luceneMatchVersion>LUCENE_40</luceneMatchVersion>

<!-- NEW -->
<luceneMatchVersion>9.8.0</luceneMatchVersion>
```

#### Schema Version Updates
**Files Affected**:
- `solr/solr/nemaki/conf/schema.xml`
- `solr/solr/token/conf/schema.xml`
- `docker/solr/solr/nemaki/conf/schema.xml`
- `docker/solr/solr/token/conf/schema.xml`

**Change Required**:
```xml
<!-- OLD -->
<schema name="nemaki" version="1.5">

<!-- NEW -->
<schema name="nemaki" version="1.7">
```

## Migration Strategy

### Phase 1: Dependency Updates ✅ COMPLETED
- [x] Update solr/pom.xml: 4.10.4 → 9.8.0
- [x] Update core/pom.xml: 4.0.0 → 9.8.0
- [x] Ensure consistent versioning across all modules

### Phase 2: API Migration ⚠️ IN PROGRESS
- [x] Update import statements (SolrServer → SolrClient)
- [x] Update variable declarations and method signatures
- [x] Update constructor calls (HttpSolrServer → HttpSolrClient.Builder)
- [x] Update exception handling (SolrServerException → SolrClientException)
- [ ] Complete remaining method name updates (getSolrServer → getSolrClient)
- [ ] Update method calls throughout codebase

### Phase 3: Configuration Updates ⚠️ IN PROGRESS
- [x] Update luceneMatchVersion in solrconfig.xml files
- [ ] Update schema versions in schema.xml files
- [ ] Validate configuration compatibility

### Phase 4: Testing and Validation ⏳ PENDING
- [ ] Maven build verification (`mvn clean install`)
- [ ] Docker test execution
- [ ] Installer test verification
- [ ] Functional testing of search and indexing
- [ ] Performance validation

## Risk Assessment

### Critical Risk Areas ⚠️
1. **Java Version Incompatibility**: Solr 9.x requires Java 11+ (BLOCKING ISSUE)
2. **Method Signature Changes**: Core functionality depends on Solr client methods
3. **Configuration Compatibility**: Schema changes may require reindexing
4. **Exception Handling**: Different exception types may affect error handling
5. **Performance Impact**: Solr 9.x may have different performance characteristics
6. **Ecosystem Dependencies**: Other Java 8-dependent components may conflict

### Mitigation Strategies
1. **Java Runtime Strategy**: Plan comprehensive Java upgrade across entire stack
2. **Incremental Testing**: Test each module independently after Java upgrade
3. **Backup Strategy**: Maintain git checkpoints for rollback
4. **Compatibility Layer**: Consider temporary compatibility methods if needed
5. **Comprehensive Logging**: Enhanced logging during migration testing
6. **Parallel Environment**: Develop and test in Java 11+ environment before production migration

## Java Compatibility Strategy

### Current State
- **Java Version**: 1.8 (maintained for compatibility)
- **Solr 9.x Requirement**: Java 11+ minimum (CRITICAL BLOCKER)
- **Target**: Java 17 compatibility preparation

### Critical Compatibility Issue ⚠️
**Build Error Encountered**: 
```
cannot access org.apache.lucene.document.DateTools
bad class file: lucene-core-9.8.0.jar(org/apache/lucene/document/DateTools.class)
class file has wrong version 55.0, should be 52.0
```

**Analysis**: 
- Java 8 uses class file version 52.0
- Lucene/Solr 9.x uses class file version 55.0 (Java 11+)
- **Direct upgrade from Solr 4.x to 9.x while maintaining Java 8 is IMPOSSIBLE**

### Revised Transition Plan
1. **Phase 1**: Java Runtime Upgrade (Java 8 → Java 11/17) - PREREQUISITE
2. **Phase 2**: Solr API Migration (4.x → 9.x APIs)
3. **Phase 3**: Configuration and Testing Updates
4. **Phase 4**: Full Integration Testing and Deployment

### Alternative Strategies
1. **Intermediate Upgrade Path**: Solr 4.x → Solr 8.x (Java 8 compatible) → Solr 9.x (Java 11+)
2. **Parallel Development**: Maintain Java 8 branch while developing Java 11+ branch
3. **Container-Based Approach**: Use Docker with Java 11+ for Solr services only

## Implementation Status

### Completed ✅
- Solr version updates in pom.xml files
- Core API import statement updates
- Basic constructor and method signature updates
- Lucene version updates in configuration files

### In Progress ⚠️
- Method name migrations (getSolrServer → getSolrClient)
- Exception handling updates
- Schema version updates
- Comprehensive testing

### Pending ⏳
- Full build verification
- Docker integration testing
- Performance validation
- Documentation updates

## Testing Strategy

### Unit Testing
- Verify all Solr client instantiations work correctly
- Test exception handling with new exception types
- Validate configuration parsing

### Integration Testing
- Docker container startup with Solr 9.x
- End-to-end search functionality
- Indexing operations verification
- CMIS query processing

### Performance Testing
- Search response times comparison
- Indexing throughput validation
- Memory usage analysis
- Startup time measurement

## Rollback Plan

### Immediate Rollback
```bash
git checkout feature/izpack-5.2.4-migration
```

### Selective Rollback
- Revert specific files using git checkout
- Maintain Spring Framework 5.3.39 upgrade
- Preserve other improvements

## Next Steps

1. **Complete API Migration**: Finish remaining method name updates
2. **Configuration Validation**: Complete schema version updates
3. **Build Testing**: Execute comprehensive Maven build
4. **Integration Testing**: Run Docker and Installer tests
5. **Performance Validation**: Benchmark against Solr 4.x baseline
6. **Documentation**: Update deployment and configuration guides

## Success Criteria

### Prerequisites
- [ ] Java runtime upgraded to 11+ across all environments
- [ ] All Java 8-dependent components assessed for compatibility
- [ ] Development and testing environments prepared with Java 11+

### Implementation Success Criteria
- [ ] All modules build successfully with Solr 9.8.0 on Java 11+
- [ ] Docker tests pass with new Solr version and Java runtime
- [ ] Installer tests complete successfully with updated Java requirements
- [ ] No deprecated API usage remains
- [ ] Search and indexing functionality preserved
- [ ] Performance within acceptable range of Solr 4.x baseline
- [ ] Java 17 compatibility confirmed for future migration
- [ ] Full reindexing process documented and tested

## Conclusion

The Solr 9.x upgrade represents a **major architectural change** that requires Java runtime modernization as a prerequisite. The investigation reveals that a direct upgrade while maintaining Java 8 compatibility is technically impossible due to bytecode version incompatibilities.

### Key Findings
1. **Critical Blocker**: Solr 9.x requires Java 11+ runtime - no Java 8 compatibility possible
2. **Scope Expansion**: Project must include comprehensive Java runtime upgrade
3. **API Migration**: 21+ Java files require Solr API updates (SolrServer → SolrClient)
4. **Configuration Updates**: 8+ configuration files need version and schema updates
5. **Reindexing Required**: Schema version changes will necessitate full data reindexing

### Recommended Approach
1. **Phase 1**: Java Runtime Assessment and Upgrade Planning
2. **Phase 2**: Development Environment Setup with Java 11+
3. **Phase 3**: Solr API Migration and Testing
4. **Phase 4**: Production Migration with Comprehensive Testing

### Resource Requirements
- **Development Time**: 4-6 development cycles (expanded from original 2-3)
- **Risk Level**: High (Java runtime change affects entire application stack)
- **Business Impact**: Very High (enables Java 17 migration but requires significant infrastructure changes)
- **Testing Requirements**: Comprehensive integration testing across all modules and environments

### Decision Point
This investigation reveals that the Solr 9.x upgrade is intrinsically linked to Java runtime modernization. The project scope must be expanded to include Java 11+ migration, or alternative approaches (intermediate Solr versions, containerization) should be considered.

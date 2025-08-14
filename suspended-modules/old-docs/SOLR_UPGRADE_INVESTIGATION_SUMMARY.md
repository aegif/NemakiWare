# Solr 9.x Upgrade Investigation Summary

## Executive Summary

The investigation into upgrading Apache Solr from version 4.x to 9.x for NemakiWare has revealed a **critical compatibility barrier** that significantly expands the project scope. The upgrade cannot be completed while maintaining Java 8 compatibility, as Solr 9.x requires Java 11+ runtime.

## Critical Finding: Java Version Incompatibility

### Build Error Evidence
```
[ERROR] cannot access org.apache.lucene.document.DateTools
[ERROR] bad class file: lucene-core-9.8.0.jar(org/apache/lucene/document/DateTools.class)
[ERROR] class file has wrong version 55.0, should be 52.0
```

### Technical Analysis
- **Java 8**: Bytecode version 52.0
- **Solr 9.x/Lucene 9.x**: Bytecode version 55.0 (Java 11+)
- **Conclusion**: Direct upgrade impossible without Java runtime modernization

## Current State Analysis

### Solr Version Inconsistencies
- **Solr Module**: 4.10.4 (solr/pom.xml)
- **Core Module**: 4.0.0 (core/pom.xml)
- **Target**: 9.8.0 (requires Java 11+)

### Code Impact Assessment
- **21+ Java files** require API migration (SolrServer → SolrClient)
- **8+ configuration files** need version updates
- **4 schema files** require version updates (1.5 → 1.7)
- **Multiple exception handling** updates needed

### Key Files Requiring Changes
1. `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrUtil.java`
2. `solr/src/main/java/jp/aegif/nemaki/tracker/CoreTracker.java`
3. `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrQueryProcessor.java`
4. `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrPredicateWalker.java`
5. `solr/src/main/java/jp/aegif/nemaki/NemakiCoreAdminHandler.java`

## Strategic Options

### Option 1: Comprehensive Java + Solr Upgrade
**Approach**: Upgrade Java runtime to 11+ then migrate to Solr 9.x
- **Pros**: Future-proof, enables Java 17 migration, modern Solr features
- **Cons**: High risk, extensive testing required, affects entire application stack
- **Timeline**: 4-6 development cycles
- **Risk**: High

### Option 2: Intermediate Upgrade Path
**Approach**: Solr 4.x → Solr 8.x (Java 8 compatible) → Solr 9.x (Java 11+)
- **Pros**: Gradual migration, reduced risk per step
- **Cons**: Extended timeline, multiple migration phases
- **Timeline**: 6-8 development cycles
- **Risk**: Medium

### Option 3: Containerized Approach
**Approach**: Use Docker containers with Java 11+ for Solr services only
- **Pros**: Isolated Java upgrade, maintains Java 8 for main application
- **Cons**: Complex deployment, container orchestration overhead
- **Timeline**: 3-4 development cycles
- **Risk**: Medium

### Option 4: Maintain Current Solr Version
**Approach**: Keep Solr 4.x, focus on other Java 17 preparation tasks
- **Pros**: No immediate risk, maintains stability
- **Cons**: Technical debt accumulation, eventual forced upgrade
- **Timeline**: 0 cycles (status quo)
- **Risk**: Low (short-term), High (long-term)

## Detailed Migration Requirements

### API Changes Required
```java
// OLD (Solr 4.x)
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.client.solrj.SolrServerException;

SolrServer server = new HttpSolrServer(url);

// NEW (Solr 9.x)
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.SolrClientException;

SolrClient client = new HttpSolrClient.Builder(url).build();
```

### Configuration Updates Required
- **Lucene Version**: `LUCENE_40` → `9.8.0`
- **Schema Version**: `1.5` → `1.7`
- **Java Target**: `1.8` → `11+`

## Risk Assessment Matrix

| Risk Factor | Probability | Impact | Mitigation |
|-------------|-------------|---------|------------|
| Java Runtime Incompatibility | High | Critical | Comprehensive Java upgrade planning |
| API Breaking Changes | High | High | Systematic API migration with testing |
| Configuration Conflicts | Medium | High | Staged configuration updates |
| Performance Degradation | Medium | Medium | Baseline performance testing |
| Data Reindexing Issues | Medium | High | Full backup and reindexing procedures |
| Integration Failures | High | Critical | Comprehensive integration testing |

## Recommendations

### Immediate Actions
1. **Stakeholder Decision**: Choose strategic approach based on business priorities
2. **Environment Assessment**: Evaluate Java 11+ compatibility across entire stack
3. **Timeline Planning**: Allocate appropriate development cycles based on chosen approach
4. **Resource Allocation**: Ensure adequate testing and rollback capabilities

### If Proceeding with Option 1 (Recommended for Long-term)
1. **Phase 1**: Java 11+ upgrade planning and environment preparation
2. **Phase 2**: Development environment setup and initial compatibility testing
3. **Phase 3**: Solr API migration with comprehensive unit testing
4. **Phase 4**: Integration testing and performance validation
5. **Phase 5**: Production migration with full backup and rollback procedures

### Success Metrics
- All modules compile and run on Java 11+
- Solr search and indexing functionality preserved
- Performance within 10% of baseline
- Zero data loss during migration
- Full rollback capability maintained

## Conclusion

The Solr 9.x upgrade investigation reveals that this is not merely a library upgrade but a **fundamental platform modernization** requiring Java runtime changes. The project scope must be expanded significantly, or alternative approaches should be considered based on business priorities and risk tolerance.

**Recommendation**: Proceed with comprehensive planning phase before implementation, with clear stakeholder approval for the expanded scope and timeline.

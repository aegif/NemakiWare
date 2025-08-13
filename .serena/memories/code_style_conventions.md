# Code Style and Conventions

## Programming Languages and Frameworks

### Java Code Style
- **Java Version**: Java 17 (mandatory)
- **Package Structure**: `jp.aegif.nemaki.*` (Japanese company structure)
- **Naming Conventions**: Standard Java camelCase for methods, PascalCase for classes
- **Jakarta EE**: Uses `jakarta.*` namespaces (migrated from `javax.*`)

### Example Java Class Structure
```java
package jp.aegif.nemaki.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/test")
public class SimpleTestResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response test() {
        return Response.ok("Jersey REST endpoint is working!").build();
    }
}
```

### Spring Framework Conventions
- **Configuration**: XML-based configuration in `applicationContext.xml`, `serviceContext.xml`, etc.
- **Bean Injection**: Uses `@Autowired` and XML configuration
- **Component Scanning**: Package `jp.aegif.nemaki` with appropriate annotations

### React/TypeScript UI Conventions
- **Framework**: React 18 with TypeScript
- **UI Library**: Ant Design components
- **Build Tool**: Vite
- **File Structure**: Standard React component structure in `src/components/`
- **Naming**: PascalCase for components, camelCase for functions and variables

### Package.json Scripts
```json
{
  "scripts": {
    "dev": "vite",           // Development server
    "build": "vite build",   // Production build
    "preview": "vite preview", // Preview build
    "type-check": "tsc --noEmit" // Type checking
  }
}
```

## Maven Configuration Patterns

### POM Structure
- **Parent POM**: Root level with common properties
- **Module POMs**: Individual module configurations
- **Properties**: Consistent version management across modules
- **Profiles**: `development` (default) and `product`

### Dependency Management
- **Jakarta EE Libraries**: Custom Jakarta-converted JARs in `/lib/jakarta-converted/`
- **OpenCMIS Version**: Consistently 1.1.0 across all modules
- **Spring Version**: 6.1.13
- **Jersey Version**: 3.1.10

## Configuration File Conventions

### Spring Configuration
- **applicationContext.xml**: Main Spring context
- **serviceContext.xml**: CMIS service definitions (700+ lines)
- **daoContext.xml**: Data access with caching decorators
- **couchContext.xml**: CouchDB connection configuration

### Docker Configuration
- **Compose Files**: `docker-compose-simple.yml` for standard development
- **Multi-stage Builds**: Separate build and runtime stages
- **Environment Variables**: Consistent naming with COUCHDB_, SOLR_, JAVA_OPTS prefixes

## Testing Conventions

### Test Structure
- **Unit Tests**: Currently disabled with `@Ignore` annotations due to timeout issues
- **Integration Tests**: Primary testing via `qa-test.sh` script
- **Test Profiles**: Tests disabled in development profile but enabled in structure

### QA Test Categories
1. Environment Verification (Java 17, Docker containers)
2. Database Initialization (CouchDB connectivity, repository creation)
3. Core Application (HTTP endpoints, CMIS bindings)
4. CMIS Query System (Document/folder queries, SQL parsing)
5. Search Integration (Solr connectivity)
6. Jakarta EE Compatibility (Servlet API verification)

## Documentation Standards

### README and Documentation
- **CLAUDE.md**: Primary development documentation with comprehensive procedures
- **Markdown Format**: Consistent heading structure and code block formatting
- **Command Examples**: Always include full command paths and expected outputs
- **Status Indicators**: Use ✅ for completed items, ❌ for issues

### Code Comments
- **Minimal Comments**: Code should be self-documenting
- **JavaDoc**: Standard JavaDoc for public APIs
- **Configuration Comments**: XML configuration files have descriptive comments

## Git and Version Control

### Branch Strategy
- **Main Branch**: `master`
- **Feature Branches**: `feature/` prefix for new features
- **Current Branch**: `feature/fix-react-ui-api-paths`

### Commit Conventions
- **Descriptive Messages**: Clear, concise commit messages
- **Feature Commits**: Use "feat:" prefix for new features
- **Fix Commits**: Use "fix:" prefix for bug fixes

## System-Specific Conventions (Darwin/macOS)

### File Paths
- **Java Home**: `/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home`
- **Project Root**: `/Users/ishiiakinori/NemakiWare`
- **Case Sensitivity**: Aware of case-sensitive filesystem

### Command Differences
- **Tools**: Uses standard Unix commands (no special Darwin variations needed)
- **Java**: Uses JetBrains Runtime (jbr) Java 17 distribution
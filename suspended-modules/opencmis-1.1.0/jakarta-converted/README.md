# Jakarta EE Converted JARs

This directory contains JAR files that have been converted from Java EE (javax.*) to Jakarta EE (jakarta.*) namespace using Eclipse Transformer.

## Converted JARs

### OpenCMIS 1.1.0 (Stable Release)
- **chemistry-opencmis-client-bindings-1.1.0-jakarta.jar** - OpenCMIS client bindings (Jakarta EE)
- **chemistry-opencmis-commons-api-1.1.0-jakarta.jar** - OpenCMIS commons API (Jakarta EE)
- **chemistry-opencmis-commons-impl-1.1.0-jakarta.jar** - OpenCMIS commons implementation (Jakarta EE)
- **chemistry-opencmis-server-bindings-1.1.0-jakarta.jar** - OpenCMIS server bindings (Jakarta EE)
- **chemistry-opencmis-server-support-1.1.0-jakarta.jar** - OpenCMIS server support (Jakarta EE)
- **chemistry-opencmis-test-tck-1.1.0-jakarta.jar** - OpenCMIS Test Compatibility Kit (Jakarta EE)

### OpenCMIS 1.2.0-SNAPSHOT (Custom Build)
- **chemistry-opencmis-client-api-1.2.0-SNAPSHOT-jakarta.jar** - OpenCMIS client API (Jakarta EE)
- **chemistry-opencmis-client-bindings-1.2.0-SNAPSHOT-jakarta.jar** - OpenCMIS client bindings (Jakarta EE)
- **chemistry-opencmis-client-impl-1.2.0-SNAPSHOT-jakarta.jar** - OpenCMIS client implementation (Jakarta EE)
- **chemistry-opencmis-commons-api-1.2.0-SNAPSHOT-jakarta.jar** - OpenCMIS commons API (Jakarta EE)
- **chemistry-opencmis-commons-impl-1.2.0-SNAPSHOT-jakarta.jar** - OpenCMIS commons implementation (Jakarta EE)
- **chemistry-opencmis-server-bindings-1.2.0-SNAPSHOT-jakarta.jar** - OpenCMIS server bindings (Jakarta EE)
- **chemistry-opencmis-server-support-1.2.0-SNAPSHOT-jakarta.jar** - OpenCMIS server support (Jakarta EE)
- **chemistry-opencmis-test-tck-1.2.0-SNAPSHOT-jakarta.jar** - OpenCMIS Test Compatibility Kit (Jakarta EE)

### JAX-WS Runtime
- **jaxws-rt-4.0.2-jakarta.jar** - JAX-WS runtime (Jakarta EE)

## Usage

These JARs are used when deploying to Jakarta EE compatible containers (e.g., Tomcat 10+, Jetty 11+).

To use these JARs:
1. Remove the original javax.* versions from your classpath
2. Add these jakarta.* versions
3. Ensure your container supports Jakarta EE

## Generation

### OpenCMIS 1.2.0-SNAPSHOT Custom Build
The 1.2.0-SNAPSHOT versions were built from the Apache Chemistry OpenCMIS source code with custom modifications for NemakiWare compatibility. The build process:
1. Cloned from Apache Chemistry repository
2. Built with Maven to create the SNAPSHOT JARs
3. Converted to Jakarta EE using Eclipse Transformer

### Jakarta Conversion
All JARs were converted from javax.* to jakarta.* namespace using Eclipse Transformer 0.5.0 with the following script:
```bash
docker/jakarta-transform.sh
```

## Important Notes

- These JARs are provided for compatibility with Jakarta EE environments
- They should NOT be used with traditional Java EE containers (Tomcat 9, etc.)
- Original JARs are preserved for backward compatibility
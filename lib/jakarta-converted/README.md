# Jakarta EE Converted JARs

This directory contains JAR files that have been converted from Java EE (javax.*) to Jakarta EE (jakarta.*) namespace using Eclipse Transformer.

## Converted JARs

- **chemistry-opencmis-client-bindings-1.1.0-jakarta.jar** - OpenCMIS client bindings (Jakarta EE)
- **chemistry-opencmis-commons-api-1.1.0-jakarta.jar** - OpenCMIS commons API (Jakarta EE)
- **chemistry-opencmis-commons-impl-1.1.0-jakarta.jar** - OpenCMIS commons implementation (Jakarta EE)
- **chemistry-opencmis-server-bindings-1.1.0-jakarta.jar** - OpenCMIS server bindings (Jakarta EE)
- **chemistry-opencmis-server-support-1.1.0-jakarta.jar** - OpenCMIS server support (Jakarta EE)
- **jaxws-rt-4.0.2-jakarta.jar** - JAX-WS runtime (Jakarta EE)

## Usage

These JARs are used when deploying to Jakarta EE compatible containers (e.g., Tomcat 10+, Jetty 11+).

To use these JARs:
1. Remove the original javax.* versions from your classpath
2. Add these jakarta.* versions
3. Ensure your container supports Jakarta EE

## Generation

These JARs were generated using Eclipse Transformer 0.5.0 with the following script:
```bash
docker/jakarta-transform.sh
```

## Important Notes

- These JARs are provided for compatibility with Jakarta EE environments
- They should NOT be used with traditional Java EE containers (Tomcat 9, etc.)
- Original JARs are preserved for backward compatibility
# OData conformance validation

There is no maintained standalone "OData Service Validation Tool" any more
(Microsoft's hosted `validator.odata.org` was retired). This directory documents
the three complementary validations we run against the NemakiWare OData binding
(`/core/odata/{repositoryId}`) instead.

Run all three against a running instance (see `CLAUDE.md` for how to start the
stack). Results at last run (v3.3): **all three green**.

## 1. Olingo client validation (reference-implementation consumption)

The strongest practical check: make Apache Olingo — the Java OData 4.0 reference
implementation — consume the service with its **client** library. If the
reference consumer can read `$metadata` into a valid `Edm`, read the service
document, and deserialize an entity collection / a single entity, the emitted
CSDL and payloads are conformant at the level Olingo enforces.

```bash
J21=/opt/homebrew/opt/openjdk@21        # a Java 21 home
JAVA_HOME=$J21 PATH=$J21/bin:$PATH mvn test \
  -Dtest=ODataOlingoClientValidationIT \
  -Dnemaki.test.baseUrl=http://localhost:8080/core \
  -Djunit.jupiter.conditions.deactivate='*' \
  -f core/pom.xml -Pdevelopment
```

Source: `core/src/test/java/jp/aegif/nemaki/odata/ODataOlingoClientValidationIT.java`
(4 tests: metadata→Edm, service document, entity collection, single entity +
`$top`/`$count`). The functional REST suite lives alongside it
(`ODataDocumentsIT` / `ODataFoldersIT`, 65 tests).

## 2. CSDL XSD validation ($metadata against the OASIS schema)

Validate the emitted `$metadata` against the official OASIS OData 4.0 EDMX/CSDL
XML schema:

```bash
mkdir -p /tmp/odata-xsd && cd /tmp/odata-xsd
curl -sSLO https://docs.oasis-open.org/odata/odata/v4.0/os/schemas/edmx.xsd
curl -sSLO https://docs.oasis-open.org/odata/odata/v4.0/os/schemas/edm.xsd
# edmx.xsd imports the edm namespace without a schemaLocation; point it at edm.xsd:
sed -i '' 's#<xs:import namespace="http://docs.oasis-open.org/odata/ns/edm" />#<xs:import namespace="http://docs.oasis-open.org/odata/ns/edm" schemaLocation="edm.xsd" />#' edmx.xsd
curl -sf -u admin:admin "http://localhost:8080/core/odata/bedroom/\$metadata" -o metadata.xml
xmllint --noout --schema edmx.xsd metadata.xml     # -> "metadata.xml validates"
```

## 3. Conformance-level checklist (Minimal MUST + Intermediate SHOULD)

Dependency-free checklist over the OData V4 conformance requirements: service
document, `$metadata`, `OData-Version` header, `@odata.context`, content-type
`odata.metadata` parameter, error `code`+`message` shape,
`$top`/`$skip`/`$count`/`$select`/`$orderby`/`$filter`/`$expand`, `$format`,
single-entity `/$entity` context + key, and an unbound function import.

```bash
python3 tools/odata-conformance/conformance_check.py \
  http://localhost:8080/core/odata/bedroom admin admin
```

## Known, documented limitations (not conformance failures)

- Unbound function imports require **all** declared parameters (OData overload
  resolution matches the full parameter set); e.g. `Query` needs `statement`,
  `searchAllVersions`, `maxItems`, `skipCount`.
- `GetObjectByPath(path='/…')`: a path value containing `/` is rejected by
  Tomcat's default encoded-slash guard (`%2F`). Use the `Query` function for
  path-like lookups.
- The `Types` / `Users` / `Groups` entity sets are declared in the EDM but not
  yet wired to a data source (they read as an empty collection).

OData here is a **secondary** binding; CMIS AtomPub / Browser Binding remain the
primary, TCK-covered interfaces.

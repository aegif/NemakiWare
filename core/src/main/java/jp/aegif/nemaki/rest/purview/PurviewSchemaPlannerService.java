package jp.aegif.nemaki.rest.purview;

public interface PurviewSchemaPlannerService {

    PurviewSchemaState getCurrentSchemaState();

    PurviewSchemaDiff getSchemaDiff();
}

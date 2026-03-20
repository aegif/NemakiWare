package jp.aegif.nemaki.rest.purview.schema;


import jp.aegif.nemaki.rest.purview.state.PurviewSchemaState;
public interface PurviewSchemaPlannerService {

    PurviewSchemaState getCurrentSchemaState();

    PurviewSchemaDiff getSchemaDiff();
}

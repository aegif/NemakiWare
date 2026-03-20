package jp.aegif.nemaki.rest.purview;

public interface PurviewSchemaStateService {

    PurviewSchemaState getSchemaState(String collection);

    PurviewSchemaState saveSchemaState(PurviewSchemaState schemaState);
}

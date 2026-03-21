package jp.aegif.nemaki.rest.purview.state;

public interface PurviewSchemaStateService {

    PurviewSchemaState getSchemaState(String collection);

    PurviewSchemaState saveSchemaState(PurviewSchemaState schemaState);
}

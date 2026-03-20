package jp.aegif.nemaki.rest.purview.schema;

public interface PurviewSchemaBootstrapService {

    PurviewSchemaBootstrapResult startTypeBootstrap(String requestedBy);
}

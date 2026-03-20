package jp.aegif.nemaki.rest.purview;

public interface PurviewSchemaBootstrapService {

    PurviewSchemaBootstrapResult startTypeBootstrap(String requestedBy);
}

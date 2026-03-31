package jp.aegif.nemaki.rest.purview;

import java.util.Map;

public interface PurviewConnectionService {

    PurviewConnectionStatus testConnection();

    PurviewConnectionStatus testConnection(Map<String, String> formValues);

    PurviewConnectionStatus testAtlasConnection(Map<String, String> formValues);
}

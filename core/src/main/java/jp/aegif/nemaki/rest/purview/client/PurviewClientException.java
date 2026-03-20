package jp.aegif.nemaki.rest.purview.client;

public class PurviewClientException extends Exception {

    private static final long serialVersionUID = 1L;

    public PurviewClientException(String message) {
        super(message);
    }

    public PurviewClientException(String message, Throwable cause) {
        super(message, cause);
    }
}

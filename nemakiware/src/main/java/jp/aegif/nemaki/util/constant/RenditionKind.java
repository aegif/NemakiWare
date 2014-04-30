package jp.aegif.nemaki.util.constant;

public enum RenditionKind {
	CMIS_THUMBNAIL("cmis:thumbnail");

	private final String value;

	RenditionKind(String v) {
        value = v;
    }

    public String value() {
        return value;
    }

    public static RenditionKind fromValue(String v) {
        for (RenditionKind ot : RenditionKind.values()) {
            if (ot.value.equals(v)) {
                return ot;
            }
        }
        throw new IllegalArgumentException(v);
    }
}
package jp.aegif.nemaki.model.constant;

public enum DomainType {
	OBJECT("object"), OBJECT_TYPE("objectType"), REPOSITORY("repository");
	
	private final String value;

    DomainType(String v) {
        value = v;
    }

    public String value() {
        return value;
    }

    public static DomainType fromValue(String v) {
        for (DomainType dt : DomainType.values()) {
            if (dt.value.equals(v)) {
                return dt;
            }
        }
        throw new IllegalArgumentException(v);
    }
}
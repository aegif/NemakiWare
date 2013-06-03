package jp.aegif.nemaki.model.constant;

public enum NodeType {
	CMIS_DOCUMENT("cmis:document"), 
	CMIS_FOLDER("cmis:folder"),
	CMIS_RELATIONSHIP("cmis:relationship"),
	CMIS_POLICY("cmis:policy"),
	CMIS_ITEM("cmis:item"),
	ATTACHMENT("attachment"),
	VERSION_SERIES("versionSeries"),
	CHANGE("change"),
	USER("user"),
	GROUP("group");
	
	private final String value;

	NodeType(String v) {
        value = v;
    }

    public String value() {
        return value;
    }

    public static NodeType fromValue(String v) {
        for (NodeType ot : NodeType.values()) {
            if (ot.value.equals(v)) {
                return ot;
            }
        }
        throw new IllegalArgumentException(v);
    }
	
}

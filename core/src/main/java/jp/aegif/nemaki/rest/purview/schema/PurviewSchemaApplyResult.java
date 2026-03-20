package jp.aegif.nemaki.rest.purview.schema;


import jp.aegif.nemaki.rest.purview.state.PurviewSchemaState;
public class PurviewSchemaApplyResult {

    private final boolean applied;
    private final String message;
    private final PurviewSchemaState schemaState;
    private final PurviewSchemaDiff schemaDiff;

    public PurviewSchemaApplyResult(
            boolean applied,
            String message,
            PurviewSchemaState schemaState,
            PurviewSchemaDiff schemaDiff) {
        this.applied = applied;
        this.message = message;
        this.schemaState = schemaState;
        this.schemaDiff = schemaDiff;
    }

    public boolean isApplied() {
        return applied;
    }

    public String getMessage() {
        return message;
    }

    public PurviewSchemaState getSchemaState() {
        return schemaState;
    }

    public PurviewSchemaDiff getSchemaDiff() {
        return schemaDiff;
    }
}

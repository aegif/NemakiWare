#!/bin/bash

# Jakarta EE Transformation Script using Eclipse Transformer

WORK_DIR="/tmp/jakarta-transform"
INPUT_DIR="$WORK_DIR/input"
OUTPUT_DIR="$WORK_DIR/output"

# Create working directories
mkdir -p "$INPUT_DIR" "$OUTPUT_DIR"

# Download Eclipse Transformer if not exists
if [ ! -f "$WORK_DIR/transformer.jar" ]; then
    echo "Downloading Eclipse Transformer..."
    curl -L -o "$WORK_DIR/transformer.jar" \
        "https://repo1.maven.org/maven2/org/eclipse/transformer/org.eclipse.transformer.cli/0.5.0/org.eclipse.transformer.cli-0.5.0.jar"
fi

# Download dependencies
DEPS_DIR="$WORK_DIR/deps"
mkdir -p "$DEPS_DIR"

echo "Downloading transformer dependencies..."
curl -L -o "$DEPS_DIR/transformer-core.jar" \
    "https://repo1.maven.org/maven2/org/eclipse/transformer/org.eclipse.transformer/0.5.0/org.eclipse.transformer-0.5.0.jar"
curl -L -o "$DEPS_DIR/bnd-transform.jar" \
    "https://repo1.maven.org/maven2/biz/aQute/bnd/biz.aQute.bnd.transform/6.3.1/biz.aQute.bnd.transform-6.3.1.jar"
curl -L -o "$DEPS_DIR/jakarta-rules.jar" \
    "https://repo1.maven.org/maven2/org/eclipse/transformer/org.eclipse.transformer.jakarta/0.5.0/org.eclipse.transformer.jakarta-0.5.0.jar"
curl -L -o "$DEPS_DIR/slf4j-api.jar" \
    "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"
curl -L -o "$DEPS_DIR/slf4j-simple.jar" \
    "https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/1.7.36/slf4j-simple-1.7.36.jar"
curl -L -o "$DEPS_DIR/commons-cli.jar" \
    "https://repo1.maven.org/maven2/commons-cli/commons-cli/1.5.0/commons-cli-1.5.0.jar"

# Set up classpath
CLASSPATH="$WORK_DIR/transformer.jar:$DEPS_DIR/transformer-core.jar:$DEPS_DIR/bnd-transform.jar:$DEPS_DIR/jakarta-rules.jar:$DEPS_DIR/slf4j-api.jar:$DEPS_DIR/slf4j-simple.jar:$DEPS_DIR/commons-cli.jar"

# Transform function
transform_jar() {
    local input_jar="$1"
    local output_jar="$2"
    echo "Transforming: $(basename $input_jar) -> $(basename $output_jar)"
    
    java -cp "$CLASSPATH" org.eclipse.transformer.cli.JakartaTransformerCLI \
        "$input_jar" "$output_jar" --verbose
}

# Copy JARs to transform
LIB_DIR="${PROJECT_ROOT:-$(pwd)}/core/target/core/WEB-INF/lib"
NEMAKI_DIR="${PROJECT_ROOT:-$(pwd)}"

echo "Copying OpenCMIS and related JARs for transformation..."
# Copy all OpenCMIS 1.1.0 JARs
cp "$LIB_DIR/chemistry-opencmis-server-bindings-1.1.0.jar" "$INPUT_DIR/"
cp "$LIB_DIR/chemistry-opencmis-commons-impl-1.1.0.jar" "$INPUT_DIR/"
cp "$LIB_DIR/chemistry-opencmis-commons-api-1.1.0.jar" "$INPUT_DIR/"
cp "$LIB_DIR/chemistry-opencmis-client-bindings-1.1.0.jar" "$INPUT_DIR/"
cp "$LIB_DIR/chemistry-opencmis-server-support-1.1.0.jar" "$INPUT_DIR/"
cp "$LIB_DIR/jaxws-rt-4.0.2.jar" "$INPUT_DIR/"

# Copy test-tck JAR if available
if [ -f "$NEMAKI_DIR/chemistry-opencmis-test-tck-1.1.0.jar" ]; then
    echo "Adding test-tck JAR for transformation..."
    cp "$NEMAKI_DIR/chemistry-opencmis-test-tck-1.1.0.jar" "$INPUT_DIR/"
fi

# Transform each JAR
echo "Starting transformation process..."

transform_jar "$INPUT_DIR/chemistry-opencmis-server-bindings-1.1.0.jar" \
              "$OUTPUT_DIR/chemistry-opencmis-server-bindings-1.1.0-jakarta.jar"

transform_jar "$INPUT_DIR/chemistry-opencmis-commons-impl-1.1.0.jar" \
              "$OUTPUT_DIR/chemistry-opencmis-commons-impl-1.1.0-jakarta.jar"

transform_jar "$INPUT_DIR/chemistry-opencmis-commons-api-1.1.0.jar" \
              "$OUTPUT_DIR/chemistry-opencmis-commons-api-1.1.0-jakarta.jar"

transform_jar "$INPUT_DIR/chemistry-opencmis-client-bindings-1.1.0.jar" \
              "$OUTPUT_DIR/chemistry-opencmis-client-bindings-1.1.0-jakarta.jar"

transform_jar "$INPUT_DIR/chemistry-opencmis-server-support-1.1.0.jar" \
              "$OUTPUT_DIR/chemistry-opencmis-server-support-1.1.0-jakarta.jar"

transform_jar "$INPUT_DIR/jaxws-rt-4.0.2.jar" \
              "$OUTPUT_DIR/jaxws-rt-4.0.2-jakarta.jar"

# Transform test-tck JAR if it was copied
if [ -f "$INPUT_DIR/chemistry-opencmis-test-tck-1.1.0.jar" ]; then
    echo "Transforming test-tck JAR..."
    transform_jar "$INPUT_DIR/chemistry-opencmis-test-tck-1.1.0.jar" \
                  "$OUTPUT_DIR/chemistry-opencmis-test-tck-1.1.0-jakarta.jar"
fi

echo "Transformation completed. Output JARs are in: $OUTPUT_DIR"
ls -la "$OUTPUT_DIR"

# Copy output JARs to jakarta-converted directory
JAKARTA_DIR="${PROJECT_ROOT:-$(pwd)}/lib/jakarta-converted"
echo "Copying transformed JARs to $JAKARTA_DIR..."
cp "$OUTPUT_DIR"/*.jar "$JAKARTA_DIR/"
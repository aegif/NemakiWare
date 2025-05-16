# NemakiWare Docker Environment Setup for macOS

This guide provides instructions for setting up the NemakiWare Docker environment on macOS, including Apple Silicon (M1/M2/M3) Macs.

## Prerequisites

### 1. Install Homebrew

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

### 2. Install Docker Desktop

```bash
brew install --cask docker
```

After installation, launch Docker Desktop from your Applications folder.

### 3. Install Java 8 (ARM64 Native for Apple Silicon)

For Apple Silicon Macs, install Azul Zulu 8 (ARM64 native):

```bash
brew install --cask zulu8
```

For Intel Macs, you can use:

```bash
brew install --cask temurin8
```

### 4. Install Maven

```bash
brew install maven
```

## Setting up Java Environment Variables

### For Zulu 8 on Apple Silicon Macs:

1. Find the Zulu JDK installation path:
   ```bash
   ls -la /Library/Java/JavaVirtualMachines/
   ```

2. Set up environment variables in your shell profile:
   ```bash
   echo 'export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home' >> ~/.zshrc
   echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.zshrc
   source ~/.zshrc
   ```

3. Verify the installation:
   ```bash
   java -version
   ```
   You should see output indicating Zulu JDK 8.

## Fixing Maven Build Issues

### JDK Tools Dependency Issue

When building the Solr component, you might encounter an error with the jdk.tools dependency. To fix this:

1. Apply the patch to the Solr POM file:
   ```bash
   cd ~/path/to/NemakiWare
   patch -p0 < solr/pom.xml.patch
   ```

   Or manually edit `solr/pom.xml` to change:
   ```xml
   <dependency>
     <groupId>jdk.tools</groupId>
     <artifactId>jdk.tools</artifactId>
     <version>1.8.0</version>
     <scope>system</scope>
     <systemPath>${java.home}/../lib/tools.jar</systemPath>
   </dependency>
   ```

   To:
   ```xml
   <dependency>
     <groupId>jdk.tools</groupId>
     <artifactId>jdk.tools</artifactId>
     <version>1.8.0</version>
     <scope>provided</scope>
   </dependency>
   ```

2. This change tells Maven that the JDK tools are provided by the JDK itself and don't need an explicit path.

## Building and Running the Docker Environment

1. Clone the repository:
   ```bash
   git clone https://github.com/aegif/NemakiWare.git
   cd NemakiWare
   git checkout devin/1747020750-couchdb3-init-without-bjornloka
   ```

2. Build the components:
   ```bash
   cd docker
   ./build.sh
   ```

3. Start the Docker environment:
   ```bash
   docker-compose up -d
   ```

4. Test the environment:
   ```bash
   ./test.sh
   ```

## Troubleshooting

### Java Version Issues

If you have multiple Java versions installed, make sure the correct one is active:

```bash
java -version
```

If it's not showing Java 8, check your JAVA_HOME setting:

```bash
echo $JAVA_HOME
```

### Docker Memory Issues

On macOS, Docker Desktop has memory limits. If you encounter memory-related errors:

1. Open Docker Desktop
2. Go to Preferences > Resources
3. Increase the memory allocation (at least 4GB recommended)
4. Click Apply & Restart

### Maven Build Failures

If Maven builds fail with other errors:

1. Try clearing the Maven cache:
   ```bash
   rm -rf ~/.m2/repository/jp/aegif
   ```

2. Make sure you're using Java 8:
   ```bash
   mvn -version
   ```

3. Check for any specific error messages and address them accordingly.

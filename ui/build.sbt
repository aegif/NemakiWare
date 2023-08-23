import com.github.play2war.plugin._

name := """ui"""

version := "2.4.0"

// lazy val root = (project in file(".")).enablePlugins(PlayJava, PlayEbean)
lazy val root = (project in file(".")).enablePlugins(PlayJava)

scalaVersion := "2.11.12"

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

initialize := {
  val _ = initialize.value
  if (sys.props("java.specification.version") != "1.8")
    sys.error("Java 8 is required for this project.")
}


resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
  evolutions,
  javaJdbc,
  cache,
  javaWs,
  "org.openjfx" % "javafx-media" % "14" pomOnly(),
  "jp.aegif.nemaki.common" % "nemakiware-common" % "2.4.1" changing(),
  "jp.aegif.nemaki.plugin" % "nemakiware-action" % "0.0.6" changing(),
  "commons-collections" % "commons-collections" % "3.2.1",
  "commons-io" % "commons-io" % "2.5",
  "org.webjars" %% "webjars-play" % "2.4.0-2",
  "org.webjars" % "bootstrap" % "3.3.7",
  "org.webjars" % "jquery" % "1.12.0",
  "org.webjars" % "jquery-ui" % "1.12.1",
  "org.webjars" % "font-awesome" % "4.7.0",
  "org.webjars.bower" % "footable" % "2.0.3",
  "org.webjars" % "bootstrap-select" % "1.12.0",
  "org.webjars" % "Eonasdan-bootstrap-datetimepicker" % "4.17.43",
  "org.webjars" % "momentjs" % "2.18.1",
  "org.apache.chemistry.opencmis" % "chemistry-opencmis-commons-api" % "1.1.0",
  "org.apache.chemistry.opencmis" % "chemistry-opencmis-commons-impl" % "1.1.0",
  "org.apache.chemistry.opencmis" % "chemistry-opencmis-client-api" % "1.1.0",
  "org.apache.chemistry.opencmis" % "chemistry-opencmis-client-impl" % "1.1.0",
  "org.apache.chemistry.opencmis" % "chemistry-opencmis-client-bindings" % "1.1.0",
  "org.apache.httpcomponents" % "httpclient" % "4.4-beta1",
  "net.lingala.zip4j" % "zip4j" % "1.3.2",
  "com.google.inject.extensions" % "guice-multibindings" % "4.0",
  "org.pac4j" % "play-pac4j" % "2.3.2",
  "org.pac4j" % "pac4j-saml" % "1.9.4",
  "org.pac4j" % "pac4j-http" % "1.9.4",
  "org.easytesting" % "fest-assert" % "1.4"
)

routesGenerator := InjectedRoutesGenerator

Play2WarPlugin.play2WarSettings

Play2WarKeys.servletVersion := "3.1"

Play2WarKeys.targetName := Option("ui")

Play2WarKeys.explodedJar := true

EclipseKeys.createSrc := EclipseCreateSrc.Default + EclipseCreateSrc.Resource
EclipseKeys.withSource := true

// Compile the project before generating Eclipse files, so that generated .scala or .class files for views and routes are present
EclipseKeys.preTasks := Seq(compile in Compile)

// Java project. Don't expect Scala IDE
EclipseKeys.projectFlavor := EclipseProjectFlavor.Java

  // Use .class files instead of generated .scala files for views and routes
EclipseKeys.createSrc := EclipseCreateSrc.ValueSet(EclipseCreateSrc.ManagedClasses, EclipseCreateSrc.ManagedResources)
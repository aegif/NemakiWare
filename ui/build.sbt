import com.github.play2war.plugin._

name := """ui"""

version := "2.4.0"

lazy val root = (project in file(".")).enablePlugins(PlayJava)

scalaVersion := "2.11.8"

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

initialize := {
  val _ = initialize.value
  if (sys.props("java.specification.version") != "1.8")
    sys.error("Java 8 is required for this project.")
}


resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
  javaJdbc,
  javaEbean,
  cache,
  javaWs,
	"jp.aegif.nemakiware" % "nemakiware-common" % "2.3.10",
  "commons-collections" % "commons-collections" % "3.2.1",
  "org.webjars" %% "webjars-play" % "2.3.0",
  "org.webjars" % "bootstrap" % "3.2.0",
  "org.webjars" % "jquery" % "1.11.1",
  "org.webjars" % "jquery-ui" % "1.11.1",
  "org.apache.chemistry.opencmis" % "chemistry-opencmis-client-impl" % "1.0.0",
  "org.apache.chemistry.opencmis" % "chemistry-opencmis-client-bindings" % "1.0.0",
  "org.apache.chemistry.opencmis" % "chemistry-opencmis-client-api" % "1.0.0",
  "org.apache.chemistry.opencmis" % "chemistry-opencmis-commons-impl" % "1.0.0",
  "org.apache.httpcomponents" % "httpclient" % "4.4-beta1",
	"net.lingala.zip4j" % "zip4j" % "1.3.2"
)

Play2WarPlugin.play2WarSettings

Play2WarKeys.servletVersion := "3.1"

Play2WarKeys.targetName := Option("ui")

EclipseKeys.createSrc := EclipseCreateSrc.Default + EclipseCreateSrc.Resource

// Compile the project before generating Eclipse files, so that generated .scala or .class files for views and routes are present
EclipseKeys.preTasks := Seq(compile in Compile)

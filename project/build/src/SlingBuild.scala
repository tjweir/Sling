import sbt._
import java.util.zip.ZipInputStream
import java.io.FileOutputStream
import java.net.URL

class SlingBuild(info: ProjectInfo) extends DefaultProject(info)
{
  val js_sources = outputPath / "js"
  val js_classpath = outputPath / "js_classes"

  override def mainClass = Some("net.databinder.sling.Server")
  override def unmanagedClasspath = super.unmanagedClasspath +++ js_classpath
  
  val snapshots = "Databinder Snapshots" at "http://databinder.net/snapshot/"
  val scala_tools = "Scala Tools Releases" at "http://scala-tools.org/repo-releases"

  val jetty = "org.mortbay.jetty" % "jetty-ajp" % "6.1.14"
  val dispatch = "net.databinder" % "databinder-dispatch" % "1.2.2-SNAPSHOT"
  val rhino = "rhino" % "js" % "1.7R1"
  val scalaz = "com.workingmouse" % "scalaz" % "3.0"

  
  override def ivyXML =
    <dependencies>
      <dependency org="slinky" name="slinky" rev="2.1" conf="default">
        <artifact name="slinky" url="http://slinky2.googlecode.com/svn/artifacts/2.1/slinky.jar" />
      </dependency>
    </dependencies>

  
  lazy val showdown = task {
    if (!js_classpath.exists) {
      FileUtilities.createDirectories(js_sources :: js_classpath :: Nil, log)
      val showdown_js = js_sources / "Showdown.js"
      def unzip(zis: ZipInputStream) {
        if (zis.getNextEntry.getName == "src/showdown.js") {
          FileUtilities.writeStream(showdown_js.asFile, log) { out =>
            FileUtilities.transfer(zis, out, log) orElse {
              out.write("\nfunction makeHtml(md) { return new Showdown.converter().makeHtml('' + md) }".getBytes); 
              None
            }
          }
        }
        else unzip(zis)
      }
      unzip(new ZipInputStream(new URL("http://attacklab.net/showdown/showdown-v0.9.zip").openStream()))
      Run.run(
        "org.mozilla.javascript.tools.jsc.Main",
        descendents(managedDependencyPath, "js-*.jar").get,
        "-d" :: js_classpath.toString :: "-package" :: "js" :: "-extends" :: "java.lang.Object" :: showdown_js.toString :: Nil,
        log
      )
    }
    None
  }
  override def compileAction = super.compileAction dependsOn(showdown)
  
  lazy val script = task {
    FileUtilities.writeStream((info.projectPath / "run.sh").asFile, log) { out =>
      out write (
        "#! /bin/sh\n\nnohup $JAVA_HOME/bin/java -cp " + 
        runClasspath.get.mkString(":") + 
        " $JAVA_OPTIONS " + mainClass.mkString + " >/dev/null &\\echo $! > run.pid\n"
      ).getBytes
      None
    }
  }
}
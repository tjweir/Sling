package sling

import org.mortbay.jetty.Connector
import org.mortbay.jetty.{Server => JettyServer}
import org.mortbay.resource.Resource
import org.mortbay.jetty.handler.ResourceHandler
import org.mortbay.jetty.servlet.{ServletHolder, Context}
import org.mortbay.jetty.ajp.Ajp13SocketConnector

import net.lag.configgy.Configgy

object Server {
  def main(args: Array[String]) {
    App // this reference initializes Configgy
    val server = new JettyServer()

    val conn = new Ajp13SocketConnector()
    conn.setPort(Configgy.config.getInt("jetty.ajp.port", 9000))
    server.addConnector(conn)

    val marker = getClass.getResource("/webroot/robots.txt").toString
    val webroot = marker.substring(0, marker.lastIndexOf("/"))
    val resource_handler = new ResourceHandler
    resource_handler.setBaseResource(Resource.newResource(webroot))
    server.addHandler(resource_handler)

    val context = new Context(server,"/")
    val holder = new ServletHolder(classOf[slinky.http.servlet.StreamStreamServlet])
    holder.setInitParameter("application", "sling.App")
    context.addServlet(holder, "/*")
    server.addHandler(context)

    server.setStopAtShutdown(true)
    server.start()

    // enter wait loop if not in main thread, e.g. running inside sbt
    Thread.currentThread.getName match {
      case "main" => server.join()
      case _ => 
        println("Embedded server running. Press any key to stop.")
        def doWait() {
          try { Thread.sleep(1000) } catch { case _: InterruptedException => () }
          if(System.in.available() <= 0)
            doWait()
        }
        doWait()
        server.stop()
    }
  }
}

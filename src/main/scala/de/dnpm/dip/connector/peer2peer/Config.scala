package de.dnpm.dip.connector.peer2peer



import java.io.{
  FileInputStream,
  InputStream
}
import java.net.{
  URI,
  URL
}
import scala.util.{
  Try,
  Failure,
  Using
}
import scala.xml._
import play.api.libs.json.{
  Json,
  JsObject,
  JsValue
}
import de.dnpm.dip.util.Logging
import de.dnpm.dip.coding.Coding
import de.dnpm.dip.model.Site


trait Config
{ 
  def localSite: Coding[Site]
  def peers: Map[Coding[Site],String]
  def timeout: Option[Int]
}


object Config extends Logging
{
  
  private case class Impl
  (
    localSite: Coding[Site],
    peers: Map[Coding[Site],String],
    timeout: Option[Int]
  )
  extends Config

/*
  XML Config structure:

  <?xml version="1.0" encoding="UTF-8"?>
  <ConnectorConfig>

    <Site id="..." name="..."/>
    <Peer id ="MH"  name="Musterlingen"   baseUrl="http://localhost:80/Musterlingen"/>
    <Peer id ="BSP" name="Beispielhausen" baseUrl="http://localhost:80/Beispielhausen"/>
    
    <!-- OPTIONAL request timeout (in seconds) -->
    <Timeout seconds="10"/>
    
  </ConnectorConfig>
*/


  private def parseXMLConfig(in: InputStream): Impl = {

    def toBaseUrl(s: String) =
      if (s.endsWith("/")) s.substring(0,s.length - 1)
      else s
  

    val xml =
      XML.load(in)

    val localSite =
      Coding[Site](
        code = (xml \ "Site" \@ "id"),
        display = (xml \ "Site" \@ "name")
      )

    val peers =
      (xml \ "Peer").map {
        peer =>
          val site =
            Coding[Site](
              code = (peer \@ "id"),
              display = (peer \@ "name")
            )

          val baseUrl =
            toBaseUrl(peer \@ "baseUrl")

        site -> baseUrl  
      }
      .toMap

    Impl(
      localSite,
      peers,
      Try(xml \ "Timeout" \@ "seconds")
        .map(_.toInt)
        .toOption
    )
  }


  lazy val getInstance: Config = {

    // Try reading config from classpath by default
    Try {
      val file = "p2pConnectorConfig.xml"

      log.debug(s"Loading connector config file '$file' from classpath...")

      Option(getClass.getClassLoader.getResourceAsStream(file)).get
    }
    // else use system property for configFile path
    .recoverWith {
      case t =>
        val sysProp = "dnpm.dip.peer2peer.connector.configFile"

        log.debug(s"Couldn't get config file from classpath, trying file configured via system property '$sysProp'")

        Try { Option(System.getProperty(sysProp)).get }
          .map(new FileInputStream(_))
    }
    .flatMap(Using(_)(parseXMLConfig))
    .get

  }

}

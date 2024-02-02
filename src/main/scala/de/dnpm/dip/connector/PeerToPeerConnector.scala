package de.dnpm.dip.connector


import java.net.URL
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
import scala.concurrent.{
  ExecutionContext,
  Future
}
import scala.concurrent.duration._
import play.api.libs.ws.{
  StandaloneWSClient,
  StandaloneWSRequest => WSRequest,
  StandaloneWSResponse => WSResponse
}
import de.dnpm.dip.util.Logging
import play.api.libs.json.JsValue
import de.dnpm.dip.coding.Coding
import de.dnpm.dip.model.Site
import de.dnpm.dip.service.query.{
  Connector,
  PeerToPeerRequest,
  PeerToPeerQuery,
  PatientRecordRequest
}


private object PeerToPeerConnector
{

  case class Config
  (
    localSite: Coding[Site],
    peers: Map[Coding[Site],String],
    timeout: Option[Int]
  )

  private object Config extends Logging
  {

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

    private def parseXMLConfig(in: InputStream): Config = {
    
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
    
      Config(
        localSite,
        peers,
        Try(xml \ "Timeout" \@ "seconds")
          .map(_.toInt)
          .toOption
      )
    }

    lazy val instance: Config = {

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

  def apply(
    baseUri: String,
    requestMapper: HttpConnector.RequestMapper,
    wsclient: StandaloneWSClient
  ): PeerToPeerConnector =
    new PeerToPeerConnector(
      baseUri,
      requestMapper,
      wsclient,
      Config.instance
    )

}


private class PeerToPeerConnector private (
  private val baseUri: String,
  private val requestMapper: HttpConnector.RequestMapper,
  private val wsclient: StandaloneWSClient,
  private val config: PeerToPeerConnector.Config
)
extends HttpConnector(baseUri,requestMapper,wsclient)
{

  private val timeout =
   config.timeout.getOrElse(10) seconds


  override val localSite: Coding[Site] =
    config.localSite
    
  override val otherSites: List[Coding[Site]] =
    config.peers.keys.toList

  override def request(
    site: Coding[Site],
    rawUri: String,
  ): WSRequest = {

    import scala.util.chaining._

    val uri =
      if (rawUri startsWith "/") rawUri.substring(1)
      else rawUri

    wsclient.url(s"${config.peers(site)}/$uri")
      .withRequestTimeout(timeout)

  }

}

package de.dnpm.dip.connector.broker



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


trait LocalConfig
{  
  def siteId: String
  def siteName: String
  def baseURL: URL
  def timeout: Option[Int]
  def updatePeriod: Option[Long]
}


object LocalConfig extends Logging
{
  
  private case class Impl
  (
    siteId: String,
    siteName: String,
    url: String,
    timeout: Option[Int],
    updatePeriod: Option[Long]
  )
  extends LocalConfig
  {
    override val baseURL =
      new URL(
        if (url endsWith "/")
          url.substring(0,url.length-1)
        else
          s"$url"
      )

  }

/*
  XML Config structure:

  <?xml version="1.0" encoding="UTF-8"?>
    <ConnectorConfig>

      <!--
      Local Site ID as also defined in central config,
      and also site name as fallback when central config not available
      -->
      <Site id="..." name="..."/>
      
      <!-- Base URL to DNPM-Proxy -->
      <Broker baseURL="http://localhost"/>
      
      <Timeout seconds="10"/>
      
      <!-- OPTIONAL, for periodic auto-update of site list from broker: Period (in seconds) -->
      <!--
      <UpdatePeriod minutes="30"/>
      -->

    </ConnectorConfig>
*/

  private def parseXMLConfig(in: InputStream): Impl = {

    val xml = XML.load(in)

    Impl(
      (xml \ "Site" \@ "id"),
      (xml \ "Site" \@ "name"),
      (xml \ "Broker" \@ "baseURL"),
      Try(xml \ "Timeout" \@ "seconds").map(_.toInt).toOption,
      Try(xml \ "UpdatePeriod" \@ "minutes").map(_.toLong).toOption
    )
  }


  lazy val getInstance: LocalConfig = {

    // Try reading config from classpath by default
    Try {
      val file = "brokerConnectorConfig.xml"

      log.debug(s"Loading connector config file '$file' from classpath...")

      Option(getClass.getClassLoader.getResourceAsStream(file)).get
    }
    // else use system property for configFile path
    .recoverWith {
      case t =>
        val sysProp = "dnpm.dip.broker.connector.configFile"

        log.debug(s"Couldn't get config file from classpath, trying file configured via system property '$sysProp'")

        Try { Option(System.getProperty(sysProp)).get }
          .map(new FileInputStream(_))
    }
    .flatMap(Using(_)(parseXMLConfig))
    // else use system properties for siteId and baseUrl to instantiate Config
    .recoverWith {
      case t => 
        Try {
          for {
            siteId    <- Option(System.getProperty("dnpm.dip.broker.connector.config.siteId"))
            siteName  <- Option(System.getProperty("dnpm.dip.broker.connector.config.siteName"))
            baseUrl   <- Option(System.getProperty("dnpm.dip.broker.connector.config.baseUrl"))
            timeout   =  Option(System.getProperty("dnpm.dip.broker.connector.config.timeout.seconds")).map(_.toInt)
            period    =  Option(System.getProperty("dnpm.dip.broker.connector.config.update.period")).map(_.toLong)
          } yield Impl(
            siteId,
            siteName,
            baseUrl,
            timeout,
            period
          )
        }
        .map(_.get)
    }
    .get

  }

}



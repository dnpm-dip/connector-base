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
  Success,
  Failure,
  Using
}
import scala.xml.XML
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
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.ws.DefaultBodyReadables._
import play.api.libs.json.{
  Json,
  JsValue,
  Reads
}
import de.dnpm.dip.util.Logging
import de.dnpm.dip.coding.Coding
import de.dnpm.dip.model.Site
import de.dnpm.dip.service.query.PeerToPeerRequest
import cats.Monad


private object BrokerConnector
{

  final case class SiteEntry
  (
    id: String,
    name: String,
    virtualhost: String
  )

  final case class SiteConfig
  (
    sites: Set[SiteEntry]
  )

  object SiteConfig
  {
    implicit val formatEntry: Reads[SiteEntry] =
      Json.reads[SiteEntry]

    implicit val format: Reads[SiteConfig] =
      Json.reads[SiteConfig]
  }

  case class LocalConfig
  (
    siteId: String,
    siteName: String,
    private val url: String,
    timeout: Option[Int],
    updatePeriod: Option[Long]
  )
  {
    def baseURL =
      new URL(
        if (url endsWith "/")
          url.substring(0,url.length-1)
        else
          s"$url"
      )
  }

  private object LocalConfig extends Logging
  {

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
    private def parseXMLConfig(in: InputStream): LocalConfig = {
    
      val xml = XML.load(in)
    
      LocalConfig(
        (xml \ "Site" \@ "id"),
        (xml \ "Site" \@ "name"),
        (xml \ "Broker" \@ "baseURL"),
        Try(xml \ "Timeout" \@ "seconds").map(_.toInt).toOption,
        Try(xml \ "UpdatePeriod" \@ "minutes").map(_.toLong).toOption
      )
    }


    lazy val instance: LocalConfig =
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
            } yield LocalConfig(
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
    
  }  // end LocalConfig


  def apply(
    baseUri: String,
    requestMapper: HttpConnector.RequestMapper,
    wsclient: StandaloneWSClient
  ): BrokerConnector =
    new BrokerConnector(
      baseUri,
      requestMapper,
      wsclient,
      LocalConfig.instance
    )

}


private class BrokerConnector
(
  private val baseUri: String,
  private val requestMapper: HttpConnector.RequestMapper,
  private val wsclient: StandaloneWSClient,
  private val localConfig: BrokerConnector.LocalConfig
)
extends HttpConnector(
  baseUri,
  requestMapper,
  wsclient
)
{

  private val timeout =
    localConfig.timeout.getOrElse(10) seconds


  // Set-up for periodic auto-update of config

  import java.time.{Duration,LocalTime}
  import java.util.concurrent.Executors
  import java.util.concurrent.TimeUnit.MINUTES
  import java.util.concurrent.atomic.AtomicReference


  private def getSiteConfig: Unit = {

    import ExecutionContext.Implicits.global

    log.debug(s"Requesting peer connectivity config")

    request("/sites")
      .get()
      .map(_.body[JsValue].as[BrokerConnector.SiteConfig])
      .onComplete {
        case Success(config) =>
          sitesConfig.set(
            config.sites.map {
              case BrokerConnector.SiteEntry(id,name,vhost) => Coding[Site](id,name) -> vhost
            }
            .toMap
          )

        case Failure(t) =>
          log.error(s"Broker connection error: ${t.getMessage}")
      }

  }

  private val sitesConfig: AtomicReference[Map[Coding[Site],String]] =
    new AtomicReference(Map.empty)

  private val executor =
    Executors.newSingleThreadScheduledExecutor

  for { period <- localConfig.updatePeriod }{
    executor.scheduleAtFixedRate(
      () => getSiteConfig,
      period,
      period,
      MINUTES
    )
  }

  override def localSite: Coding[Site] =
    sitesConfig.get match {
      case map if (map.nonEmpty) =>
        map.collectFirst {
          case (site,_) if (site.code.value == localConfig.siteId) => site
        }
        .get

      case _ =>
        log.warn("Global site config from broker not available, falling back to locally defined localSite info")
        Coding[Site](
          localConfig.siteId,
          localConfig.siteName,
        )
    }


  override def otherSites: Set[Coding[Site]] =
    sitesConfig.get match {
      case map if (map.nonEmpty) =>
        map.collect {
          case (site,_) if (site.code.value != localConfig.siteId) => site
        }
        .toSet

      case _ =>
        log.warn("Global site config from broker not available, falling back to empty external site list")
        Set.empty[Coding[Site]]
    }


  private def request(
    rawUri: String
  ): WSRequest = {

    val uri =
      if (rawUri startsWith "/") rawUri.substring(1)
      else rawUri

    wsclient.url(s"${localConfig.baseURL}/$uri")
      .withRequestTimeout(timeout)
        
  }

  
  override def request(
    site: Coding[Site],
    rawUri: String
  ): WSRequest = 
    request(rawUri)
      .withVirtualHost(sitesConfig.get()(site))

}

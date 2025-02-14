package de.dnpm.dip.connector


import java.io.{
  FileInputStream,
  InputStream
}
import java.net.URI
import scala.util.{
  Try,
//  Failure,
  Success,
  Using
}
import scala.util.chaining._
import scala.xml.XML
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.stream.Materializer
import play.api.libs.ws.StandaloneWSRequest
import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.json.{
  Json,
  JsValue,
  Reads
}
import de.dnpm.dip.util.{
  Logging,
  Retry
}
import de.dnpm.dip.coding.Coding
import de.dnpm.dip.model.Site


private object BrokerConnector extends Logging
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
    private val url: String,
    timeout: Option[Int],
    updatePeriod: Option[Long]
  )
  extends HttpConnector.Config
  {
    def baseURL =
      URI.create(
        if (url endsWith "/")
          url.substring(0,url.length-1)
        else
          url
      )
      .toURL
  }

  private object LocalConfig extends Logging
  {

    /*
     * Expected XML Config structure:
     * 
     *  <?xml version="1.0" encoding="UTF-8"?>
     *  <Config>
     *    ...
     *    <Connector>
     *    
     *      <!-- Base URL to DNPM-Proxy -->
     *      <Broker baseURL="http://localhost"/>
     *      
     *      <!-- OPTIONAL request timeout (in seconds) -->
     *      <Timeout seconds="10"/>
     *      
     *      <!-- OPTIONAL, for periodic auto-update of site list from broker: Period (in seconds) -->
     *      <UpdatePeriod minutes="30"/>
     *    
     *    </Connector>
     *    ...
     *  </Config>
     */

    private def parseXMLConfig(in: InputStream): LocalConfig = {
    
      val xml =
        (XML.load(in) \\ "Connector")
    
      LocalConfig(
        (xml \ "Broker" \@ "baseURL"),
        Try(xml \ "Timeout" \@ "seconds").map(_.toInt).toOption,
        Try(xml \ "UpdatePeriod" \@ "minutes").map(_.toLong).toOption
      )
    }

    
    lazy val instance: LocalConfig = {

      val sysProp = "dnpm.dip.config.file"

      // Try reading config from classpath by default
      Try {
        val file = "config.xml"
    
        log.debug(s"Loading connector config file '$file' from classpath...")
    
        Option(getClass.getClassLoader.getResourceAsStream(file)).get
      }
      // else use system property for configFile path
      .recoverWith {
        case _ =>
          log.debug(s"Couldn't get config file from classpath, trying file configured via system property '$sysProp'")
    
          Try { Option(System.getProperty(sysProp)).get }
            .map(new FileInputStream(_))
      }
      .flatMap(Using(_)(parseXMLConfig))
      // else use system properties for siteId and baseUrl to instantiate Config
      .recoverWith {
        case _ => 
          log.warn(s"Couldn't get config file, most likely due to undefined property '$sysProp'. Attempting configuration via system properties...")
          Try {
            for {
              baseUrl   <- Option(System.getProperty("dnpm.dip.connector.config.baseUrl"))
              timeout   =  Option(System.getProperty("dnpm.dip.connector.config.timeout.seconds")).map(_.toInt)
              period    =  Option(System.getProperty("dnpm.dip.connector.config.update.period")).map(_.toLong)
            } yield LocalConfig(
              baseUrl,
              timeout,
              period
            )
          }
          .map(_.get)
      }
      .get
    }
    
  }  // end LocalConfig


  private implicit lazy val system: ActorSystem =
    ActorSystem()

  private implicit lazy val materializer: Materializer =
    Materializer.matFromSystem

  private lazy val wsclient =
    StandaloneAhcWSClient()

  private val timeout =
    LocalConfig.instance.timeout.getOrElse(10) seconds

  private def request(
    rawUri: String
  ): StandaloneWSRequest = {

    val uri =
      if (rawUri startsWith "/") rawUri.substring(1)
      else rawUri

    wsclient.url(s"${LocalConfig.instance.baseURL}/$uri")
      .withRequestTimeout(timeout)
        
  }


  // Set-up for periodic auto-update of config
  import java.util.concurrent.{
    Executors,
    ScheduledExecutorService
  }
  import java.util.concurrent.TimeUnit.SECONDS
  import java.util.concurrent.atomic.AtomicReference
  import ExecutionContext.Implicits.global


  private implicit lazy val executor: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor


  private val peerDiscoveryTask =
    Retry(
      () => {
        log.info(s"Requesting peer connectivity config from broker")
      
        request("/sites")
          .get()
          .map(_.body[JsValue].as[BrokerConnector.SiteConfig])
          .andThen {
            case Success(config) =>
              sitesConfig.set(
                config.sites.map {
                  case BrokerConnector.SiteEntry(id,name,vhost) => Coding[Site](id,name) -> vhost
                }
                .toMap
              )
          }
      },
      "Peer Discovery",
      5,
      15
    )

  private val sitesConfig: AtomicReference[Map[Coding[Site],String]] =
    new AtomicReference(Map.empty)


  LocalConfig.instance.updatePeriod match {
    case Some(period) =>
      executor.scheduleAtFixedRate(
        peerDiscoveryTask,
        0,
        period*60,
        SECONDS
      )
    case None =>
      peerDiscoveryTask.run
  }



  def apply(
    requestMapper: HttpConnector.RequestMapper,
  ): BrokerConnector =
    new BrokerConnector(
      requestMapper
    )

}


private class BrokerConnector
(
  private val requestMapper: HttpConnector.RequestMapper
)
extends HttpConnector(requestMapper){

  override def otherSites: Set[Coding[Site]] =
    BrokerConnector.sitesConfig.get
      .collect { case (site,_) if site != Site.local => site }
      .toSet
      .tap {
        set => 
          if (set.isEmpty)
            log.warn("Global site config from broker not available, falling back to empty external site list")
      }

  override def request(
    site: Coding[Site],
    uri: String
  ): StandaloneWSRequest =
    BrokerConnector.sitesConfig.get.get(site) match {

      case Some(vhost) =>
        BrokerConnector.request(uri).withVirtualHost(vhost)

      case _ =>
        log.warn(
          s"Virtual hostname of site '${site.code.value}' not available. Check broker availability for peer discovery."
        )
        BrokerConnector.request(uri)
    }
    

}

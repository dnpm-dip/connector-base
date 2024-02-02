package de.dnpm.dip.connector.broker


import java.net.URL
import scala.util.{
  Success,
  Failure
}
import scala.concurrent.{
  ExecutionContext,
  Future
}
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.stream.Materializer
import play.api.libs.ws.{
  StandaloneWSClient,
  StandaloneWSRequest => WSRequest,
  StandaloneWSResponse => WSResponse
}
import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.ws.JsonBodyWritables._
import play.api.libs.ws.DefaultBodyWritables._
import play.api.libs.ws.DefaultBodyReadables._
import play.api.libs.json.{
  Json,
  JsValue,
  Reads,
  Writes
}
import de.dnpm.dip.util.Logging
import de.dnpm.dip.coding.Coding
import de.dnpm.dip.model.Site
import de.dnpm.dip.service.query.{
  Connector,
  PeerToPeerRequest,
  PeerToPeerQuery,
  PatientRecordRequest
}
import de.dnpm.dip.connector.HttpMethod._
import cats.Monad


object BrokerConnector
{

  implicit lazy val system: ActorSystem =
    ActorSystem()

  implicit lazy val materializer: Materializer =
    Materializer.matFromSystem

  private lazy val wsclient =
    StandaloneAhcWSClient()

  private lazy val config =
    LocalConfig.getInstance


  private val defaultUriMethods: PartialFunction[PeerToPeerRequest,(HttpMethod,String)] =
  {
    case req: PeerToPeerQuery[_,_]    => POST -> "query"
    case req: PatientRecordRequest[_] => POST -> "patient-record"
  }

  def apply(
    apiBaseUri: String,
    uriMethods: PartialFunction[PeerToPeerRequest,(HttpMethod,String)]
  ): BrokerConnector =
    new BrokerConnector(
      apiBaseUri,
      uriMethods orElse defaultUriMethods,
      wsclient,
      config
    )

  def apply(
    apiBaseUri: String
  ): BrokerConnector =
    BrokerConnector(
      apiBaseUri,
      PartialFunction.empty
    )

}


class BrokerConnector private (
  private val apiBaseUri: String,
  private val uriMethods: PartialFunction[PeerToPeerRequest,(HttpMethod,String)],
  private val wsclient: StandaloneWSClient,
  private val localConfig: LocalConfig
)
extends Connector[
  Future,
  Monad[Future]
]
with Logging
{

  private val timeout =
    localConfig.timeout.getOrElse(10) seconds


  // Set-up for periodic auto-update of config

  import java.time.{Duration,LocalTime}
  import java.util.concurrent.Executors
  import java.util.concurrent.TimeUnit.MINUTES
  import java.util.concurrent.atomic.AtomicReference


  import ExecutionContext.Implicits.global


  private def getBrokerConfig: Unit = {


    log.debug(s"Requesting peer connectivity config")

    request("/sites")
      .withRequestTimeout(timeout)
      .get()
      .map(_.body[JsValue].as[BrokerConfig])
      .onComplete {
        case Success(config) =>
          sitesConfig.set(
            config.sites.map {
              case SiteEntry(id,name,vhost) => Coding[Site](id,name) -> vhost
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
      () => getBrokerConfig,
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


  override def otherSites: List[Coding[Site]] =
    sitesConfig.get match {
      case map if (map.nonEmpty) =>
        map.collect {
          case (site,_) if (site.code.value != localConfig.siteId) => site
        }
        .toList

      case _ =>
        log.warn("Global site config from broker not available, falling back to empty external site list")
        List.empty[Coding[Site]]
    }


  
  private def request(
    rawUri: String,
    virtualHost: Option[String] = None
  ): WSRequest = {

    import scala.util.chaining._

    val uri =
      if (rawUri startsWith "/") rawUri.substring(1)
      else rawUri

    val req =
      wsclient.url(s"${localConfig.baseURL}/$uri")
        .withRequestTimeout(timeout)

    virtualHost match {
      case Some(host) => req.withVirtualHost(host)
      case _          => req
    }
    
  }


  private def scatter(
    uri: String,
    sites: List[Coding[Site]]
  ): List[(Coding[Site],WSRequest)] =
    for {
      site <- sites
      vhost = sitesConfig.get.get(site)
    } yield site -> request(uri,vhost)


  private def gather[T](
    responses: List[Future[(Coding[Site],T)]],
  ): Future[Map[Coding[Site],T]] =
    Future.foldLeft(
      responses
    )(
      Map.empty[Coding[Site],T]
    )(
      _ + _
    )



  private def scatterGather[T](
    uri: String,
    sites: List[Coding[Site]],
    trf: (Coding[Site],WSRequest) => Future[(Coding[Site],T)]
  ): Future[Map[Coding[Site],T]] = {

    import scala.util.chaining._

    scatter(uri,sites) pipe (_.map(trf.tupled)) pipe (gather(_))

  }


  override def submit[T <: PeerToPeerRequest: Writes](
    req: T,
    sites: List[Coding[Site]] = this.otherSites
  )(
    implicit 
    env: Monad[Future],
    fr: Reads[req.ResultType] 
  ): Future[Map[Coding[Site],Either[String,req.ResultType]]] = {

    import cats.syntax.either._

    val (method,uri) = uriMethods(req)

    scatterGather(
      apiBaseUri + uri,
      sites,
      {
        case (site,request) =>
          request
            .withBody(Json.toJson(req))
            .execute(method.toString)
            .map(_.body[JsValue].as[req.ResultType])
            .map(_.asRight[String])
            .recover {
              case t => 
                s"Error in peer-to-peer response from site ${site.display.get}: ${t.getMessage}".asLeft[req.ResultType]
            }
            .map(site -> _)
      }        
    )

  }

}


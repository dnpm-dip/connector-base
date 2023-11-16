package de.dnpm.dip.connector.peer2peer


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


object PeerToPeerConnector
{

  implicit lazy val system: ActorSystem =
    ActorSystem()

  implicit lazy val materializer: Materializer =
    Materializer.matFromSystem

  private lazy val wsclient =
    StandaloneAhcWSClient()

  private lazy val config =
    Config.getInstance


  private val defaultUriMethods: PartialFunction[PeerToPeerRequest,(HttpMethod,String)] =
  {
    case req: PeerToPeerQuery[_,_]    => POST -> "query"
    case req: PatientRecordRequest[_] => POST -> "patient-record"
  }

  def apply(
    apiBaseUri: String,
    uriMethods: PartialFunction[PeerToPeerRequest,(HttpMethod,String)]
  ): PeerToPeerConnector =
    new PeerToPeerConnector(
      apiBaseUri,
      uriMethods orElse defaultUriMethods,
      wsclient,
      config
    )

  def apply(
    apiBaseUri: String
  ): PeerToPeerConnector =
    PeerToPeerConnector(
      apiBaseUri,
      PartialFunction.empty
    )

}


class PeerToPeerConnector private (
  private val apiBaseUri: String,
  private val uriMethods: PartialFunction[PeerToPeerRequest,(HttpMethod,String)],
  private val wsclient: StandaloneWSClient,
  private val config: Config
)
extends Connector[
  Future,
  Monad[Future]
]
with Logging
{

  private val timeout =
   config.timeout.getOrElse(10) seconds


  override def localSite: Coding[Site] =
    config.localSite
    
  override def otherSites: List[Coding[Site]] =
    config.peers.keys.toList

  import scala.concurrent.ExecutionContext.Implicits.global

  
  private def request(
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


  private def scatter(
    uri: String,
    sites: List[Coding[Site]]
  ): List[(Coding[Site],WSRequest)] =
    for {
      site <- sites
    } yield site -> request(site,uri)


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


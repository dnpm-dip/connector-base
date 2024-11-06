package de.dnpm.dip.connector


import scala.concurrent.Future
import cats.Monad
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
import de.dnpm.dip.service.{
  Connector,
  PeerToPeerRequest
}
import de.dnpm.dip.service.query.{
  PeerToPeerQuery,
  PatientRecordRequest
}
import HttpMethod._


abstract class HttpConnector
(
  _baseUri: String,
  private val requestMapper: HttpConnector.RequestMapper,
  private val wsclient: StandaloneWSClient
)
extends Connector[Future,Monad[Future]]
with Logging
{
  self =>

  import scala.util.chaining._

  import scala.concurrent.ExecutionContext.Implicits.global


  private val baseUri =
    _baseUri match {
      case uri if !uri.endsWith("/") => s"$uri/"
      case uri => uri 
    }


  protected def request(
    site: Coding[Site],
    rawUri: String
  ): WSRequest


  private def scatterGather[T](
    uri: String,
    sites: Set[Coding[Site]],
    f: (Coding[Site],WSRequest) => Future[(Coding[Site],T)]
  ): Future[Map[Coding[Site],T]] =
    Future.foldLeft(
      for {
        site <- sites
        req  =  request(site,uri)
        resp =  f(site,req)
      } yield resp
    )(
      Map.empty[Coding[Site],T]
    )(
      _ + _
    )


  override def submit[T <: PeerToPeerRequest: Writes](
    req: T,
    sites: Set[Coding[Site]] = this.otherSites
  )(
    implicit
    env: Monad[Future],
    fr: Reads[req.ResultType]
  ): Future[Map[Coding[Site],Either[String,req.ResultType]]] = {

    import cats.syntax.either._

    val (method,uri,queryParams) =
      requestMapper(req)


    scatterGather(
      s"$baseUri$uri".replace("//","/"),
      sites,
      {
        case (site,request) =>
          request
            .pipe(
              r => method match { 
                case POST | PUT =>
                  r.withBody(Json.toJson(req))
                case _          =>
                  r.addQueryStringParameters(
                    queryParams
                      .map { case (name,values) => values.map(name -> _) }
                      .flatten
                      .toSeq: _*
                  )
                  .withQueryStringParameters(
                    "origin"  -> req.origin.code.value
                  )
              }
            )
            .execute(method.toString)
            .map(_.body[JsValue].as[req.ResultType])
            .map(_.asRight[String])
            .recover {
              case t =>
                s"Error in peer-to-peer response from site ${site.display.get}: ${t.getMessage}"
                  .tap(log.error)
                  .asLeft[req.ResultType]
            }
            .map(site -> _)
      }
    )

  }

}


object HttpConnector
{

  type RequestMapper =
    PartialFunction[
      PeerToPeerRequest,
      (HttpMethod.Value,String,Map[String,Seq[String]])
    ] 


  object Type extends Enumeration
  {
    val PeerToPeer = Value("peer2peer")
    val Broker     = Value("broker")
    
    val property = "dnpm.dip.connector.type"
    
    def unapply(s: String): Option[Value] =
      values.find(_.toString.toLowerCase == s.toLowerCase)
  }

  trait Config
  {
    def timeout: Option[Int]
  }


  private implicit lazy val system: ActorSystem =
    ActorSystem()

  private implicit lazy val materializer: Materializer =
    Materializer.matFromSystem

  private lazy val wsclient =
    StandaloneAhcWSClient()


  import Type._

  val baseRequestMapper: RequestMapper = { 

    case req: PeerToPeerQuery[_,_]    => (POST, "query", Map.empty)

    case req: PatientRecordRequest[_] => (GET, "patient-record", Map.empty)

  }


  def apply(
    typ: Type.Value,
    baseUri: String,
    requestMapper: HttpConnector.RequestMapper = PartialFunction.empty
  ): HttpConnector =
    typ match {

      case PeerToPeer => 
        PeerToPeerConnector(
          baseUri,
          baseRequestMapper.orElse(requestMapper),
          wsclient,
        )

      case Broker => 
        BrokerConnector(
          baseUri,
          baseRequestMapper.orElse(requestMapper),
          wsclient
        )

    }

}

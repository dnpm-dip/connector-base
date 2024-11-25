package de.dnpm.dip.connector


import scala.concurrent.Future
import cats.Monad
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
import de.dnpm.dip.model.{
  ClosedInterval,
  Site
}
import de.dnpm.dip.service.{
  Connector,
  PeerToPeerRequest
}
import HttpMethod._


abstract class HttpConnector
(
  private val requestMapper: HttpConnector.RequestMapper
)
extends Connector[Future,Monad[Future]]
with Logging
{
  self =>

  import scala.util.chaining._
  import scala.concurrent.ExecutionContext.Implicits.global
  import cats.syntax.either._
  import de.dnpm.dip.model.Interval.IntervalOps  // for "t isIn Interval" syntax


  protected def request(
    site: Coding[Site],
    uri: String
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



//  private val successCodes = ClosedInterval(200 -> 299)
  private val successCodes = 200 to 299


  override def submit[T <: PeerToPeerRequest: Writes](
    req: T,
    sites: Set[Coding[Site]] = this.otherSites
  )(
    implicit
    env: Monad[Future],
    fr: Reads[req.ResultType]
  ): Future[Map[Coding[Site],Either[String,req.ResultType]]] = {

    import cats.syntax.either._

    val (method,uri,queryParams) = requestMapper(req)

    scatterGather(
      uri,
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
                    "origin" -> req.origin.code.value
                  )
              }
            )
            .execute(method.toString)
            .map( 
              resp =>
                if (successCodes contains resp.status){
                  resp.body[JsValue]
                    .validate[req.ResultType]
                    .asEither
                    .leftMap(
                      errs =>
                        "Invalid JSON response payload"
                          .tap(msg => log error s"$msg:\n${errs.mkString("\n")}")
                    )
                } else {
                  s"${resp.status} ${resp.statusText}"
                    .tap(msg => log error s"In peer-to-peer response from site ${site.display.get}: $msg")
                    .asLeft
                }
            )
            .recover {
              case t =>
                s"In peer-to-peer request to site ${site.display.get}: ${t.getMessage}"
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


  // Convert a Request into triple of HTTP Method, URI and Query Parameters
  type RequestMapper =
    PartialFunction[
      PeerToPeerRequest,
      (HttpMethod.Value,String,Map[String,Seq[String]])
    ] 


  def apply(
    typ: Type.Value,
    requestMapper: HttpConnector.RequestMapper
  ): HttpConnector =
    typ match {

      case Type.Broker     => BrokerConnector(requestMapper)

      case Type.PeerToPeer => PeerToPeerConnector(requestMapper)

    }

}

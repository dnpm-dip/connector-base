package de.dnpm.dip.connector


import scala.concurrent.Future
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.must.Matchers._
import de.dnpm.dip.coding.Coding
import de.dnpm.dip.model.Site
import de.dnpm.dip.service.PeerToPeerRequest
import play.api.libs.json.{
  Json,
  JsObject,
  Writes
}
import HttpMethod._



final case class TestRequest(
  origin: Coding[Site],
)
extends PeerToPeerRequest
{
  type ResultType = JsObject
}

object TestRequest
{
  implicit val writes: Writes[TestRequest] =
    Json.writes[TestRequest]
}



class Tests extends AsyncFlatSpec
{

  private val brokerConnector =
    HttpConnector(
      HttpConnector.Type.Broker,
      "/api/peer-to-peer/dummy-use-case",
      {
        case _: TestRequest => (POST,"test",Map.empty)
      }
    )

  private val p2pConnector =
    HttpConnector(
      HttpConnector.Type.PeerToPeer,
      "/api/peer-to-peer/dummy-use-case",
      {
        case _: TestRequest => (POST,"test",Map.empty)
      }
    )


  "BrokerConnector" must "return empty external site list" in {
    brokerConnector.otherSites must be (empty)
  }


  it must "have returned empty Map" in {

    val result = 
      brokerConnector ! TestRequest(brokerConnector.localSite)

    result.map(_ must be (empty))

  }

  "PeerToPeerConnector" must "return empty external site list" in {
    p2pConnector.otherSites must be (empty)
  }


  it must "have returned empty Map" in {

    val result = 
      p2pConnector ! TestRequest(p2pConnector.localSite)

    result.map(_ must be (empty))

  }

}

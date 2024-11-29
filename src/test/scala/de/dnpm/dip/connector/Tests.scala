package de.dnpm.dip.connector


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

  System.setProperty(Site.property,"UKx:Musterlingen")


  private val brokerConnector =
    HttpConnector(
      HttpConnector.Type.Broker,
      {
        case _: TestRequest => (POST,"/api/peer-to-peer/dummy-use-case/test",Map.empty)
      }
    )

  private val p2pConnector =
    HttpConnector(
      HttpConnector.Type.PeerToPeer,
      {
        case _: TestRequest => (POST,"/api/peer-to-peer/dummy-use-case/test",Map.empty)
      }
    )


  "BrokerConnector" must "return empty external site list" in {
    brokerConnector.otherSites must be (empty)
  }


  it must "have returned empty Map" in {

    val result = 
      brokerConnector ! TestRequest(Site.local)

    result.map(_ must be (empty))

  }

  "PeerToPeerConnector" must "return empty external site list" in {
    p2pConnector.otherSites must be (empty)
  }


  it must "have returned empty Map" in {

    val result = 
      p2pConnector ! TestRequest(Site.local)

    result.map(_ must be (empty))

  }

}

package de.dnpm.dip.connector.peer2peer


import scala.concurrent.Future
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.must.Matchers._
import org.scalatest.OptionValues._
import de.dnpm.dip.coding.Coding
import de.dnpm.dip.model.Site
import de.dnpm.dip.service.query.{
  Querier,
  PeerToPeerRequest
}
import play.api.libs.json.{
  Json,
  JsObject,
  Writes
}
import de.dnpm.dip.connector.HttpMethod._



final case class TestRequest(
  origin: Coding[Site],
  querier: Querier
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

  private val connector =
    PeerToPeerConnector(
      "/api/peer-to-peer/dummy-use-case",
      {
        case _: TestRequest => POST -> "test" 
      }
    )


  "OtherSites list" must "be empty" in {

    connector.otherSites.headOption must be (defined)

  }


  "TestQuery" must "have returned empty Map" in {

    val result = 
      connector ! TestRequest(
        connector.localSite,
        Querier("Dummy-ID")
      )

    result.map(
      _.headOption.value._2.isLeft mustBe true
    )

  }

}

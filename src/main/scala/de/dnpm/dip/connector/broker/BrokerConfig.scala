package de.dnpm.dip.connector.broker


import java.net.URI
import play.api.libs.json.{
  Json,
  Format
}
import de.dnpm.dip.coding.Coding
import de.dnpm.dip.model.Site



final case class SiteEntry
(
  id: String,
  name: String,
  virtualhost: String
)


final case class BrokerConfig
(
  sites: List[SiteEntry]
)

object BrokerConfig
{

  implicit val formatEntry: Format[SiteEntry] =
    Json.format[SiteEntry]

  implicit val format: Format[BrokerConfig] =
    Json.format[BrokerConfig]

}


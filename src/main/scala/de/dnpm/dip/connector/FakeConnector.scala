package de.dnpm.dip.connector


import java.net.URL
import scala.util.{
  Success,
  Failure
}
import scala.concurrent.{
  ExecutionContext,
  Future
}
import play.api.libs.json.{
  Reads,
  Writes
}
import de.dnpm.dip.util.Logging
import de.dnpm.dip.coding.Coding
import de.dnpm.dip.model.Site
import de.dnpm.dip.service.{
  Connector,
  PeerToPeerRequest,
}
import cats.Monad


object FakeConnector
{

  def apply[F[_]: Monad]: Connector[F,Monad[F]] =
    new FakeConnector[F]

}

private class FakeConnector[F[_]] extends Connector[F,Monad[F]]
{

  override def localSite: Coding[Site] =
    Coding[Site](
      "UKx",
      "Musterhausen"
    )

  override def otherSites: Set[Coding[Site]] =
    Set.empty

  
  override def submit[T <: PeerToPeerRequest: Writes](
    req: T,
    sites: Set[Coding[Site]] = this.otherSites
  )(
    implicit 
    env: Monad[F],
    fr: Reads[req.ResultType] 
  ): F[Map[Coding[Site],Either[String,req.ResultType]]] =
    env.pure(
      Map.empty
    )

}

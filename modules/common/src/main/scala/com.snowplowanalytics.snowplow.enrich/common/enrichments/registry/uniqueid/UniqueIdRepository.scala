package com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.uniqueid

final case class UniqueIdException(private val message: String = "",
                                 private val cause: Throwable = None.orNull)
  extends Exception(message, cause)

final case class UniqueIdResult(uniqueId: String, isNewUser: Boolean)

trait UniqueIdRepository {
  def getUniqueId (appId: String, installationId: Option[String], userId: Option[String]): UniqueIdResult
}

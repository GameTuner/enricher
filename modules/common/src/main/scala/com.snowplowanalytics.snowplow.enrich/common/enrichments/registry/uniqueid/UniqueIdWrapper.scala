package com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.uniqueid

import cats.effect.Sync

import java.time.Duration
import cats.implicits._

sealed trait UniqueIdWrapper[F[_]] {
  def setUniqueID(app_id: String, installation_id: Option[String], user_id: Option[String]): F[Either[String, UniqueIdResult]]
}

class UniqueIdSyncWrapper[F[_]: Sync](username: String, password: String, database: String, connectionName: String,
  maximumElementsInCache: Long,
  expireCacheAfterMinutes: Long
) extends UniqueIdWrapper[F] {
  var cachedRepository = new UniqueIdCachedRepository(
    new UniqueIdCloudSqlRepository(username, password, database, connectionName),
    maximumElementsInCache,
    Duration.ofMinutes(expireCacheAfterMinutes))

  override def setUniqueID(app_id: String, installation_id: Option[String], user_id: Option[String]): F[Either[String, UniqueIdResult]] = {
    Sync[F].delay {
      Either.catchNonFatal {
        require((installation_id.isDefined || user_id.isDefined) && app_id != null && app_id.nonEmpty, "Must pass either installation_id or user_id or app_id!")
        cachedRepository.getUniqueId(app_id, installation_id, user_id)
      }.leftMap(_.getMessage)
    }
  }
}

sealed trait CreateUniqueIdWrapper[F[_]] {
  def create(
              username: String,
              password: String,
              database: String,
              connectionName: String,
              maximumElementsInCache: Long,
              expireCacheAfterMinutes: Long
            ): F[UniqueIdWrapper[F]]
}

object CreateUniqueIdWrapper {
  def apply[F[_]](implicit ev: CreateUniqueIdWrapper[F]): CreateUniqueIdWrapper[F] = ev

  implicit def syncCreateUniqueIdWrapper[F[_]: Sync]: CreateUniqueIdWrapper[F] = new CreateUniqueIdWrapper[F] {
    override def create(username: String, password: String, database: String,
                        connectionName: String, maximumElementsInCache: Long, expireCacheAfterMinutes: Long): F[UniqueIdWrapper[F]] =
      Sync[F].delay {new UniqueIdSyncWrapper[F](username = username, password = password, database = database, connectionName = connectionName, maximumElementsInCache = maximumElementsInCache, expireCacheAfterMinutes = expireCacheAfterMinutes)}
  }
}
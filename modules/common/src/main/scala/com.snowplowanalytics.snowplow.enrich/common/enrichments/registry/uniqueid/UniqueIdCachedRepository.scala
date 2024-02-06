package com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.uniqueid

import com.google.common.cache.CacheBuilder
import scalacache.Entry
import scalacache.guava.GuavaCache
import scalacache.modes.sync._

import java.time.Duration

class UniqueIdCachedRepository(
  underlyingRepository: UniqueIdRepository,
  maximumElementsInCache: Long,
  expireAfterAccess: Duration
) extends UniqueIdRepository {

  val cache: GuavaCache[UniqueIdResult] = GuavaCache(
    CacheBuilder.newBuilder()
      .maximumSize(maximumElementsInCache)
      .expireAfterAccess(expireAfterAccess)
      .build[String, Entry[UniqueIdResult]])

  private def getCacheKey(implicit appId: String, installationId: Option[String], userId: Option[String]): String = {
    s"appId:#${appId}#iid:${installationId.getOrElse("")}#uid:${userId.getOrElse("")}"
  }

  override def getUniqueId(appId: String, installationId: Option[String], userId: Option[String]): UniqueIdResult = {
    val cacheKey = getCacheKey(appId, installationId, userId)
    val cacheResult = cache.get(cacheKey)

    cacheResult.orElse {
      val calculated = underlyingRepository.getUniqueId(appId, installationId, userId)
      cache.put(cacheKey)(UniqueIdResult(calculated.uniqueId, false))
      Some(calculated)
    }.get
  }
}
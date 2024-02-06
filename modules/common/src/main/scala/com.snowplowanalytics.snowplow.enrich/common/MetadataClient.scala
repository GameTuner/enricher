package com.snowplowanalytics.snowplow.enrich.common

import io.circe.parser
import org.slf4j.LoggerFactory

import java.net.URI
import java.util.concurrent.{Executors, TimeUnit}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import io.circe.generic.auto._

object MetadataClient {

  case class JsonApp(app_id: String, api_key: String, timezone: String, close_event_partitions_after_hours: Int)

  case class AppIdData(apiKey: String, timezone: String, closePartitionAfterHours: Int)

  private val logger = LoggerFactory.getLogger("AppIdDataManager")
  private val executor = Executors.newSingleThreadScheduledExecutor()
  val client: HttpClient = HttpClient.newHttpClient
  @volatile private var data = Map[String, AppIdData]()

  def getAppIdData(appId: String): Option[AppIdData] = {
    this.data.get(appId)
  }

  def startDataUpdate(appIdConfigsEndpoint: String, cacheRefreshIntervalSeconds: Int): Unit = {
    syncCache(appIdConfigsEndpoint, true)
    executor.scheduleAtFixedRate(() => syncCache(appIdConfigsEndpoint, false),
      cacheRefreshIntervalSeconds.longValue(), cacheRefreshIntervalSeconds.longValue(), TimeUnit.SECONDS)
    ()
  }

  def syncCache(appIdConfigsEndpoint: String, propagateExceptions: Boolean): Unit = {
    try {
      val request = HttpRequest.newBuilder()
        .uri(URI.create(appIdConfigsEndpoint))
        .version(HttpClient.Version.HTTP_1_1)
        .GET()
        .build()

      val response = client.send(request, HttpResponse.BodyHandlers.ofString())

      if (response.statusCode() == 200) {
        val body = response.body()

        val jsonApps = parser.parse(body).right.get.as[List[JsonApp]].right.get
        val tempData = scala.collection.mutable.Map[String, AppIdData]()
        jsonApps.foreach(jsonApp => {
          tempData(jsonApp.app_id) = AppIdData(
            jsonApp.api_key,
            jsonApp.timezone,
            jsonApp.close_event_partitions_after_hours
          )
        })
        this.data = tempData.toMap
        logger.info(s"Updated api key cache with ${this.data.size} keys")
      } else {
        throw new IllegalStateException("Received status code " + response.statusCode() + " from metadata service")
      }
    } catch {
      case e: Exception =>
        if (propagateExceptions) {
          throw new IllegalStateException("Failed to sync api key cache during startup", e)
        } else {
          logger.error("Failed to sync api key cache", e)
        }
    }
  }
}

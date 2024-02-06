package com.snowplowanalytics.snowplow.enrich.common.enrichments

import com.snowplowanalytics.iglu.core.SchemaKey
import com.snowplowanalytics.snowplow.badrows.Payload.PartiallyEnrichedEvent
import io.circe.{HCursor, Json, parser}
import io.opencensus.stats.Measure.MeasureLong
import io.opencensus.stats.{Aggregation, Stats, View}
import io.opencensus.stats.View.Name
import io.opencensus.tags.{TagKey, TagValue, Tags}

import scala.collection.JavaConverters._

object BadRowMetrics {

  final case class EventFailureDetails(appId: String, eventName: String, error: String)

  def eventFailureDetails(event: PartiallyEnrichedEvent, error: String): EventFailureDetails = {
    val eventName =
    try {
      val doc: Json = parser.parse(event.unstruct_event.getOrElse("")).getOrElse(Json.Null)
      val cursor: HCursor = doc.hcursor

      val schema = SchemaKey.fromUri(cursor
        .downField("data").downField("schema").as[String].toOption.get).toOption.get
      schema.name + "_" + schema.version
    } catch {
      case _: Exception => "unknown"
    }

    EventFailureDetails(event.app_id.getOrElse("unknown"), eventName, error)
  }

  private val eventErrorsMeasure = MeasureLong.create(
    "gametuner_enricher/event_errors",
    "Detailed enricher event errors by type", "1")

  private val appIdTag = TagKey.create("app_id")
  private val eventNameTag = TagKey.create("event_name")
  private val errorTag = TagKey.create("error")

  private val eventErrorsView: View = View.create(
    Name.create("gametuner_enricher/event_errors"),
    "Detailed enricher event errors by type",
    eventErrorsMeasure,
    Aggregation.Sum.create(),
    List(appIdTag, eventNameTag, errorTag).asJava)
  Stats.getViewManager.registerView(eventErrorsView)

  def trackEventError(eventFailureDetails: EventFailureDetails): Unit = {
    val ctx = Tags.getTagger.emptyBuilder()
      .putLocal(appIdTag, TagValue.create(eventFailureDetails.appId))
      .putLocal(eventNameTag, TagValue.create(eventFailureDetails.eventName))
      .putLocal(errorTag, TagValue.create(eventFailureDetails.error))
      .build()
    val scope = Tags.getTagger.withTagContext(ctx)
    Stats.getStatsRecorder.newMeasureMap().put(eventErrorsMeasure, 1).record();
    scope.close()
  }
}

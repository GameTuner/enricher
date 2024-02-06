/*
 * Copyright (c) 2012-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.common
package enrichments

import java.util.{TimeZone, UUID}
import scala.util.control.NonFatal
import cats.syntax.either._
import cats.syntax.option._
import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import io.circe.{HCursor, Json, Printer, parser}
import org.apache.commons.codec.digest.DigestUtils
import org.joda.time.{DateTime, DateTimeZone, Period, Seconds}
import org.joda.time.format.DateTimeFormat

import scala.collection.mutable

/** Holds the enrichments related to events. */
object EventEnrichments {

  /** A Redshift-compatible timestamp format */
  private val TstampFormat =
    DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(DateTimeZone.UTC)

  /**
   * Converts a Joda DateTime into a Redshift-compatible timestamp String.
   * @param datetime The Joda DateTime to convert to a timestamp String
   * @return the timestamp String
   */
  def toTimestamp(datetime: DateTime): String = TstampFormat.print(datetime)

  /**
   * Converts a Redshift-compatible timestamp String back into a Joda DateTime.
   * @param timestamp The timestamp String to convert
   * @return the Joda DateTime
   */
  def fromTimestamp(timestamp: String): DateTime = TstampFormat.parseDateTime(timestamp)

  /**
   * Make a collector_tstamp Redshift-compatible
   * @param Optional collectorTstamp
   * @return Validation boxing the result of making the timestamp Redshift-compatible
   */
  def formatCollectorTstamp(collectorTstamp: Option[DateTime]): Either[FailureDetails.EnrichmentFailure, String] =
    (collectorTstamp match {
      case None =>
        FailureDetails.EnrichmentFailureMessage
          .InputData("collector_tstamp", None, "should be set")
          .asLeft
      case Some(t) =>
        val formattedTimestamp = toTimestamp(t)
        if (formattedTimestamp.startsWith("-") || t.getYear > 9999 || t.getYear < 0) {
          val msg = s"formatted as $formattedTimestamp is not Redshift-compatible"
          FailureDetails.EnrichmentFailureMessage
            .InputData("collector_tstamp", t.toString.some, msg)
            .asLeft
        } else
          formattedTimestamp.asRight
    }).leftMap(FailureDetails.EnrichmentFailure(None, _))

  /**
   * Calculate the derived timestamp
   * If dvce_sent_tstamp and dvce_created_tstamp are not null and the former is after the latter,
   * add the difference between the two to the collector_tstamp.
   * Otherwise just return the collector_tstamp.
   * TODO: given missing collectorTstamp is invalid, consider updating this signature to
   * `..., collectorTstamp: String): Validation[String, String]` and making the call to this
   * function in the EnrichmentManager dependent on a Success(collectorTstamp).
   * @param dvceSentTstamp
   * @param dvceCreatedTstamp
   * @param collectorTstamp
   * @return derived timestamp
   */
  def getDerivedTimestamp(
    dvceSentTstamp: Option[String],
    dvceCreatedTstamp: Option[String],
    collectorTstamp: Option[String],
    trueTstamp: Option[String],
    backfillMode: Boolean
  ): Either[FailureDetails.EnrichmentFailure, Option[String]] =
    try {
      if (backfillMode && trueTstamp.isEmpty) {
        throw new IllegalStateException("trueTstamp must be defined in backfill mode")
      }

      trueTstamp match {
        case Some(ttm) =>
          val trueTstamp = fromTimestamp(ttm)
          val collectTstamp = fromTimestamp(collectorTstamp.get)
          val seconds = Seconds.secondsBetween(collectTstamp, trueTstamp)
          if (seconds.getSeconds > 60) {
            throw new IllegalStateException("Cannot set trueTstamp events in the future")
          }
          Some(ttm).asRight
        case None =>
            ((dvceSentTstamp, dvceCreatedTstamp, collectorTstamp) match {
              case (Some(dst), Some(dct), Some(ct)) =>
                val startTstamp = fromTimestamp(dct)
                val endTstamp = fromTimestamp(dst)
                if (startTstamp.isBefore(endTstamp))
                  toTimestamp(fromTimestamp(ct).minus(new Period(startTstamp, endTstamp))).some
                else
                  ct.some
              case _ => collectorTstamp
            }).asRight
      }
    } catch {
      case NonFatal(e) =>
        FailureDetails
          .EnrichmentFailure(
            None,
            FailureDetails.EnrichmentFailureMessage.Simple(
              s"exception calculating derived timestamp: ${e.getMessage}"
            )
          )
          .asLeft
    }

  def getEventTimestamp(
    appIdTimezone: String,
    closePartitionAfterHours: Int,
    collectorTstamp: String,
    derivedTstamp: String,
    backfillMode: Boolean):
  Either[FailureDetails.EnrichmentFailure, String] = {
    try {
      if (!backfillMode && shouldUseCollectorTimeForPartition(appIdTimezone, closePartitionAfterHours, derivedTstamp, collectorTstamp)) {
        Right(collectorTstamp)
      } else {
        Right(derivedTstamp)
      }

    } catch {
      case NonFatal(e) =>
        FailureDetails
          .EnrichmentFailure(
            None,
            FailureDetails.EnrichmentFailureMessage.Simple(
              s"exception calculating event timestamp: ${e.getMessage}"
            )
          )
          .asLeft
    }
  }

  def getEventPartitionDate(appIdTimezone: String, eventTstamp: String):
  Either[FailureDetails.EnrichmentFailure, String] = {
    try {
      val eventTs = fromTimestamp(eventTstamp)
      val date = eventTs.withZone(DateTimeZone.forTimeZone(TimeZone.getTimeZone(appIdTimezone))).toLocalDate
      Right(date.toString("yyyy-MM-dd"))

    } catch {
      case NonFatal(e) =>
        FailureDetails
          .EnrichmentFailure(
            None,
            FailureDetails.EnrichmentFailureMessage.Simple(
              s"exception calculating event partition date: ${e.getMessage}"
            )
          )
          .asLeft
    }
  }

  private def shouldUseCollectorTimeForPartition(appIdTimezone: String, closePartitionAfterHours: Int,
                                       derivedTstamp: String, collectorTstamp: String): Boolean = {
    val derivedTs = fromTimestamp(derivedTstamp)
    val collectorTs = fromTimestamp(collectorTstamp)
    val derivedDateTime = derivedTs.withZone(DateTimeZone.forTimeZone(TimeZone.getTimeZone(appIdTimezone)))
    val collectorDateTime = collectorTs.withZone(DateTimeZone.forTimeZone(TimeZone.getTimeZone(appIdTimezone)))

    if (derivedDateTime.toLocalDate == collectorDateTime.toLocalDate) {
      return false
    }

    if (collectorDateTime.toLocalDate.minusDays(1) == derivedDateTime.toLocalDate
      && collectorDateTime.getHourOfDay >= 0 && collectorDateTime.getHourOfDay < closePartitionAfterHours) {
      return false
    }

    return true
  }

  def getEventQualityScore(appIdTimezone: String, closePartitionAfterHours: Int,
                           derivedTstamp: String, collectorTstamp: String,
                           dvceCreatedTstamp: String, dvceSentTstamp: String, trueTstamp: String, backfillMode: Boolean):
  Either[FailureDetails.EnrichmentFailure, Int] = {
    try {
      val collectorTs = fromTimestamp(collectorTstamp)

      if (backfillMode) {
        return Right(5)
      }

      if (trueTstamp != null) {
        return Right(4)
      }

      val dvceCreatedTs = fromTimestamp(dvceCreatedTstamp)
      val dvceSentTs = fromTimestamp(dvceSentTstamp)

      if (dvceCreatedTs.isAfter(dvceSentTs)) {
        return Right(3)
      }

      if (Math.abs(Seconds.secondsBetween(dvceSentTs, collectorTs).getSeconds) > 60) {
        return Right(2)
      }

      if (!shouldUseCollectorTimeForPartition(appIdTimezone, closePartitionAfterHours, derivedTstamp, collectorTstamp)) {
        return Right(0)
      }

      return Right(1)
    } catch {
      case NonFatal(e) =>
        FailureDetails
          .EnrichmentFailure(
            None,
            FailureDetails.EnrichmentFailureMessage.Simple(
              s"exception calculating event quality score: ${e.getMessage}"
            )
          )
          .asLeft
    }
  }


  /**
   * Extracts the timestamp from the format as laid out in the Tracker Protocol:
   * https://github.com/snowplow/snowplow/wiki/snowplow-tracker-protocol#wiki-common-params
   * @param tstamp The timestamp as stored in the Tracker Protocol
   * @return a Tuple of two Strings (date and time), or an error message if the format was invalid
   */
  val extractTimestamp: (String, String) => Either[FailureDetails.EnrichmentFailure, String] =
    (field, tstamp) =>
      try {
        val dt = new DateTime(tstamp.toLong)
        val timestampString = toTimestamp(dt)
        if (timestampString.startsWith("-") || dt.getYear > 9999 || dt.getYear < 0) {
          val msg = s"formatting as $timestampString is not Redshift-compatible"
          val f = FailureDetails.EnrichmentFailureMessage.InputData(
            field,
            Option(tstamp),
            msg
          )
          FailureDetails.EnrichmentFailure(None, f).asLeft
        } else
          timestampString.asRight
      } catch {
        case _: NumberFormatException =>
          val msg = "not in the expected format: ms since epoch"
          val f = FailureDetails.EnrichmentFailureMessage.InputData(
            field,
            Option(tstamp),
            msg
          )
          FailureDetails.EnrichmentFailure(None, f).asLeft
      }

  /**
   * Turns an event code into a valid event type, e.g. "pv" -> "page_view". See the Tracker
   * Protocol for details:
   * https://github.com/snowplow/snowplow/wiki/snowplow-tracker-protocol#wiki-event2
   * @param eventCode The event code
   * @return the event type, or an error message if not recognised, boxed in a Scalaz Validation
   */
  val extractEventType: (String, String) => Either[FailureDetails.EnrichmentFailure, String] =
    (field, code) =>
      code match {
        case "se" => "struct".asRight
        case "ev" => "struct".asRight // Leave in for legacy.
        case "ue" => "unstruct".asRight
        case "ad" => "ad_impression".asRight // Leave in for legacy.
        case "tr" => "transaction".asRight
        case "ti" => "transaction_item".asRight
        case "pv" => "page_view".asRight
        case "pp" => "page_ping".asRight
        case _ =>
          val msg = "not recognized as an event type"
          val f = FailureDetails.EnrichmentFailureMessage.InputData(
            field,
            Option(code),
            msg
          )
          FailureDetails.EnrichmentFailure(None, f).asLeft
      }

  /**
   * Returns a unique event ID. The event ID is generated as a type 4 UUID, then converted
   * to a String.
   * () on the function signature because it's not pure
   * @return the unique event ID
   */
  def generateEventId(): String = UUID.randomUUID().toString

  def calculateCustomEventFingerprint(event: EnrichedEvent): String = {
    val unitSeparator = "\u001f"
    val builder = new mutable.StringBuilder

    builder.append(event.app_id)
    builder.append(unitSeparator)
    builder.append(event.platform)
    builder.append(unitSeparator)
    builder.append(event.dvce_created_tstamp)
    builder.append(unitSeparator)
    builder.append(event.true_tstamp)
    builder.append(unitSeparator)
    builder.append(event.user_id)
    builder.append(unitSeparator)
    builder.append(event.installation_id)
    builder.append(unitSeparator)
    builder.append(event.event_id)
    builder.append(unitSeparator)
    val printer = Printer(
      dropNullValues = true,
      indent = "",
      sortKeys = true,
    )
    val doc = parser.parse(event.unstruct_event).getOrElse(Json.Null)
    val cursor: HCursor = doc.hcursor

    // Sort keys, sort maps (representend as array of key, value objects)
    // We don't want fingerprint to change as its still same data
    // Clients can send different json string for same events as dictionaries do not guarantee ordering
    val processedUnstruct = cursor
      .downField("data").downField("data")
      .withFocus(_.mapObject(data => {

        data.mapValues(value => {
          if (value.isArray) {
            value.asArray match {
              case Some(arr) if arr.forall(item => item.isObject &&
                item.asObject.get.contains("key") &&
                item.asObject.get.contains("value") &&
                item.asObject.get.keys.size == 2
              ) => Json.arr(arr.sortBy(obj => obj.hcursor.get[String]("key").getOrElse("")): _*)
              case _ => value
            }
          } else {
            value
          }
        })
      }
      ))
      .top.map(v => printer.print(v)).get

    builder.append(processedUnstruct)
    builder.append(unitSeparator)
    builder.append(event.sandbox_mode)
    builder.append(unitSeparator)
    if (event.backfill_mode == "1") {
      // In case this is backfill, we generate a random fingerprint
      // We want to avoid bigquery loader deduplicating backfill event in this way
      // This allows to restart backfill jobs multiple times without duplicates being rejected
      // (current workflow is: delete partition from _backfill table, load whole partition, send)
      builder.append(java.util.UUID.randomUUID.toString)
      builder.append(unitSeparator)
    }

    DigestUtils.md5Hex(builder.toString())

  }
}

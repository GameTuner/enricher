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

import cats.syntax.either._
import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.specs2.Specification
import org.specs2.matcher.DataTables

class ExtractEventTypeSpec extends Specification with DataTables {
  def is = s2"""
  extractEventType should return the event name for any valid event code         $e1
  extractEventType should return a validation failure for any invalid event code $e2
  formatCollectorTstamp should validate collector timestamps                     $e3
  extractTimestamp should validate timestamps                                    $e4
  """

  val FieldName = "e"
  def err: String => FailureDetails.EnrichmentFailure =
    input =>
      FailureDetails.EnrichmentFailure(
        None,
        FailureDetails.EnrichmentFailureMessage.InputData(
          FieldName,
          Option(input),
          "not recognized as an event type"
        )
      )

  def e1 =
    "SPEC NAME" || "INPUT VAL" | "EXPECTED OUTPUT" |
      "transaction" !! "tr" ! "transaction" |
      "transaction item" !! "ti" ! "transaction_item" |
      "page view" !! "pv" ! "page_view" |
      "page ping" !! "pp" ! "page_ping" |
      "unstructured event" !! "ue" ! "unstruct" |
      "structured event" !! "se" ! "struct" |
      "structured event (legacy)" !! "ev" ! "struct" |
      "ad impression (legacy)" !! "ad" ! "ad_impression" |> { (_, input, expected) =>
      EventEnrichments.extractEventType(FieldName, input) must beRight(expected)
    }

  def e2 =
    "SPEC NAME" || "INPUT VAL" | "EXPECTED OUTPUT" |
      "null" !! null ! err(null) |
      "empty string" !! "" ! err("") |
      "unrecognized #1" !! "e" ! err("e") |
      "unrecognized #2" !! "evnt" ! err("evnt") |> { (_, input, expected) =>
      EventEnrichments.extractEventType(FieldName, input) must beLeft(expected)
    }

  val SeventiesTstamp = Some(new DateTime(0, DateTimeZone.UTC))
  val BCTstamp = SeventiesTstamp.map(_.minusYears(2000))
  val FarAwayTstamp = SeventiesTstamp.map(_.plusYears(10000))
  def e3 =
// format: off
    "SPEC NAME"          || "INPUT VAL"     | "EXPECTED OUTPUT" |
    "None"               !! None            ! FailureDetails.EnrichmentFailure(None, FailureDetails.EnrichmentFailureMessage.InputData("collector_tstamp", None, "should be set")).asLeft |
    "Negative timestamp" !! BCTstamp        ! FailureDetails.EnrichmentFailure(None, FailureDetails.EnrichmentFailureMessage.InputData("collector_tstamp", Some("-0030-01-01T00:00:00.000Z"),"formatted as -0030-01-01 00:00:00.000 is not Redshift-compatible")).asLeft |
    ">10k timestamp"     !! FarAwayTstamp   ! FailureDetails.EnrichmentFailure(None, FailureDetails.EnrichmentFailureMessage.InputData("collector_tstamp", Some("11970-01-01T00:00:00.000Z"),"formatted as 11970-01-01 00:00:00.000 is not Redshift-compatible")).asLeft |
    "Valid timestamp"    !! SeventiesTstamp ! "1970-01-01 00:00:00.000".asRight |> {
// format: on
      (_, input, expected) =>
        EventEnrichments.formatCollectorTstamp(input) must_== expected
    }

  def e4 =
    "SPEC NAME" || "INPUT VAL" | "EXPECTED OUTPUT" |
      "Not long" !! (("f", "v")) ! FailureDetails
        .EnrichmentFailure(
          None,
          FailureDetails.EnrichmentFailureMessage.InputData(
            "f",
            Some("v"),
            "not in the expected format: ms since epoch"
          )
        )
        .asLeft |
      "Too long" !! (("f", "1111111111111111")) ! FailureDetails
        .EnrichmentFailure(
          None,
          FailureDetails.EnrichmentFailureMessage.InputData(
            "f",
            Some("1111111111111111"),
            "formatting as 37179-09-17 07:18:31.111 is not Redshift-compatible"
          )
        )
        .asLeft |
      "Valid ts" !! (("f", "1")) ! "1970-01-01 00:00:00.001".asRight |> { (_, input, expected) =>
      EventEnrichments.extractTimestamp(input._1, input._2) must_== expected
    }
}

class DerivedTimestampSpec extends Specification with DataTables {
  def is = s2"""
  getDerivedTimestamp should correctly calculate the derived timestamp $e1
  getDerivedTimestamp should reject invalid events $e2"""

  def e1 =
    "SPEC NAME" || "DVCE_CREATED_TSTAMP" | "DVCE_SENT_TSTAMP" | "COLLECTOR_TSTAMP" | "TRUE_TSTAMP" | "BACKFILL_MODE" | "EXPECTED DERIVED_TSTAMP" |
      "No dvce_sent_tstamp" !! "2014-04-29 12:00:54.555" ! null ! "2014-04-29 09:00:54.000" ! null ! false ! "2014-04-29 09:00:54.000" |
      "No dvce_created_tstamp" !! null ! null ! "2014-04-29 09:00:54.000" ! null ! false ! "2014-04-29 09:00:54.000" |
      "No collector_tstamp" !! null ! null ! null ! null ! false ! null |
      "dvce_sent_tstamp before dvce_created_tstamp" !! "2014-04-29 09:00:54.001" ! "2014-04-29 09:00:54.000" ! "2014-04-29 09:00:54.000" ! null ! false ! "2014-04-29 09:00:54.000" |
      "dvce_sent_tstamp after dvce_created_tstamp" !! "2014-04-29 09:00:54.000" ! "2014-04-29 09:00:54.001" ! "2014-04-29 09:00:54.000" ! null ! false ! "2014-04-29 09:00:53.999" |
      "true_tstamp override" !! "2014-04-29 09:00:54.001" ! "2014-04-29 09:00:54.000" ! "2014-04-29 09:00:54.000" ! "2000-01-01 00:00:00.000" ! false ! "2000-01-01 00:00:00.000" |
      "true_tstamp override, backfill event" !! "2014-04-29 09:00:54.001" ! "2014-04-29 09:00:54.000" ! "2014-04-29 09:00:54.000" ! "2000-01-01 00:00:00.000" ! true ! "2000-01-01 00:00:00.000" |
      "true_tstamp override in the future, allowed" !! "2014-04-29 09:00:54.001" ! "2014-04-29 09:00:54.000" ! "2014-04-29 09:00:54.000" ! "2014-04-29 09:01:30.000" ! false ! "2014-04-29 09:01:30.000" |> {
      (_, created, sent, collected, truth, backfillMode, expected) =>
        EventEnrichments.getDerivedTimestamp(
          Option(sent),
          Option(created),
          Option(collected),
          Option(truth),
          backfillMode
        ) must beRight(Option(expected))
    }

  def e2 =
    "SPEC NAME" || "DVCE_CREATED_TSTAMP" | "DVCE_SENT_TSTAMP" | "COLLECTOR_TSTAMP" | "TRUE_TSTAMP" | "BACKFILL_MODE" |
      "true_tstamp missing in backfill event" !! "2014-04-29 09:00:54.000" ! "2014-04-29 09:00:54.001" ! "2014-04-29 09:00:54.000" ! null ! true |
      "true_tstamp override in the future, not allowed" !! "2014-04-29 09:00:54.001" ! "2014-04-29 09:00:54.000" ! "2014-04-29 09:00:54.000" ! "2014-04-29 09:02:30.000" ! false |
      "true_tstamp override in the future, not allowed, backfill event" !! "2014-04-29 09:00:54.001" ! "2014-04-29 09:00:54.000" ! "2014-04-29 09:00:54.000" ! "2014-04-29 09:02:30.000" ! true |> {

      (_, created, sent, collected, truth, backfillMode) =>
        EventEnrichments.getDerivedTimestamp(
          Option(sent),
          Option(created),
          Option(collected),
          Option(truth),
          backfillMode
        ) must beLeft
    }
}

class EventTimestampSpec extends Specification with DataTables {
  def is = s2"""
  getEventTimestamp should correctly calculate the derived timestamp $e1"""

  def e1 =
     "SPEC NAME" || "APP_ID_TIMEZONE" | "CLOSE_PARTITION_AFTER_HOURS" | "COLLECTOR_TSTAMP" | "DERIVED_TSTAMP" | "BACKFILL_MODE" | "EXPECTED EVENT_TSTAMP" |
      "UTC, 2 days before, before partition close" !! "UTC" ! 4 ! "2022-12-05 02:00:00.000" ! "2022-12-03 02:00:00.000" ! false ! "2022-12-05 02:00:00.000" |
      "UTC, 1 day before, before partition close" !! "UTC" ! 4 ! "2022-12-05 02:00:00.000" ! "2022-12-04 02:00:00.000" ! false ! "2022-12-04 02:00:00.000" |
      "UTC, same day, before partition close" !! "UTC" ! 4 ! "2022-12-05 02:00:00.000" ! "2022-12-05 01:00:00.000" ! false ! "2022-12-05 01:00:00.000" |
      "UTC, 2 days before, after close" !! "UTC" ! 4 ! "2022-12-05 04:00:00.000" ! "2022-12-03 02:00:00.000" ! false ! "2022-12-05 04:00:00.000" |
      "UTC, 1 day before, after close" !! "UTC" ! 4 ! "2022-12-05 04:00:00.000" ! "2022-12-04 02:00:00.000" ! false ! "2022-12-05 04:00:00.000" |
      "UTC, same day, after close" !! "UTC" ! 4 ! "2022-12-05 04:00:00.000" ! "2022-12-05 01:00:00.000" ! false ! "2022-12-05 01:00:00.000" |
      "Europe/Belgrade, 1 day before, before partition close" !! "Europe/Belgrade" ! 4 ! "2022-12-05 02:00:00.000" ! "2022-12-04 02:00:00.000" ! false ! "2022-12-04 02:00:00.000" |
      "Europe/Belgrade, 1 day before, after close" !! "Europe/Belgrade" ! 4 ! "2022-12-05 03:00:00.000" ! "2022-12-04 02:00:00.000" ! false ! "2022-12-05 03:00:00.000" |
      "ignore partition close if backfill" !! "Europe/Belgrade" ! 4 ! "2022-12-05 03:00:00.000" ! "2022-12-04 02:00:00.000" ! true ! "2022-12-04 02:00:00.000" |>{

      (_, appIdTimezone, closePartitionAfterHours, collectorTstamp, derivedTstamp, backfillMode, expected) =>
        EventEnrichments.getEventTimestamp(
          appIdTimezone,
          closePartitionAfterHours,
          collectorTstamp,
          derivedTstamp,
          backfillMode
        ) must beRight(expected)
    }
}

class EventPartitionDateSpec extends Specification with DataTables {
  def is = s2"""
  getEventPartitionDate should correctly calculate partition date $e1"""

  def e1 =
    "SPEC NAME" || "APP_ID_TIMEZONE" | "EVENT_TSTAMP" | "EXPECTED PARTITION_DATE" |
      "UTC" !! "UTC" ! "2022-12-05 23:00:00.000" ! "2022-12-05" |
      "Europe/Belgrade +1 before midnight" !! "Europe/Belgrade" ! "2022-12-05 22:00:00.000" ! "2022-12-05" |
      "Europe/Belgrade +1 midnight" !! "Europe/Belgrade" ! "2022-12-05 23:00:00.000" ! "2022-12-06" |
      "Europe/Belgrade +2 before midnight" !! "Europe/Belgrade" ! "2022-04-04 21:00:00.000" ! "2022-04-04" |
      "Europe/Belgrade +2 midnight" !! "Europe/Belgrade" ! "2022-04-04 22:00:00.000" ! "2022-04-05" |>{

      (_, appIdTimezone, eventTstamp, expected) =>
        EventEnrichments.getEventPartitionDate(
          appIdTimezone,
          eventTstamp
        ) must beRight(expected)
    }
}

class CustomEventFingerprintSpec extends Specification with DataTables {
  def is = s2"""
  calculateCustomEventFingerprint calculates same fingerprint for same input $e1"""

  def simpleEnrichedEvent(unstruct_event: String): EnrichedEvent = {
    val event = new EnrichedEvent()
    event.app_id = "app_id"
    event.platform = "android"
    event.dvce_created_tstamp = "2022-04-04 22:00:00.000"
    event.true_tstamp = "2022-04-04 22:00:00.000"
    event.user_id = "user_id"
    event.installation_id = "installation_id"
    event.event_id = "event_id"
    event.unstruct_event = unstruct_event
    event
  }

  def e1 =
    "SPEC NAME" || "ENRICHED_EVENT" | "EXPECTED_FINGERPRINT" |
      "stable fingerprint" !! simpleEnrichedEvent("{\"schema\":\"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0\",\"data\":{\"schema\":\"iglu:com.algebraai.gametuner.event/purchase/jsonschema/1-0-0\",\"data\":{\"paid_currency\":\"RSD\", \"paid_amount\":1000,  \"price\": 20, \"price_currency\": \"EUR\"}}}") ! "4e81dd2487a51c34c115efb557da55c6" |
      "spaces in json do not affect fingerprint" !! simpleEnrichedEvent("{\"schema\":\"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0\",\"data\":{\"schema\":\"iglu:com.algebraai.gametuner.event/purchase/jsonschema/1-0-0\",\"data\":{\"paid_currency\":\"RSD\",    \"paid_amount\":1000,     \"price\": 20, \"price_currency\": \"EUR\"}}}") ! "4e81dd2487a51c34c115efb557da55c6" |
      "order of params does not affect fingerprint" !! simpleEnrichedEvent("{\"schema\":\"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0\",\"data\":{\"schema\":\"iglu:com.algebraai.gametuner.event/purchase/jsonschema/1-0-0\",\"data\":{\"paid_amount\":1000, \"paid_currency\":\"RSD\",         \"price\": 20, \"price_currency\": \"EUR\"}}}") ! "4e81dd2487a51c34c115efb557da55c6" |
      "null values do not affect fingerprint" !! simpleEnrichedEvent("{\"schema\":\"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0\",\"data\":{\"schema\":\"iglu:com.algebraai.gametuner.event/purchase/jsonschema/1-0-0\",\"data\":{\"paid_amount\":1000, \"paid_currency\":\"RSD\",  \"value\":null,        \"price\": 20, \"price_currency\": \"EUR\"}}}") ! "4e81dd2487a51c34c115efb557da55c6" |
      "fingerprint works with map(modelled as array)" !! simpleEnrichedEvent("{\"schema\":\"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0\",\"data\":{\"schema\":\"iglu:com.algebraai.gametuner.event/purchase/jsonschema/1-0-0\",\"data\":{\"paid_amount\":1000, \"map\":[{\"key\":\"key1\", \"value\":\"value1\"}, {\"key\":\"key2\", \"value\":\"value2\"}] }}}") ! "cf3cec4214c93b461042b14848e8fb08" |
      "changing order in map(modelled as array) does not affect fingerprint" !! simpleEnrichedEvent("{\"schema\":\"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0\",\"data\":{\"schema\":\"iglu:com.algebraai.gametuner.event/purchase/jsonschema/1-0-0\",\"data\":{\"paid_amount\":1000, \"map\":[{\"key\":\"key2\", \"value\":\"value2\"}, {\"key\":\"key1\", \"value\":\"value1\"}] }}}") ! "cf3cec4214c93b461042b14848e8fb08" |
      "changing map affects fingerprint" !! simpleEnrichedEvent("{\"schema\":\"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0\",\"data\":{\"schema\":\"iglu:com.algebraai.gametuner.event/purchase/jsonschema/1-0-0\",\"data\":{\"paid_amount\":1000, \"map\":[{\"key\":\"key3\", \"value\":\"value2\"}, {\"key\":\"key1\", \"value\":\"value1\"}] }}}") ! "f93717cfd8e6a4c0eadda26cedb14773" |
      "fingerprint works with array" !! simpleEnrichedEvent("{\"schema\":\"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0\",\"data\":{\"schema\":\"iglu:com.algebraai.gametuner.event/purchase/jsonschema/1-0-0\",\"data\":{\"paid_amount\":1000, \"map\":[{\"a\":\"key3\"}, {\"b\":\"key1\"}] }}}") ! "8c99a5ee00a048bfeac2d1b7a1ce8726" |
      "changing order in non map array changes fingerprint" !! simpleEnrichedEvent("{\"schema\":\"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0\",\"data\":{\"schema\":\"iglu:com.algebraai.gametuner.event/purchase/jsonschema/1-0-0\",\"data\":{\"paid_amount\":1000, \"map\":[{\"b\":\"keya\"}, {\"a\":\"key3\"}] }}}") ! "138d683d315bf997de9becb16b1314eb" |
      "changing param values affects fingerprint" !! simpleEnrichedEvent("{\"schema\":\"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0\",\"data\":{\"schema\":\"iglu:com.algebraai.gametuner.event/purchase/jsonschema/1-0-0\",\"data\":{\"paid_currency\":\"RSD\",    \"paid_amount\":1000,     \"price\": 21, \"price_currency\": \"EUR\"}}}") ! "8d03f9772d6041d67cd298947e70e13f" |
      "fingerprint works with empty data dict" !! simpleEnrichedEvent("{\"schema\":\"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0\",\"data\":{\"schema\":\"iglu:com.algebraai.gametuner.event/purchase/jsonschema/1-0-0\",\"data\":{}}}") ! "c7bfaee259ca88f119e015bb9088180d" |
      "fingerprint works with null dict" !! simpleEnrichedEvent("{\"schema\":\"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0\",\"data\":{\"schema\":\"iglu:com.algebraai.gametuner.event/purchase/jsonschema/1-0-0\",\"data\":null}}") ! "f160c6ea82f0f09bed8b732404b8fab9" |> {
      (_, enrichedEvent, expectedFingerprint) =>
        EventEnrichments.calculateCustomEventFingerprint(enrichedEvent) must beEqualTo(expectedFingerprint)
    }
}
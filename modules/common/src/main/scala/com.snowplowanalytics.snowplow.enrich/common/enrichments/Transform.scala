/*
 * Copyright (c) 2020-2022 Snowplow Analytics Ltd. All rights reserved.
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

import cats.implicits._
import cats.data.ValidatedNel

import com.snowplowanalytics.snowplow.badrows._

import enrichments.{EventEnrichments => EE}
import enrichments.{MiscEnrichments => ME}
//import enrichments.{ClientEnrichments => CE}
import utils.{ConversionUtils => CU, JsonUtils => JU}
import utils.MapTransformer._
import outputs.EnrichedEvent
import adapters.RawEvent

object Transform {

  /**
   * Map the parameters of the input raw event to the fields of the enriched event,
   * with a possible transformation. For instance "ip" in the input would be mapped
   * to "user_ipaddress" in the enriched event
   * @param enriched /!\ MUTABLE enriched event, mutated IN-PLACE /!\
   */
  private[enrichments] def transform(raw: RawEvent, enriched: EnrichedEvent): ValidatedNel[FailureDetails.EnrichmentFailure, Unit] = {
    val sourceMap: SourceMap = raw.parameters.collect { case (k, Some(v)) => (k, v) }
    val firstPassTransform = enriched.transform(sourceMap, firstPassTransformMap)
    val secondPassTransform = enriched.transform(sourceMap, secondPassTransformMap)

    (firstPassTransform |+| secondPassTransform).void
  }

  // The TransformMap used to map/transform the fields takes the format:
  // "source key" -> (transformFunction, field(s) to set)
  // Caution: by definition, a TransformMap loses type safety. Always unit test!
  private val firstPassTransformMap: TransformMap =
    Map(
      ("e", (EE.extractEventType, "event")),
      ("ip", (ME.extractIp, "user_ipaddress")),
      ("gc", (ME.toTsvSafe, "geo_country")),
      ("gcn", (ME.toTsvSafe, "geo_country_name")),
      ("ak", (ME.toTsvSafe, "api_key")),
      ("aid", (ME.toTsvSafe, "app_id")),
      ("p", (ME.extractPlatform, "platform")),
      ("uid", (ME.toTsvSafe, "user_id")),
      ("unqid", (ME.toTsvSafe, "unique_id")),
      ("iid", (ME.toTsvSafe, "installation_id")),
      ("nuid", (ME.toTsvSafe, "network_userid")),
      ("fp", (ME.toTsvSafe, "user_fingerprint")),
      ("dtm", (EE.extractTimestamp, "dvce_created_tstamp")),
      ("ttm", (EE.extractTimestamp, "true_tstamp")),
      ("stm", (EE.extractTimestamp, "dvce_sent_tstamp")),
      ("etm", (EE.extractTimestamp, "event_tstamp")),
      ("eq", (ME.toTsvSafe, "event_quality")),
      ("ed", (ME.toTsvSafe, "date_")),
      ("tna", (ME.toTsvSafe, "name_tracker")),
      ("tv", (ME.toTsvSafe, "v_tracker")),
      ("cv", (ME.toTsvSafe, "v_collector")),
      ("eid", (CU.validateUuid, "event_id")),
      ("sm", (ME.toTsvSafe, "sandbox_mode")),
      ("bm", (ME.toTsvSafe, "backfill_mode")),
      // Custom contexts
      ("co", (JU.extractUnencJson, "contexts")),
      ("cx", (JU.extractBase64EncJson, "contexts")),
      // Custom unstructured events
      ("ue_pr", (JU.extractUnencJson, "unstruct_event")),
      ("ue_px", (JU.extractBase64EncJson, "unstruct_event"))
    )

  // A second TransformMap which can overwrite values set by the first
  private val secondPassTransformMap: TransformMap =
    // Overwrite collector-set nuid with tracker-set tnuid
    Map(("tnuid", (ME.toTsvSafe, "network_userid")))
}

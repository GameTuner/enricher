/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.enrichments

import org.slf4j.LoggerFactory

import cats.Monad
import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, ValidatedNel}

import cats.implicits._

import com.snowplowanalytics.snowplow.badrows.FailureDetails.EnrichmentFailure
import com.snowplowanalytics.snowplow.badrows.{BadRow, FailureDetails, Processor}

import com.snowplowanalytics.snowplow.enrich.common.adapters.RawEvent
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent

/**
 * Atomic fields length validation inspired by
 * https://github.com/snowplow/snowplow-scala-analytics-sdk/blob/master/src/main/scala/com.snowplowanalytics.snowplow.analytics.scalasdk/validate/package.scala
 */
object AtomicFieldsLengthValidator {

  final case class AtomicField(
    name: String,
    enrichedValueExtractor: EnrichedEvent => String,
    maxLength: Int
  )

  private val logger = LoggerFactory.getLogger("InvalidEnriched")

  // format: off
  private val atomicFields =
    List(
      AtomicField(name = "api_key",            _.api_key,           maxLength = 255 ),
      AtomicField(name = "app_id",             _.app_id,            maxLength = 255 ),
      AtomicField(name = "platform",           _.platform,          maxLength = 255 ),
      AtomicField(name = "event",              _.event,             maxLength = 128 ),
      AtomicField(name = "event_id",           _.event,             maxLength = 36  ),
      AtomicField(name = "name_tracker",       _.name_tracker,      maxLength = 128 ),
      AtomicField(name = "v_tracker",          _.v_tracker,         maxLength = 100 ),
      AtomicField(name = "v_collector",        _.v_collector,       maxLength = 100 ),
      AtomicField(name = "v_etl",              _.v_etl,             maxLength = 1000 ),
      AtomicField(name = "user_id",            _.user_id,           maxLength = 255 ),
      AtomicField(name = "unique_id",          _.unique_id,         maxLength = 255 ),
      AtomicField(name = "installation_id",    _.installation_id,   maxLength = 255 ),
      AtomicField(name = "network_userid",     _.network_userid,    maxLength = 128 ),
      AtomicField(name = "geo_country",        _.geo_country,       maxLength = 2   ),
      AtomicField(name = "geo_country_name",   _.geo_country_name,  maxLength = 255 ),
      AtomicField(name = "geo_region",         _.geo_region,        maxLength = 3   ),
      AtomicField(name = "geo_city",           _.geo_city,          maxLength = 75  ),
      AtomicField(name = "geo_zipcode",        _.geo_zipcode,       maxLength = 15  ),
      AtomicField(name = "geo_region_name",    _.geo_region_name,   maxLength = 100 ),
      AtomicField(name = "mkt_medium",         _.mkt_medium,        maxLength = 255 ),
      AtomicField(name = "mkt_source",         _.mkt_source,        maxLength = 255 ),
      AtomicField(name = "mkt_term",           _.mkt_term,          maxLength = 255 ),
      AtomicField(name = "mkt_content",        _.mkt_content,       maxLength = 500 ),
      AtomicField(name = "mkt_campaign",       _.mkt_campaign,      maxLength = 255 ),
      AtomicField(name = "etl_tags",           _.etl_tags,          maxLength = 500 ),
      AtomicField(name = "event_vendor",       _.event_vendor,      maxLength = 1000),
      AtomicField(name = "event_name",         _.event_name,        maxLength = 1000),
      AtomicField(name = "event_format",       _.event_format,      maxLength = 128 ),
      AtomicField(name = "event_version",      _.event_version,     maxLength = 128 ),
      AtomicField(name = "event_fingerprint",  _.event_fingerprint, maxLength = 128 ),
      AtomicField(name = "sandbox_mode",       _.sandbox_mode,      maxLength = 1 ),
      AtomicField(name = "backfill_mode",       _.sandbox_mode,      maxLength = 1 ),
    )
  // format: on

  def validate[F[_]: Monad](
    event: EnrichedEvent,
    rawEvent: RawEvent,
    processor: Processor,
    acceptInvalid: Boolean,
    invalidCount: F[Unit]
  ): F[Either[BadRow, Unit]] =
    atomicFields
      .map(validateField(event))
      .combineAll match {
      case Invalid(errors) if acceptInvalid =>
        handleAcceptableBadRow(invalidCount, event, errors) *> Monad[F].pure(Right(()))
      case Invalid(errors) =>
        Monad[F].pure(buildBadRow(event, rawEvent, processor, errors).asLeft)
      case Valid(()) =>
        Monad[F].pure(Right(()))
    }

  private def validateField(event: EnrichedEvent)(atomicField: AtomicField): ValidatedNel[String, Unit] = {
    val actualValue = atomicField.enrichedValueExtractor(event)
    if (actualValue != null && actualValue.length > atomicField.maxLength)
      s"Field ${atomicField.name} longer than maximum allowed size ${atomicField.maxLength}".invalidNel
    else
      Valid(())
  }

  private def buildBadRow(
    event: EnrichedEvent,
    rawEvent: RawEvent,
    processor: Processor,
    errors: NonEmptyList[String]
  ): BadRow.EnrichmentFailures =
    EnrichmentManager.buildEnrichmentFailuresBadRow(
      NonEmptyList(
        asEnrichmentFailure("Enriched event does not conform to atomic schema field's length restrictions"),
        errors.toList.map(asEnrichmentFailure)
      ),
      EnrichedEvent.toPartiallyEnrichedEvent(event),
      RawEvent.toRawEvent(rawEvent),
      processor
    )

  private def handleAcceptableBadRow[F[_]: Monad](
    invalidCount: F[Unit],
    event: EnrichedEvent,
    errors: NonEmptyList[String]
  ): F[Unit] =
    invalidCount *>
      Monad[F].pure(
        logger.debug(
          s"Enriched event not valid against atomic schema. Event id: ${event.event_id}. Invalid fields: ${errors.toList.mkString(",")}"
        )
      )

  private def asEnrichmentFailure(errorMessage: String): EnrichmentFailure =
    EnrichmentFailure(
      enrichment = None,
      FailureDetails.EnrichmentFailureMessage.Simple(errorMessage)
    )
}

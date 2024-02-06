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

import java.nio.charset.Charset
import java.time.Instant
import org.joda.time.DateTime
import io.circe.{HCursor, Json, parser}
import cats.{Applicative, Monad}
import cats.data.{EitherT, NonEmptyList, OptionT, StateT}
import cats.effect.Clock
import cats.implicits._
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.uniqueid.UniqueIdEnrichment
import com.snowplowanalytics.iglu.client.Client
import com.snowplowanalytics.iglu.client.resolver.registries.RegistryLookup
import com.snowplowanalytics.iglu.core.{SchemaKey, SelfDescribingData}
import com.snowplowanalytics.iglu.core.circe.implicits._
import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.snowplow.badrows.{FailureDetails, Payload, Processor}
import adapters.RawEvent
import enrichments.{EventEnrichments => EE}
import enrichments.{MiscEnrichments => ME}
import enrichments.registry._
import enrichments.registry.apirequest.ApiRequestEnrichment
import enrichments.registry.pii.PiiPseudonymizerEnrichment
import enrichments.registry.sqlquery.SqlQueryEnrichment

import scala.collection.mutable.ListBuffer

//import enrichments.web.{PageEnrichments => WPE}
import outputs.EnrichedEvent
import utils.{IgluUtils, ConversionUtils => CU}
import java.math.{BigDecimal => JBigDecimal}

object EnrichmentManager {

  /**
   * Run the enrichment workflow
   *
   * @param registry     Contain configuration for all enrichments to apply
   * @param client       Iglu Client, for schema lookups and validation
   * @param processor    Meta information about processing asset, for bad rows
   * @param etlTstamp    ETL timestamp
   * @param raw          Canonical input event to enrich
   * @param featureFlags The feature flags available in the current version of Enrich
   * @param invalidCount Function to increment the count of invalid events
   * @return Enriched event or bad row if a problem occured
   */
  def enrichEvent[F[_] : Monad : RegistryLookup : Clock](
                                                          registry: EnrichmentRegistry[F],
                                                          client: Client[F, Json],
                                                          processor: Processor,
                                                          etlTstamp: DateTime,
                                                          raw: RawEvent,
                                                          featureFlags: EtlPipeline.FeatureFlags,
                                                          invalidCount: F[Unit]
                                                        ): F[List[Either[BadRow, EnrichedEvent]]] = {
    (for {
      enriched <- EitherT.fromEither[F](setupEnrichedEvent(raw, etlTstamp, processor))
      inputSDJs <- IgluUtils.extractAndValidateInputJsons(enriched, client, raw, processor)
      (inputContexts, unstructEvent) = inputSDJs
      enrichmentsContexts <- runEnrichments(
        registry,
        processor,
        raw,
        enriched,
        inputContexts,
        unstructEvent
      )
      _ <- EitherT.rightT[F, BadRow] {
        if (enrichmentsContexts.nonEmpty)
          enriched.derived_contexts = ME.formatDerivedContexts(enrichmentsContexts)
      }
      _ <- IgluUtils
        .validateEnrichmentsContexts[F](client, enrichmentsContexts, raw, processor, enriched)
      _ <- EitherT.rightT[F, BadRow](
        anonIp(enriched, registry.anonIp).foreach(enriched.user_ipaddress = _)
      )
      _ <- EitherT.rightT[F, BadRow] {
        piiTransform(enriched, registry.piiPseudonymizer).foreach { pii =>
          enriched.pii = pii.asString
        }
      }
      _ <- validateEnriched(enriched, raw, processor, featureFlags.acceptInvalid, invalidCount)
      generated <- EitherT.rightT[F, BadRow] { generateEvents(enriched) }
      } yield generated).value.map {
        case Left(error) => List(Left(error))
        case Right(events) => events.map(Right.apply)
      }
  }


  def generateEvents(sourceEnrichedEvent: EnrichedEvent): List[EnrichedEvent] = {

      val events = new ListBuffer[EnrichedEvent]()
      events += sourceEnrichedEvent

      // generate new_user event
      if (sourceEnrichedEvent.is_new_unique_id) {
        val newUserEvent = sourceEnrichedEvent.copy()
        newUserEvent.event_vendor = "com.algebraai.gametuner.event"
        newUserEvent.event_name = "new_user"
        newUserEvent.event_format = "jsonschema"
        newUserEvent.event_version = "1-0-0"

        newUserEvent.name_tracker = "EnricherGenerated"
        newUserEvent.v_tracker = ""

        newUserEvent.event_id = java.util.UUID.randomUUID.toString
        newUserEvent.unstruct_event = """{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":{"schema":"iglu:com.algebraai.gametuner.event/new_user/jsonschema/1-0-0","data":null}}"""

        newUserEvent.event_fingerprint = newUserEvent.event_fingerprint + "_gen"
        events += newUserEvent
      }
      events.toList
  }

  /**
   * Run all the enrichments and aggregate the errors if any
   *
   * @param enriched /!\ MUTABLE enriched event, mutated IN-PLACE /!\
   * @return List of contexts to attach to the enriched event if all the enrichments went well
   *         or [[BadRow.EnrichmentFailures]] if something wrong happened
   *         with at least one enrichment
   */
  private def runEnrichments[F[_] : Monad](
                                            registry: EnrichmentRegistry[F],
                                            processor: Processor,
                                            raw: RawEvent,
                                            enriched: EnrichedEvent,
                                            inputContexts: List[SelfDescribingData[Json]],
                                            unstructEvent: Option[SelfDescribingData[Json]]
                                          ): EitherT[F, BadRow.EnrichmentFailures, List[SelfDescribingData[Json]]] =
    EitherT {
      accState(registry, raw, inputContexts, unstructEvent)
        .runS(Accumulation(enriched, Nil, Nil))
        .map {
          case Accumulation(_, failures, contexts) =>
            failures.toNel match {
              case Some(nel) => {
                buildEnrichmentFailuresBadRow(
                  nel,
                  EnrichedEvent.toPartiallyEnrichedEvent(enriched),
                  RawEvent.toRawEvent(raw),
                  processor
                ).asLeft
              }
              case None =>
                contexts.asRight
            }
        }
    }

  private[enrichments] case class Accumulation(
                                                event: EnrichedEvent,
                                                errors: List[FailureDetails.EnrichmentFailure],
                                                contexts: List[SelfDescribingData[Json]]
                                              )

  private[enrichments] type EStateT[F[_], A] = StateT[F, Accumulation, A]

  private object EStateT {
    def apply[F[_] : Applicative, A](f: Accumulation => F[(Accumulation, A)]): EStateT[F, A] =
      StateT(f)

    def fromEither[F[_] : Applicative](
                                        f: (EnrichedEvent,
                                          List[SelfDescribingData[Json]]) => Either[NonEmptyList[FailureDetails.EnrichmentFailure], List[SelfDescribingData[Json]]]
                                      ): EStateT[F, Unit] =
      fromEitherF[F] { case (x, y) => f(x, y).pure[F] }

    def fromEitherF[F[_] : Applicative](
                                         f: (EnrichedEvent,
                                           List[SelfDescribingData[Json]]) => F[Either[NonEmptyList[FailureDetails.EnrichmentFailure], List[SelfDescribingData[Json]]]]
                                       ): EStateT[F, Unit] =
      EStateT {
        case Accumulation(event, errors, contexts) =>
          f(event, contexts).map {
            case Right(contexts2) => (Accumulation(event, errors, contexts2 ::: contexts), ())
            case Left(moreErrors) => (Accumulation(event, moreErrors.toList ::: errors, contexts), ())
          }
      }

    def fromEitherOpt[F[_] : Applicative, A](
                                              f: EnrichedEvent => Either[NonEmptyList[FailureDetails.EnrichmentFailure], Option[A]]
                                            ): EStateT[F, Option[A]] =
      EStateT {
        case acc@Accumulation(event, errors, contexts) =>
          f(event) match {
            case Right(opt) => (acc, opt).pure[F]
            case Left(moreErrors) => (Accumulation(event, moreErrors.toList ::: errors, contexts), Option.empty[A]).pure[F]
          }
      }
  }

  private def accState[F[_] : Monad](
                                      registry: EnrichmentRegistry[F],
                                      raw: RawEvent,
                                      inputContexts: List[SelfDescribingData[Json]],
                                      unstructEvent: Option[SelfDescribingData[Json]]
                                    ): EStateT[F, Unit] = {
    val getCookieContexts = headerContexts[F, CookieExtractorEnrichment](
      raw.context.headers,
      registry.cookieExtractor,
      (e, hs) => e.extract(hs)
    )
    val getHttpHeaderContexts = headerContexts[F, HttpHeaderExtractorEnrichment](
      raw.context.headers,
      registry.httpHeaderExtractor,
      (e, hs) => e.extract(hs)
    )
    val sqlContexts = getSqlQueryContexts[F](inputContexts, unstructEvent, registry.sqlQuery)
    val apiContexts = getApiRequestContexts[F](inputContexts, unstructEvent, registry.apiRequest)

    for {
      // format: off
      _ <- setCustomEventFingerprint[F]()
      _ <- getCollectorVersionSet[F] // The load fails if the collector version is not set
      _ <- validateApiKey[F]
      _ <- getDerivedTstamp[F]
      _ <- getEventTstamp[F]
      _ <- getEventPartitionDate[F]
      _ <- getEventQualityScore[F]
      _ <- getIabContext[F](registry.iab) // Fetch IAB enrichment context (before anonymizing the IP address)
      _ <- getUaParser[F](registry.uaParser) // Create the ua_parser_context
      _ <- getCookieContexts // Execute cookie extractor enrichment
      _ <- getHttpHeaderContexts // Execute header extractor enrichment
      _ <- getWeatherContext[F](registry.weather) // Fetch weather context
      _ <- getYauaaContext[F](registry.yauaa) // Runs YAUAA enrichment (gets info thanks to user agent)
      _ <- extractSchemaFields[F](unstructEvent) // Extract the event vendor/name/format/version
      _ <- geoLocation[F](registry.ipLookups) // Execute IP lookup enrichment
      _ <- getJsScript[F](registry.javascriptScript) // Execute the JavaScript scripting enrichment
      _ <- sqlContexts // Derive some contexts with custom SQL Query enrichment
      _ <- apiContexts // Derive some contexts with custom API Request enrichment
      _ <- getCurrency[F](registry.currencyConversion,"price_currency", "price", "price_usd")
      _ <- getCurrency[F](registry.currencyConversion,"paid_currency", "paid_amount", "paid_usd")
      _ <- resolveUniqueId[F](registry.uniqueIdResolver)

      // format: on
    } yield ()
  }

  /** Create the mutable [[EnrichedEvent]] and initialize it. */
  private def setupEnrichedEvent(
                                  raw: RawEvent,
                                  etlTstamp: DateTime,
                                  processor: Processor
                                ): Either[BadRow.EnrichmentFailures, EnrichedEvent] = {
    val e = new EnrichedEvent()
    e.event_id = EE.generateEventId() // May be updated later if we have an `eid` parameter
    e.v_collector = raw.source.name // May be updated later if we have a `cv` parameter
    e.v_etl = ME.etlVersion(processor)
    e.enricher_tstamp = EE.toTimestamp(etlTstamp)
    e.network_userid = raw.context.userId.map(_.toString).orNull // May be updated later by 'nuid'
    e.user_ipaddress = ME
      .extractIp("user_ipaddress", raw.context.ipAddress.orNull)
      .toOption
      .orNull // May be updated later by 'ip'
    // May be updated later if we have a `ua` parameter
    val useragent = setUseragent(e, raw.context.useragent, raw.source.encoding).toValidatedNel
    // Validate that the collectorTstamp exists and is Redshift-compatible
    val collectorTstamp = setCollectorTstamp(e, raw.context.timestamp).toValidatedNel
    // Map/validate/transform input fields to enriched event fields
    val transformed = Transform.transform(raw, e)

    (useragent |+| collectorTstamp |+| transformed)
      .leftMap { enrichmentFailures =>
        EnrichmentManager.buildEnrichmentFailuresBadRow(
          enrichmentFailures,
          EnrichedEvent.toPartiallyEnrichedEvent(e),
          RawEvent.toRawEvent(raw),
          processor
        )
      }
      .as(e)
      .toEither
  }

  def setCollectorTstamp(event: EnrichedEvent, timestamp: Option[DateTime]): Either[FailureDetails.EnrichmentFailure, Unit] =
    EE.formatCollectorTstamp(timestamp).map { t =>
      event.collector_tstamp = t
      ().asRight
    }

  def setUseragent(
                    event: EnrichedEvent,
                    useragent: Option[String],
                    encoding: String
                  ): Either[FailureDetails.EnrichmentFailure, Unit] =
    useragent match {
      case Some(ua) =>
        CU.decodeString(Charset.forName(encoding), ua)
          .map { ua =>
            event.useragent = ua
          }
          .leftMap(f =>
            FailureDetails.EnrichmentFailure(
              None,
              FailureDetails.EnrichmentFailureMessage.Simple(f)
            )
          )
      case None => ().asRight // No fields updated
    }

  // The load fails if the collector version is not set
  def getCollectorVersionSet[F[_] : Applicative]: EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        event.v_collector match {
          case "" | null =>
            NonEmptyList
              .one(
                FailureDetails
                  .EnrichmentFailure(
                    None,
                    FailureDetails.EnrichmentFailureMessage
                      .InputData("v_collector", None, "should be set")
                  )
              )
              .asLeft
          case _ => Nil.asRight
        }
    }

  // If our IpToGeo enrichment is enabled, get the geo-location from the IP address
  // enrichment doesn't fail to maintain the previous approach where failures were suppressed
  // c.f. https://github.com/snowplow/snowplow/issues/351
  def geoLocation[F[_] : Monad](ipLookups: Option[IpLookupsEnrichment[F]]): EStateT[F, Unit] =
    EStateT.fromEitherF {
      case (event, _) =>
        var ipAddress = event.user_ipaddress
        if (event.geo_country != null || event.geo_country_name != null) {
          // if geo_country or geo_country_name was already set, disable geo_ip
          ipAddress = null
        }
        val ipLookup = for {
          enrichment <- OptionT.fromOption[F](ipLookups)
          ip <- OptionT.fromOption[F](Option(ipAddress))
          result <- OptionT.liftF(enrichment.extractIpInformation(ip))
        } yield result

        ipLookup.value.map {
          case Some(lookup) =>
            lookup.ipLocation.flatMap(_.toOption).foreach { loc =>
              event.geo_country = loc.countryCode
              event.geo_country_name = loc.countryName
              event.geo_region = loc.region.orNull
              event.geo_city = loc.city.orNull
              event.geo_zipcode = loc.postalCode.orNull
              event.geo_latitude = loc.latitude
              event.geo_longitude = loc.longitude
              event.geo_region_name = loc.regionName.orNull
              event.geo_timezone = loc.timezone.orNull
            }
            Nil.asRight
          case None =>
            Nil.asRight
        }
    }

  // Calculate the derived timestamp
  def getDerivedTstamp[F[_] : Applicative]: EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        EE.getDerivedTimestamp(
          Option(event.dvce_sent_tstamp),
          Option(event.dvce_created_tstamp),
          Option(event.collector_tstamp),
          Option(event.true_tstamp),
          event.backfill_mode == "1",
        ).map { dt =>
          dt.foreach(event.derived_tstamp = _)
          Nil
        }.leftMap(NonEmptyList.one(_))
    }

  def getEventTstamp[F[_] : Applicative]: EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        MetadataClient.getAppIdData(event.app_id) match {
          case Some(appId) =>
            EE.getEventTimestamp(
              appId.timezone,
              appId.closePartitionAfterHours,
              event.collector_tstamp,
              event.derived_tstamp,
              event.backfill_mode == "1"
            ).map { dt =>
              event.event_tstamp = dt
              Nil
            }.leftMap(NonEmptyList.one(_))
          case _ =>
            NonEmptyList.one(FailureDetails.EnrichmentFailure(None,
              FailureDetails.EnrichmentFailureMessage
                .InputData("app_id", Option(event.app_id), "not found in metadata client cache")
            )).asLeft
        }
    }

  def getEventPartitionDate[F[_] : Applicative]: EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        MetadataClient.getAppIdData(event.app_id) match {
          case Some(appId) =>
            EE.getEventPartitionDate(
              appId.timezone,
              event.event_tstamp
            ).map { dt =>
              event.date_ = dt
              Nil
            }.leftMap(NonEmptyList.one(_))
          case _ =>
            NonEmptyList.one(FailureDetails.EnrichmentFailure(None,
              FailureDetails.EnrichmentFailureMessage
                .InputData("app_id", Option(event.app_id), "not found in metadata client cache")
            )).asLeft
        }
    }

  def getEventQualityScore[F[_] : Applicative]: EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        MetadataClient.getAppIdData(event.app_id) match {
          case Some(appId) =>
            EE.getEventQualityScore(
              appId.timezone,
              appId.closePartitionAfterHours,
              event.derived_tstamp,
              event.collector_tstamp,
              event.dvce_created_tstamp,
              event.dvce_sent_tstamp,
              event.true_tstamp,
              event.backfill_mode == "1"
            ).map { dt =>
              event.event_quality = dt
              Nil
            }.leftMap(NonEmptyList.one(_))
          case _ =>
            NonEmptyList.one(FailureDetails.EnrichmentFailure(None,
              FailureDetails.EnrichmentFailureMessage
                .InputData("app_id", Option(event.app_id), "not found in metadata client cache")
            )).asLeft
        }
    }

  // Fetch IAB enrichment context (before anonymizing the IP address).
  // IAB enrichment is called only if the IP is v4 (and after removing the port if any)
  // and if the user agent is defined.
  def getIabContext[F[_] : Applicative](iabEnrichment: Option[IabEnrichment]): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        val result = for {
          iab <- iabEnrichment
          useragent <- Option(event.useragent).filter(_.trim.nonEmpty)
          ipString <- Option(event.user_ipaddress)
          ip <- CU.extractInetAddress(ipString)
          tstamp <- Option(event.derived_tstamp).map(EventEnrichments.fromTimestamp)
        } yield iab.getIabContext(useragent, ip, tstamp)

        result.sequence.bimap(NonEmptyList.one(_), _.toList)
    }

  def anonIp(event: EnrichedEvent, anonIp: Option[AnonIpEnrichment]): Option[String] =
    Option(event.user_ipaddress).map { ip =>
      anonIp match {
        case Some(anon) => anon.anonymizeIp(ip)
        case None => ip
      }
    }

  def getUaParser[F[_] : Applicative](uaParser: Option[UaParserEnrichment]): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        uaParser match {
          case Some(uap) =>
            Option(event.useragent) match {
              case Some(ua) => uap.extractUserAgent(ua).bimap(NonEmptyList.one(_), List(_))
              case None => Nil.asRight // No fields updated
            }
          case None => Nil.asRight
        }
    }

  def getCurrency[F[_] : Monad](currencyConversion: Option[CurrencyConversionEnrichment[F]],
                                currencyFieldName: String,
                                amountFieldName: String,
                                resultFieldName: String
                               ): EStateT[F, Unit] =
    EStateT.fromEitherF {
      case (event, _) =>
        currencyConversion match {
          case Some(currency) =>
            val doc: Json = parser.parse(event.unstruct_event).getOrElse(Json.Null)
            val cursor: HCursor = doc.hcursor

            val schema = SchemaKey.fromUri(cursor
              .downField("data").downField("schema").as[String].toOption.get).toOption.get

            val currencyOpt = cursor
              .downField("data").downField("data")
              .downField(currencyFieldName).as[String].toOption
            val amountOpt = cursor
              .downField("data").downField("data")
              .downField(amountFieldName).as[Double].toOption
            val resultOpt = cursor
              .downField("data").downField("data")
              .downField(resultFieldName).as[Double].toOption
              .flatMap(x => if (x > 0) Some(x) else None)

            if (schema.name == "purchase" && currencyOpt.isDefined && amountOpt.isDefined && resultOpt.isEmpty) {

              val amount = CU.jBigDecimalToDouble(amountFieldName, new JBigDecimal(amountOpt.get)).toValidatedNel

              EitherT(
                amount.map {
                  currency.convertCurrencies(
                    currencyOpt,
                    _,
                    Some(EE.fromTimestamp(event.event_tstamp))
                  )
                }
                  .toEither
                  .sequence
                  .map(_.flatMap(_.toEither))
              )
                .map {
                  case convertedCurrency =>
                    convertedCurrency.foreach(v =>
                      event.unstruct_event = cursor
                        .downField("data").downField("data")
                        .withFocus(_.mapObject(x => x
                          .+:((resultFieldName, Json.fromBigDecimal(v)))))
                        .top.map(_.noSpaces).get)
                    List.empty[SelfDescribingData[Json]]
                }
                .fold(
                  _ => Nil.asRight,
                  _ => Nil.asRight
                )
            } else {
              Monad[F].pure(Nil.asRight)
            }
          case None => Monad[F].pure(Nil.asRight)
        }
    }

  def setCustomEventFingerprint[F[_]: Applicative](): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        event.event_fingerprint = EE.calculateCustomEventFingerprint(event)
        Nil.asRight
    }

  // Extract the event vendor/name/format/version
  def extractSchemaFields[F[_]: Applicative](
    unstructEvent: Option[SelfDescribingData[Json]]
  ): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        SchemaEnrichment
          .extractSchema(event, unstructEvent)
          .map {
            case Some(schemaKey) =>
              event.event_vendor = schemaKey.vendor
              event.event_name = schemaKey.name
              event.event_format = schemaKey.format
              event.event_version = schemaKey.version.asString
              Nil
            case None => Nil
          }
          .leftMap(NonEmptyList.one(_))
    }

  // Execute the JavaScript scripting enrichment
  def getJsScript[F[_]: Applicative](
    javascriptScript: Option[JavascriptScriptEnrichment]
  ): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        javascriptScript match {
          case Some(jse) => jse.process(event).leftMap(NonEmptyList.one(_))
          case None => Nil.asRight
        }
    }

  def headerContexts[F[_]: Applicative, A](
    headers: List[String],
    enrichment: Option[A],
    f: (A, List[String]) => List[SelfDescribingData[Json]]
  ): EStateT[F, Unit] =
    EStateT.fromEither[F] {
      case (_, _) =>
        enrichment match {
          case Some(e) => f(e, headers).asRight
          case None => Nil.asRight
        }
    }

  // Fetch weather context
  def getWeatherContext[F[_]: Monad](weather: Option[WeatherEnrichment[F]]): EStateT[F, Unit] =
    EStateT.fromEitherF {
      case (event, _) =>
        weather match {
          case Some(we) =>
            we.getWeatherContext(
              Option(event.geo_latitude),
              Option(event.geo_longitude),
              Option(event.derived_tstamp).map(EventEnrichments.fromTimestamp)
            ).map(_.map(List(_)))
          case None => Monad[F].pure(Nil.asRight)
        }
    }

  def getYauaaContext[F[_]: Applicative](yauaa: Option[YauaaEnrichment]): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        yauaa.map(_.getYauaaContext(event.useragent)).toList.asRight
    }

  // Derive some contexts with custom SQL Query enrichment
  def getSqlQueryContexts[F[_]: Monad](
    inputContexts: List[SelfDescribingData[Json]],
    unstructEvent: Option[SelfDescribingData[Json]],
    sqlQuery: Option[SqlQueryEnrichment[F]]
  ): EStateT[F, Unit] =
    EStateT.fromEitherF {
      case (event, derivedContexts) =>
        sqlQuery match {
          case Some(enrichment) =>
            enrichment.lookup(event, derivedContexts, inputContexts, unstructEvent).map(_.toEither)
          case None =>
            List.empty[SelfDescribingData[Json]].asRight.pure[F]
        }
    }

  // Derive some contexts with custom API Request enrichment
  def getApiRequestContexts[F[_]: Monad](
    inputContexts: List[SelfDescribingData[Json]],
    unstructEvent: Option[SelfDescribingData[Json]],
    apiRequest: Option[ApiRequestEnrichment[F]]
  ): EStateT[F, Unit] =
    EStateT.fromEitherF {
      case (event, derivedContexts) =>
        apiRequest match {
          case Some(enrichment) =>
            enrichment.lookup(event, derivedContexts, inputContexts, unstructEvent).map(_.toEither)
          case None =>
            List.empty[SelfDescribingData[Json]].asRight.pure[F]
        }
    }

  def piiTransform(event: EnrichedEvent, piiPseudonymizer: Option[PiiPseudonymizerEnrichment]): Option[SelfDescribingData[Json]] =
    piiPseudonymizer.flatMap(_.transformer(event))

  /** Build `BadRow.EnrichmentFailures` from a list of `FailureDetails.EnrichmentFailure`s */
  def buildEnrichmentFailuresBadRow(
    fs: NonEmptyList[FailureDetails.EnrichmentFailure],
    pee: Payload.PartiallyEnrichedEvent,
    re: Payload.RawEvent,
    processor: Processor
  ) =
    BadRow.EnrichmentFailures(
      processor,
      Failure.EnrichmentFailures(Instant.now(), fs),
      Payload.EnrichmentPayload(pee, re)
    )

  /**
   * Validates enriched events against atomic schema.
   * For now it's possible to accept enriched events that are not valid.
   * See https://github.com/snowplow/enrich/issues/517#issuecomment-1033910690
   */
  private def validateEnriched[F[_]: Clock: Monad: RegistryLookup](
    enriched: EnrichedEvent,
    raw: RawEvent,
    processor: Processor,
    acceptInvalid: Boolean,
    invalidCount: F[Unit]
  ): EitherT[F, BadRow, Unit] =
    EitherT {

      //We're using static field's length validation. See more in https://github.com/snowplow/enrich/issues/608
      AtomicFieldsLengthValidator.validate[F](enriched, raw, processor, acceptInvalid, invalidCount)
    }

  def validateApiKey[F[_] : Applicative]: EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        MetadataClient.getAppIdData(event.app_id) match {
          case Some(appId) =>
            if (event.api_key == appId.apiKey) {
              Nil.asRight
            } else {
              NonEmptyList.one(FailureDetails.EnrichmentFailure(None,
                FailureDetails.EnrichmentFailureMessage
                  .InputData("api_key", None, "not valid"))
              ).asLeft
            }
            case _ =>
              NonEmptyList.one(FailureDetails.EnrichmentFailure(None,
                FailureDetails.EnrichmentFailureMessage
                  .InputData("app_id", Option(event.app_id), "not found in metadata client cache")
              )).asLeft
        }
    }

  def resolveUniqueId[F[_] : Monad](uniqueIdEnrichment: Option[UniqueIdEnrichment[F]]): EStateT[F, Unit] = {
    EStateT.fromEitherF {
      case (event, _) =>
        uniqueIdEnrichment match {
          case Some(enrichment) => {
            val uniqueId = enrichment.resolveUniqueId(event.app_id,
              Option(event.installation_id), Option(event.user_id))

            uniqueId.map {
              case Left(error) => NonEmptyList.one(FailureDetails.EnrichmentFailure(
                None,
                FailureDetails.EnrichmentFailureMessage
                  .InputData("unique id", error.some, "should be resolved"))).asLeft
              case Right(uniqueId) => {
                event.unique_id = uniqueId.uniqueId
                event.is_new_unique_id = uniqueId.isNewUser
                Nil.asRight
              }
            }
          }
          case _ => Monad[F].pure(Nil.asRight)
        }
    }
  }
}

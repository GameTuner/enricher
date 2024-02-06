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
package com.snowplowanalytics.snowplow.enrich.common.enrichments.registry

import java.time.ZonedDateTime
import java.io.{PrintWriter, StringWriter}
import java.math.BigDecimal
import cats.Monad
import cats.data.{EitherT, NonEmptyList, ValidatedNel}
import cats.implicits._
import io.opencensus.stats.{Aggregation, Stats, View}
import io.circe._
import com.snowplowanalytics.forex.{CreateForex, Forex}
import com.snowplowanalytics.forex.model._
import com.snowplowanalytics.forex.errors.OerResponseError
import org.joda.money.CurrencyUnit
import org.joda.time.DateTime
import com.snowplowanalytics.iglu.core.{SchemaCriterion, SchemaKey}
import com.snowplowanalytics.snowplow.badrows.FailureDetails
import com.snowplowanalytics.snowplow.enrich.common.utils.CirceUtils
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf.CurrencyConversionConf
import io.opencensus.stats.Measure.MeasureLong
import io.opencensus.stats.View.Name
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.Collections
import java.util.concurrent.{Executors, TimeUnit}

/** Companion object. Lets us create an CurrencyConversionEnrichment instance from a Json. */
object CurrencyConversionEnrichment extends ParseableEnrichment {

  override val supportedSchema =
    SchemaCriterion(
      "com.snowplowanalytics.snowplow",
      "currency_conversion_config",
      "jsonschema",
      1,
      0
    )

  // Creates a CurrencyConversionConf from a Json
  override def parse(
    c: Json,
    schemaKey: SchemaKey,
    localMode: Boolean = false
  ): ValidatedNel[String, CurrencyConversionConf] =
    isParseable(c, schemaKey)
      .leftMap(e => NonEmptyList.one(e))
      .flatMap { _ =>
        (
          CirceUtils.extract[String](c, "parameters", "apiKey").toValidatedNel,
          CirceUtils
            .extract[String](c, "parameters", "baseCurrency")
            .toEither
            .flatMap(bc => Either.catchNonFatal(CurrencyUnit.of(bc)).leftMap(_.getMessage))
            .toValidatedNel,
          CirceUtils
            .extract[String](c, "parameters", "accountType")
            .toEither
            .flatMap {
              case "DEVELOPER" => DeveloperAccount.asRight
              case "ENTERPRISE" => EnterpriseAccount.asRight
              case "UNLIMITED" => UnlimitedAccount.asRight
              // Should never happen (prevented by schema validation)
              case s =>
                s"accountType [$s] is not one of DEVELOPER, ENTERPRISE, and UNLIMITED".asLeft
            }
            .toValidatedNel
        ).mapN { (apiKey, baseCurrency, accountType) =>
          CurrencyConversionConf(schemaKey, accountType, apiKey, baseCurrency)
        }.toEither
      }
      .toValidated

  /**
   * Creates a CurrencyConversionEnrichment from a CurrencyConversionConf
   * @param conf Configuration for the currency conversion enrichment
   * @return a currency conversion enrichment
   */
  def apply[F[_]: Monad: CreateForex](conf: CurrencyConversionConf): F[CurrencyConversionEnrichment[F]] =
    CreateForex[F]
      .create(
        ForexConfig(conf.apiKey, conf.accountType, baseCurrency = conf.baseCurrency)
      )
      .map(f => CurrencyConversionEnrichment(conf.schemaKey, f, conf.baseCurrency))
}

/**
 * Currency conversion enrichment
 * @param forex Forex client
 * @param baseCurrency the base currency to refer to
 */
final case class CurrencyConversionEnrichment[F[_]: Monad](
  schemaKey: SchemaKey,
  forex: Forex[F],
  baseCurrency: CurrencyUnit
) extends Enrichment {
  private val enrichmentInfo =
    FailureDetails.EnrichmentInformation(schemaKey, "currency-conversion").some

  private val logger = LoggerFactory.getLogger(getClass)

  private val apiQuotaMesaure = MeasureLong.create(
    "gametuner_enricher/currency_api_quota_left",
    "Currency api quota left", "1")

  private val view: View = View.create(
              Name.create("gametuner_enricher/currency_api_quota_left"),
              "Currency api quota left",
              apiQuotaMesaure,
              Aggregation.LastValue.create(),
              Collections.emptyList());

  private val executor = Executors.newSingleThreadScheduledExecutor()
  val client: HttpClient = HttpClient.newHttpClient

  Stats.getViewManager.registerView(view)
  startMetricsRecorder()

  def startMetricsRecorder() = {
    executor.scheduleAtFixedRate(() => recordMetrics(),
      1, 1, TimeUnit.MINUTES)
    ()
  }

  def recordMetrics(): Unit = {
    try {
      val request = HttpRequest.newBuilder()
        .uri(URI.create("https://openexchangerates.org/api/usage.json?app_id=" + forex.config.appId))
        .version(HttpClient.Version.HTTP_1_1)
        .GET()
        .build()

      val response = client.send(request, HttpResponse.BodyHandlers.ofString())

      if (response.statusCode() == 200) {
        val doc: Json = parser.parse(response.body()).getOrElse(Json.Null)
        val cursor: HCursor = doc.hcursor
        val requestsRemaining = cursor
          .downField("data").downField("usage").downField("requests_remaining").as[Long].right.get
        Stats.getStatsRecorder.newMeasureMap().put(apiQuotaMesaure, requestsRemaining).record();
      } else {
        throw new IllegalStateException("Received status code " + response.statusCode() + " from metadata service")
      }
    } catch {
      case e: Exception => logger.warn("Failed read currency quota!", e)
    }
  }

  /**
   * Attempt to convert if the initial currency and value are both defined
   * @param initialCurrency Option boxing the initial currency if it is present
   * @param value Option boxing the amount to convert
   * @return None.success if the inputs were not both defined,
   * otherwise `Validation[Option[_]]` boxing the result of the conversion
   */
  private def performConversion(
    initialCurrency: Option[Either[FailureDetails.EnrichmentFailure, CurrencyUnit]],
    value: Option[Double],
    tstamp: ZonedDateTime
  ): F[Either[FailureDetails.EnrichmentFailure, Option[BigDecimal]]] =
    (initialCurrency, value) match {
      case (Some(ic), Some(v)) =>
        (for {
          cu <- EitherT.fromEither[F](ic)
          money <- EitherT.fromEither[F](
                     Either
                       .catchNonFatal(forex.convert(v, cu).to(baseCurrency).at(tstamp))
                       .leftMap(t => mkEnrichmentFailure(Left(t)))
                   )
          res <- EitherT(
                   money.map(
                     _.bimap(
                       l => mkEnrichmentFailure(Right(l)),
                       r =>
                         Either.catchNonFatal(r.getAmount()) match {
                           case Left(e) =>
                             Left(mkEnrichmentFailure(Left(e)))
                           case Right(a) =>
                             Right(a.some)
                         }
                     ).flatten
                   )
                 )
        } yield res).value
      case _ => Monad[F].pure(None.asRight)
    }

  def convertCurrencies(
    currency: Option[String],
    amount: Option[Double],
    currencyTstamp: Option[DateTime]
  ): F[ValidatedNel[FailureDetails.EnrichmentFailure, Option[BigDecimal]]] =
    currencyTstamp match {
      case Some(tstamp) =>
        val zdt = tstamp.toGregorianCalendar().toZonedDateTime()
        val paidCurrencyCu = currency.map { c =>
          Either
            .catchNonFatal(CurrencyUnit.of(c))
            .leftMap { e =>
              val f = FailureDetails.EnrichmentFailureMessage.InputData(
                "currency",
                currency,
                e.getMessage
              )
              FailureDetails.EnrichmentFailure(enrichmentInfo, f)
            }
        }

        performConversion(paidCurrencyCu, amount, zdt)
          .map(paidAmountUsd => paidAmountUsd.toValidatedNel)

      // This should never happen
      case None =>
        val f = FailureDetails.EnrichmentFailureMessage.InputData(
          "currency timestamp",
          None,
          "missing"
        )
        Monad[F].pure(FailureDetails.EnrichmentFailure(enrichmentInfo, f).invalidNel)
    }

  // The original error is either a Throwable or an OER error
  private def mkEnrichmentFailure(error: Either[Throwable, OerResponseError]): FailureDetails.EnrichmentFailure = {
    val msg = error match {
      case Left(t) =>
        val sw = new StringWriter
        t.printStackTrace(new PrintWriter(sw))
        s"an error happened while converting the currency: ${t.getMessage}\n${sw.toString}".substring(0, 511)
      case Right(e) =>
        val errorType = e.errorType.getClass.getSimpleName.replace("$", "")
        s"Open Exchange Rates error, type: [$errorType], message: [${e.errorMessage}]"
    }
    val f =
      FailureDetails.EnrichmentFailureMessage.Simple(msg)
    FailureDetails
      .EnrichmentFailure(enrichmentInfo, f)
  }
}

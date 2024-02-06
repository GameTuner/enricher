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
package com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.uniqueid

import cats.Functor
import cats.implicits.toFunctorOps
import com.snowplowanalytics.iglu.core.{SchemaCriterion, SchemaKey}
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf.UniqueIdResolverConf
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.{Enrichment, ParseableEnrichment}
import com.snowplowanalytics.snowplow.enrich.common.utils.{BlockerF, CirceUtils}
import io.circe._
import cats.data.ValidatedNel
import cats.implicits._

/** Companion object. Lets us create an UniqueIdEnrichment instance from a Json. */
object UniqueIdEnrichment extends ParseableEnrichment {
  override val supportedSchema =
    SchemaCriterion("com.algebraai.gametuner.enricher", "unique_id_resolver", "jsonschema", 1, 0)

  def apply[F[_] : Functor : CreateUniqueIdWrapper](conf: UniqueIdResolverConf, blocker: BlockerF[F]): F[UniqueIdEnrichment[F]] =
    CreateUniqueIdWrapper[F]
      .create(conf.username, conf.password, conf.database, conf.connectionName, conf.maximumElementsInCache, conf.expireCacheAfterMinutes)
      .map(i => UniqueIdEnrichment(i, blocker))

  /**
   * Creates an UniqueIdEnrichmentConf from a Json.
   *
   * @param config    The enrichment JSON
   * @param schemaKey provided for the enrichment, must be supported  by this enrichment
   * @return a UniqueIdEnrichment configuration
   */
  override def parse(
    config: Json,
    schemaKey: SchemaKey,
    localMode: Boolean = false
  ): ValidatedNel[String, UniqueIdResolverConf] = {
    (for {
      _ <- isParseable(config, schemaKey)
      username <- CirceUtils.extract[String](config, "parameters", "username").toEither
      password <- CirceUtils.extract[String](config, "parameters", "password").toEither
      database <- CirceUtils.extract[String](config, "parameters", "database").toEither
      connectionName <- CirceUtils.extract[String](config, "parameters", "connectionName").toEither
      maximumElementsInCache <- CirceUtils.extract[Int](config, "parameters", "maximumElementsInCache").toEither
      expireCacheAfterMinutes <- CirceUtils.extract[Int](config, "parameters", "expireCacheAfterMinutes").toEither
    } yield UniqueIdResolverConf(
      schemaKey,
      username,
      password,
      database,
      connectionName,
      maximumElementsInCache.longValue(),
      expireCacheAfterMinutes.longValue())).toValidatedNel
  }
}



final case class UniqueIdEnrichment[F[_]](uniqueIdResolver: UniqueIdWrapper[F], blocker: BlockerF[F]) extends Enrichment {
  def resolveUniqueId(appId: String, installationId: Option[String], userId: Option[String]): F[Either[String, UniqueIdResult]] = {
    blocker.blockOn {
      uniqueIdResolver.setUniqueID(appId, installationId, userId)
    }
  }
}
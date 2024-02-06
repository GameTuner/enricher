/*
 * Copyright (c) 2012-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use enriched file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.common.outputs

//import java.lang.{Integer => JInteger}
import java.lang.{Float => JFloat}
//import java.lang.{Byte => JByte}
//import java.math.{BigDecimal => JBigDecimal}
import java.time.format.DateTimeFormatter

import scala.beans.BeanProperty

import cats.implicits._

import io.circe.Json

import com.snowplowanalytics.snowplow.badrows.Payload.PartiallyEnrichedEvent

/**
 * The canonical output format for enriched events.
 *
 * For simplicity, we are using our Redshift format
 * as the canonical format, i.e. the below is
 * equivalent to the redshift-etl.q HiveQL script
 * used by the Hive ETL.
 *
 * When we move to Avro, we will
 * probably review some of these
 * types (e.g. move back to
 * Array for browser features, and
 * switch remaining Bytes to Booleans).
 */
// TODO: make the EnrichedEvent Avro-format, not Redshift-specific
class EnrichedEvent extends Serializable {

  def copy(): EnrichedEvent = {
    val newEvent = new EnrichedEvent()
    newEvent.api_key = api_key
    newEvent.app_id = app_id
    newEvent.platform = platform
    newEvent.enricher_tstamp = enricher_tstamp
    newEvent.collector_tstamp = collector_tstamp
    newEvent.dvce_created_tstamp = dvce_created_tstamp
    newEvent.event = event
    newEvent.event_id = event_id
    newEvent.name_tracker = name_tracker
    newEvent.v_tracker = v_tracker
    newEvent.v_collector = v_collector
    newEvent.v_etl = v_etl
    newEvent.user_id = user_id
    newEvent.installation_id = installation_id
    newEvent.unique_id = unique_id
    newEvent.is_new_unique_id = is_new_unique_id
    newEvent.user_ipaddress = user_ipaddress
    newEvent.network_userid = network_userid
    newEvent.geo_country = geo_country
    newEvent.geo_country_name = geo_country_name
    newEvent.geo_region = geo_region
    newEvent.geo_city = geo_city
    newEvent.geo_zipcode = geo_zipcode
    newEvent.geo_latitude = geo_latitude
    newEvent.geo_longitude = geo_longitude
    newEvent.geo_region_name = geo_region_name
    newEvent.mkt_medium = mkt_medium
    newEvent.mkt_source = mkt_source
    newEvent.mkt_term = mkt_term
    newEvent.mkt_content = mkt_content
    newEvent.mkt_campaign = mkt_campaign
    newEvent.contexts = contexts
    newEvent.unstruct_event = unstruct_event
    newEvent.useragent = useragent
    newEvent.geo_timezone = geo_timezone
    newEvent.etl_tags = etl_tags
    newEvent.dvce_sent_tstamp = dvce_sent_tstamp
    newEvent.derived_contexts = derived_contexts
    newEvent.derived_tstamp = derived_tstamp
    newEvent.event_vendor = event_vendor
    newEvent.event_name = event_name
    newEvent.event_format = event_format
    newEvent.event_version = event_version
    newEvent.event_fingerprint = event_fingerprint
    newEvent.true_tstamp = true_tstamp
    newEvent.pii = pii
    newEvent.event_tstamp = event_tstamp
    newEvent.event_quality = event_quality
    newEvent.sandbox_mode = sandbox_mode
    newEvent.backfill_mode = backfill_mode
    newEvent.date_ = date_
    newEvent
  }

  // The application (site, game, app etc) enriched event belongs to, and the tracker platform
  @BeanProperty var api_key: String = _
  @BeanProperty var app_id: String = _
  @BeanProperty var platform: String = _

  // Date/time
  @BeanProperty var enricher_tstamp: String = _
  @BeanProperty var collector_tstamp: String = _
  @BeanProperty var dvce_created_tstamp: String = _

  // Transaction (i.e. enriched logging event)
  @BeanProperty var event: String = _
  @BeanProperty var event_id: String = _

  // Versioning
  @BeanProperty var name_tracker: String = _
  @BeanProperty var v_tracker: String = _
  @BeanProperty var v_collector: String = _
  @BeanProperty var v_etl: String = _

  // User and visit
  @BeanProperty var user_id: String = _
  @BeanProperty var installation_id: String = _
  @BeanProperty var unique_id: String = _
  @BeanProperty var user_ipaddress: String = _
  @BeanProperty var network_userid: String = _

  // Location
  @BeanProperty var geo_country: String = _
  @BeanProperty var geo_country_name: String = _
  @BeanProperty var geo_region: String = _
  @BeanProperty var geo_city: String = _
  @BeanProperty var geo_zipcode: String = _
  @BeanProperty var geo_latitude: JFloat = _
  @BeanProperty var geo_longitude: JFloat = _
  @BeanProperty var geo_region_name: String = _

  // Marketing
  @BeanProperty var mkt_medium: String = _
  @BeanProperty var mkt_source: String = _
  @BeanProperty var mkt_term: String = _
  @BeanProperty var mkt_content: String = _
  @BeanProperty var mkt_campaign: String = _

  // Custom Contexts
  @BeanProperty var contexts: String = _

  // Unstructured Event
  @BeanProperty var unstruct_event: String = _

  // User Agent
  @BeanProperty var useragent: String = _

  // Geolocation
  @BeanProperty var geo_timezone: String = _

  // ETL tags
  @BeanProperty var etl_tags: String = _

  // Time event was sent
  @BeanProperty var dvce_sent_tstamp: String = _

  // Derived contexts
  @BeanProperty var derived_contexts: String = _

  // Derived timestamp
  @BeanProperty var derived_tstamp: String = _

  // Derived event vendor/name/format/version
  @BeanProperty var event_vendor: String = _
  @BeanProperty var event_name: String = _
  @BeanProperty var event_format: String = _
  @BeanProperty var event_version: String = _

  // Event fingerprint
  @BeanProperty var event_fingerprint: String = _

  // True timestamp
  @BeanProperty var true_tstamp: String = _

  // Fields modified in PII enrichemnt (JSON String)
  @BeanProperty var pii: String = _

  @BeanProperty var event_tstamp: String = _

  @BeanProperty var event_quality: Int = _

  @BeanProperty var sandbox_mode: String = _

  @BeanProperty var backfill_mode: String = _

  @BeanProperty var `date_`: String = _

  // non serialized fields used by enricher
  @transient
  var is_new_unique_id: Boolean = _
  is_new_unique_id = false
}

object EnrichedEvent {

  private val JsonSchemaDateTimeFormat =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

  private def toKv[T](
    k: String,
    v: T,
    f: T => Json
  ): Option[(String, Json)] =
    Option(v).map(value => (k, f(value)))

  private def toKv(k: String, s: String): Option[(String, Json)] = toKv(k, s, Json.fromString)
  //private def toKv(k: String, i: JInteger): Option[(String, Json)] = toKv(k, i, (jInt: JInteger) => Json.fromInt(jInt))
  private def toKv(k: String, f: JFloat): Option[(String, Json)] = toKv(k, f, (jFloat: JFloat) => Json.fromFloatOrNull(jFloat))
  //private def toKv(k: String, b: JByte): Option[(String, Json)] = toKv(k, b, (jByte: JByte) => Json.fromBoolean(jByte != 0))
  //private def toKv(k: String, b: JBigDecimal): Option[(String, Json)] = toKv(k, b, (jNum: JBigDecimal) => Json.fromBigDecimal(jNum))
  private def toDateKv(k: String, s: String): Option[(String, Json)] =
    toKv(
      k,
      s,
      (s: String) => Json.fromString(DateTimeFormatter.ISO_DATE_TIME.format(JsonSchemaDateTimeFormat.parse(s)))
    )

  def toAtomic(enriched: EnrichedEvent): Either[Throwable, Json] =
    Either.catchNonFatal(
      Json.fromFields(
          toKv("api_key", enriched.api_key) ++
          toKv("app_id", enriched.app_id) ++
          toKv("platform", enriched.platform) ++
          toDateKv("enricher_tstamp", enriched.enricher_tstamp) ++
          toDateKv("collector_tstamp", enriched.collector_tstamp) ++
          toDateKv("dvce_created_tstamp", enriched.dvce_created_tstamp) ++
          toKv("event", enriched.event) ++
          toKv("event_id", enriched.event_id) ++
          toKv("name_tracker", enriched.name_tracker) ++
          toKv("v_tracker", enriched.v_tracker) ++
          toKv("v_collector", enriched.v_collector) ++
          toKv("v_etl", enriched.v_etl) ++
          toKv("user_id", enriched.user_id) ++
          toKv("unique_id", enriched.unique_id) ++
          toKv("installation_id", enriched.installation_id) ++
          toKv("user_ipaddress", enriched.user_ipaddress) ++
          toKv("network_userid", enriched.network_userid) ++
          toKv("geo_country", enriched.geo_country) ++
          toKv("geo_country_name", enriched.geo_country_name) ++
          toKv("geo_region", enriched.geo_region) ++
          toKv("geo_city", enriched.geo_city) ++
          toKv("geo_zipcode", enriched.geo_zipcode) ++
          toKv("geo_latitude", enriched.geo_latitude) ++
          toKv("geo_longitude", enriched.geo_longitude) ++
          toKv("geo_region_name", enriched.geo_region_name) ++
          toKv("mkt_medium", enriched.mkt_medium) ++
          toKv("mkt_source", enriched.mkt_source) ++
          toKv("mkt_term", enriched.mkt_term) ++
          toKv("mkt_content", enriched.mkt_content) ++
          toKv("mkt_campaign", enriched.mkt_campaign) ++
          toKv("etl_tags", enriched.etl_tags) ++
          toDateKv("dvce_sent_tstamp", enriched.dvce_sent_tstamp) ++
          toDateKv("derived_tstamp", enriched.derived_tstamp) ++
          toKv("event_vendor", enriched.event_vendor) ++
          toKv("event_name", enriched.event_name) ++
          toKv("event_format", enriched.event_format) ++
          toKv("event_version", enriched.event_version) ++
          toKv("event_fingerprint", enriched.event_fingerprint) ++
          toDateKv("true_tstamp", enriched.true_tstamp) ++
          toDateKv("event_tstamp", enriched.event_tstamp) ++
          toKv("event_quality", enriched.event_quality) ++
          toKv("sandbox_mode", enriched.sandbox_mode) ++
          toKv("backfill_mode", enriched.backfill_mode) ++
          toDateKv("date_", enriched.date_)
      )
    )

  /*
  Some fields in PartiallyEnrichedEvent object are default ("", 0)
  because PartiallyEnrichedEvent is imported over module and we can not change it.
  PartiallyEnrichedEvent is used for bad row topic.
  */
  def toPartiallyEnrichedEvent(enrichedEvent: EnrichedEvent): PartiallyEnrichedEvent =
    PartiallyEnrichedEvent(
      app_id = Option(enrichedEvent.app_id),
      platform = Option(enrichedEvent.platform),
      etl_tstamp = Option(enrichedEvent.enricher_tstamp),
      collector_tstamp = Option(enrichedEvent.collector_tstamp),
      dvce_created_tstamp = Option(enrichedEvent.dvce_created_tstamp),
      event = Option(enrichedEvent.event),
      event_id = Option(enrichedEvent.event_id),
      txn_id = Option(""),
      name_tracker = Option(enrichedEvent.name_tracker),
      v_tracker = Option(enrichedEvent.v_tracker),
      v_collector = Option(enrichedEvent.v_collector),
      v_etl = Option(enrichedEvent.v_etl),
      user_id = Option(enrichedEvent.user_id),
      //registration_id = Option(enrichedEvent.registration_id),
      user_ipaddress = Option(enrichedEvent.user_ipaddress),
      user_fingerprint = Option(""),
      domain_userid = Option(""),
      domain_sessionidx = Option(0),//.map(Integer2int),
      network_userid = Option(enrichedEvent.network_userid),
      geo_country = Option(enrichedEvent.geo_country),
      geo_region = Option(enrichedEvent.geo_region),
      geo_city = Option(enrichedEvent.geo_city),
      geo_zipcode = Option(enrichedEvent.geo_zipcode),
      geo_latitude = Option(enrichedEvent.geo_latitude).map(Float2float),
      geo_longitude = Option(enrichedEvent.geo_longitude).map(Float2float),
      geo_region_name = Option(enrichedEvent.geo_region_name),
      ip_isp = Option(""),
      ip_organization = Option(""),
      ip_domain = Option(""),
      ip_netspeed = Option(""),
      page_url = Option(""),
      page_title = Option(""),
      page_referrer = Option(""),
      page_urlscheme = Option(""),
      page_urlhost = Option(""),
      page_urlport = Option(0),//.map(Integer2int),
      page_urlpath = Option(""),
      page_urlquery = Option(""),
      page_urlfragment = Option(""),
      refr_urlscheme = Option(""),
      refr_urlhost = Option(""),
      refr_urlport = Option(0),//.map(Integer2int),
      refr_urlpath = Option(""),
      refr_urlquery = Option(""),
      refr_urlfragment = Option(""),
      refr_medium = Option(""),
      refr_source = Option(""),
      refr_term = Option(""),
      mkt_medium = Option(enrichedEvent.mkt_medium),
      mkt_source = Option(enrichedEvent.mkt_source),
      mkt_term = Option(enrichedEvent.mkt_term),
      mkt_content = Option(enrichedEvent.mkt_content),
      mkt_campaign = Option(enrichedEvent.mkt_campaign),
      contexts = Option(enrichedEvent.contexts),
      se_category = Option(""),
      se_action = Option(""),
      se_label = Option(""),
      se_property = Option(""),
      se_value = Option("").map(_.toString),
      unstruct_event = Option(enrichedEvent.unstruct_event),
      tr_orderid = Option(""),
      tr_affiliation = Option(""),
      tr_total = Option("").map(_.toString),
      tr_tax = Option("").map(_.toString),
      tr_shipping = Option("").map(_.toString),
      tr_city = Option(""),
      tr_state = Option(""),
      tr_country = Option(""),
      ti_orderid = Option(""),
      ti_sku = Option(""),
      ti_name = Option(""),
      ti_category = Option(""),
      ti_price = Option("").map(_.toString),
      ti_quantity = Option(0),//.map(Integer2int),
      pp_xoffset_min = Option(0),//.map(Integer2int),
      pp_xoffset_max = Option(0),//.map(Integer2int),
      pp_yoffset_min = Option(0),//.map(Integer2int),
      pp_yoffset_max = Option(0),//.map(Integer2int),
      useragent = Option(enrichedEvent.useragent),
      br_name = Option(""),
      br_family = Option(""),
      br_version = Option(""),
      br_type = Option(""),
      br_renderengine = Option(""),
      br_lang = Option(""),
      br_features_pdf = Option(0),//.map(Byte2byte),
      br_features_flash = Option(0),//.map(Byte2byte),
      br_features_java = Option(0),//.map(Byte2byte),
      br_features_director = Option(0),//.map(Byte2byte),
      br_features_quicktime = Option(0),//.map(Byte2byte),
      br_features_realplayer = Option(0),//.map(Byte2byte),
      br_features_windowsmedia = Option(0),//.map(Byte2byte),
      br_features_gears = Option(0),//.map(Byte2byte),
      br_features_silverlight = Option(0),//.map(Byte2byte),
      br_cookies = Option(0),//.map(Byte2byte),
      br_colordepth = Option(""),
      br_viewwidth = Option(0),//.map(Integer2int),
      br_viewheight = Option(0),//.map(Integer2int),
      os_name = Option(""),
      os_family = Option(""),
      os_manufacturer = Option(""),
      os_timezone = Option(""),
      dvce_type = Option(""),
      dvce_ismobile = Option(0),//.map(Byte2byte),
      dvce_screenwidth = Option(0),//.map(Integer2int),
      dvce_screenheight = Option(0),//.map(Integer2int),
      doc_charset = Option(""),
      doc_width = Option(0),//.map(Integer2int),
      doc_height = Option(0),//.map(Integer2int),
      tr_currency = Option(""),
      tr_total_base = Option("").map(_.toString),
      tr_tax_base = Option("").map(_.toString),
      tr_shipping_base = Option("").map(_.toString),
      ti_currency = Option(""),
      ti_price_base = Option("").map(_.toString),
      base_currency = Option(""),
      geo_timezone = Option(enrichedEvent.geo_timezone),
      mkt_clickid = Option(""),
      mkt_network = Option(""),
      etl_tags = Option(enrichedEvent.etl_tags),
      dvce_sent_tstamp = Option(enrichedEvent.dvce_sent_tstamp),
      refr_domain_userid = Option(""),
      refr_dvce_tstamp = Option(""),
      derived_contexts = Option(enrichedEvent.derived_contexts),
      domain_sessionid = Option(""),
      derived_tstamp = Option(enrichedEvent.derived_tstamp),
      event_vendor = Option(enrichedEvent.event_vendor),
      event_name = Option(enrichedEvent.event_name),
      event_format = Option(enrichedEvent.event_format),
      event_version = Option(enrichedEvent.event_version),
      event_fingerprint = Option(enrichedEvent.event_fingerprint),
      true_tstamp = Option(enrichedEvent.true_tstamp),
    )
}

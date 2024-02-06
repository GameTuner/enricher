package com.snowplowanalytics.snowplow.enrich.common

import io.opencensus.exporter.stats.stackdriver.StackdriverStatsExporter


object GcpMetrics {

  def enable() = {
    StackdriverStatsExporter.createAndRegister()
  }
}

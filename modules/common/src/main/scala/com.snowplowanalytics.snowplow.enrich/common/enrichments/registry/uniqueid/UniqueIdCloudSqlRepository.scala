package com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.uniqueid

import com.zaxxer.hikari.HikariDataSource
import org.postgresql.util.PGobject

import java.sql.Connection
import scala.io.Source

class UniqueIdCloudSqlRepository(
                                  username: String,
                                  password: String,
                                  database: String,
                                  connectionName: String
                                ) extends UniqueIdRepository {

  val dataSource = getDataSource()
  runDDL()

  private def getDataSource(): HikariDataSource = {
    val source = new HikariDataSource()
    val _: Class[_] = Class.forName("org.postgresql.Driver") // Load class
    val connectionString =
      s"jdbc:postgresql:///$database?cloudSqlInstance=$connectionName&user=$username&password=$password&socketFactory=com.google.cloud.sql.postgres.SocketFactory"

    source.setJdbcUrl(connectionString)
    source.setMaximumPoolSize(10)
    source
  }

  def readFile(path: String): String = {
    val source =
      if (path.startsWith("/")) Source.fromFile(path)
      else Source.fromResource(path)
    val contents = source.mkString
    source.close()
    contents
  }

  def runDDL() = {
    var connection: Connection = null
    try {
      connection = dataSource.getConnection
      val statement = connection.prepareStatement(readFile("unique_id.sql"))
      statement.execute()

    } finally {
      if (connection != null) {
        connection.close()
      }
    }
  }

  def getUniqueId (appId: String, installationId: Option[String], userId: Option[String]): UniqueIdResult = {
    var connection: Connection = null
    try {
      connection = dataSource.getConnection
      val statement = connection.prepareStatement("SELECT get_unique_id(?, ?, ?)")
      statement.setString(1, appId)
      if (installationId.isDefined) {
        statement.setString(2, installationId.get)
      } else {
        statement.setNull(2, java.sql.Types.CHAR)
      }
      if (userId.isDefined) {
        statement.setString(3, userId.get)
      } else {
        statement.setNull(3, java.sql.Types.CHAR)
      }
      val result = statement.executeQuery()
      result.next()
      // we get (unique_id,t), need to parse it
      val recordValue = result.getObject(1).asInstanceOf[PGobject].getValue
      val token = recordValue.substring(1, recordValue.length - 1).split(',')
      UniqueIdResult(token(0), token(1) == "t")
    } finally {
      if (connection != null) {
        connection.close()
      }
    }
  }
}
package io.frama.parisni.spark.postgres

import com.opentable.db.postgres.junit.{EmbeddedPostgresRules, SingleInstancePostgresRule}
import org.apache.spark.sql.QueryTest
import org.junit.{Rule, Test}
import org.postgresql.util.PSQLException

import scala.annotation.meta.getter

class ExampleSuite extends QueryTest with SparkSessionTestWrapper {


  @Test def verifySpark(): Unit = {
    spark.sql("select 1").show
  }

  @Test def verifyPostgres() { // Uses JUnit-style assertions
    println(pg.getEmbeddedPostgres.getJdbcUrl("postgres", "pg"))
    val con = pg.getEmbeddedPostgres.getPostgresDatabase.getConnection
    val res2 = con.createStatement().executeUpdate("create table test(i int)")
    val res = con.createStatement().executeQuery("select 27")
    while (res.next())
      println(res.getInt(1))
  }

  @Test def verifySparkPostgres(): Unit = {

    val input = spark.sql("select 1 as t")
    input
      .write.format("io.frama.parisni.spark.postgres")
      .option("host", "localhost")
      .option("port", pg.getEmbeddedPostgres.getPort)
      .option("database", "postgres")
      .option("user", "postgres")
      .option("table", "test_table")
      .mode(org.apache.spark.sql.SaveMode.Overwrite)
      .save

    val output = spark.read.format("io.frama.parisni.spark.postgres")
      .option("host", "localhost")
      .option("port", pg.getEmbeddedPostgres.getPort)
      .option("database", "postgres")
      .option("user", "postgres")
      .option("query", "select * from test_table")
      .load

    checkAnswer(input, output)
  }

  @Test def verifySparkPostgresOldDatasource(): Unit = {

    val input = spark.sql("select 1 as t")
    input
      .write.format("postgres")
      .option("host", "localhost")
      .option("port", pg.getEmbeddedPostgres.getPort)
      .option("database", "postgres")
      .option("user", "postgres")
      .option("table", "test_table")
      .mode(org.apache.spark.sql.SaveMode.Overwrite)
      .save

    val output = spark.read.format("postgres")
      .option("host", "localhost")
      .option("port", pg.getEmbeddedPostgres.getPort)
      .option("database", "postgres")
      .option("user", "postgres")
      .option("query", "select * from test_table")
      .load

    checkAnswer(input, output)
  }

  @Test
  def verifyPostgresConnectionWithUrl(): Unit = {

    val input = spark.sql("select 2 as t")
    input
      .write.format("io.frama.parisni.spark.postgres")
      .option("url", getPgUrl)
      .option("table", "test_table")
      .mode(org.apache.spark.sql.SaveMode.Overwrite)
      .save

  }

  @Test
  def verifyPostgresConnection() {
    val pg = PGTool(spark, getPgUrl, "/tmp")
      .setPassword("postgres")
    pg.showPassword()
    pg.sqlExecWithResult("select 1").show

  }

  @Test
  def verifyPostgresConnectionFailWhenBadPassword() {
    assertThrows[Exception](
      spark.sql("select 2 as t")
        .write.format("io.frama.parisni.spark.postgres")
        .option("host", "localhost")
        .option("port", pg.getEmbeddedPostgres.getPort)
        .option("database", "postgres")
        .option("user", "idontknow")
        .option("password", "badpassword")
        .option("table", "test_table")
        .mode(org.apache.spark.sql.SaveMode.Overwrite)
        .save
    )
  }

  @Test
  def verifyPostgresCreateTable(): Unit = {
    import spark.implicits._
    val schema = ((1, "asdf", 1L, Array(1, 2, 3), Array("bob"), Array(1L, 2L)) :: Nil)
      .toDF("int_col", "string_col", "long_col", "array_int_col", "array_string_col", "array_bigint_col").schema
    getPgTool().tableCreate("test_array", schema, true)
  }

  @Test
  def verifyPostgresCreateSpecialTable(): Unit = {
    import spark.implicits._
    val data = ((1, "asdf", 1L, Array(1, 2, 3), Array("bob"), Array(1L, 2L)) :: Nil)
      .toDF("INT_COL", "STRING_COL", "LONG_COL", "ARRAY_INT_COL", "ARRAY_STRING_COL", "ARRAY_BIGINT_COL")
    val schema = data.schema
    getPgTool().tableCreate("TEST_ARRAY", schema, true)
    data.write.format("io.frama.parisni.spark.postgres")
      .option("url", getPgUrl)
      .option("type", "full")
      .option("table", "TEST_ARRAY")
      .save
  }

  @Test
  def verifyKillLocks(): Unit = {
    val db = pg.getEmbeddedPostgres.getPostgresDatabase
    val conn = db.getConnection()
    conn.createStatement().execute("create table lockable()")
    conn.setAutoCommit(false)
    try {
      conn.createStatement().execute("BEGIN TRANSACTION")
      conn.createStatement().execute("LOCK lockable IN ACCESS EXCLUSIVE MODE")
      assert(getPgTool().killLocks("lockable") == 1)
      conn.commit()
      fail()
    } catch {
      case e: PSQLException => succeed
    }
  }

  @Test
  def verifyRename(): Unit = {
    val db = pg.getEmbeddedPostgres.getPostgresDatabase
    val conn = db.getConnection()
    conn.createStatement().execute("create table to_rename()")
    getPgTool().tableRename("to_rename", "renamed")

    var rs = conn.createStatement().executeQuery("SELECT EXISTS(SELECT FROM information_schema.tables WHERE table_name = 'to_rename')")
    rs.next()
    assert(! rs.getBoolean(1))

    rs = conn.createStatement().executeQuery("SELECT EXISTS(SELECT FROM information_schema.tables WHERE table_name = 'renamed')")
    rs.next()
    assert(rs.getBoolean(1))
    conn.close()
  }
}

import org.apache.spark.sql.SparkSession

trait SparkSessionTestWrapper {

  // looks like crazy but compatibility issue with junit rule (public)
  @(Rule@getter)
  var pg: SingleInstancePostgresRule = EmbeddedPostgresRules.singleInstance()

  def getPgUrl = pg.getEmbeddedPostgres.getJdbcUrl("postgres", "postgres") + "&currentSchema=public"

  def getPgTool() = PGTool(spark, getPgUrl, "/tmp")

  lazy val spark: SparkSession = {
    SparkSession
      .builder()
      .master("local")
      .appName("spark session")
      .config("spark.sql.shuffle.partitions", "1")
      .getOrCreate()
  }

}

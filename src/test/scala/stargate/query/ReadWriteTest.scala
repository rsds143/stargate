package stargate.query

import stargate.CassandraTest
import com.typesafe.config.ConfigFactory
import org.junit.{AfterClass, BeforeClass, Test}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import stargate.schema.ENTITY_ID_COLUMN_NAME
import stargate.cassandra

class ReadWriteTest {

  import ReadWriteTest._


  @Test
  def testCreateDelete = {
    val session = ReadWriteTest.newSession
    Await.ready(model.createTables(session, executor), Duration.Inf)

    List.range(0, 100).foreach(_ => {
      model.input.entities.values.foreach(entity => {
        val tables = model.entityTables(entity.name)
        val random = stargate.model.generator.createEntity(model.input, entity.name, 1)
        val (id, statements) = stargate.query.write.createEntity(tables, random)
        val payload = random.updated(ENTITY_ID_COLUMN_NAME, id)
        val future = statements.map(cassandra.executeAsync(session, _, executor))
        Await.result(Future.sequence(future), Duration.Inf)
        tables.foreach(table => {
          val conditions = stargate.query.write.tableConditionsForEntity(table, payload).map(_.get)
          val select = stargate.query.read.selectStatement(table.name, conditions).build
          val rs = session.execute(select)
          assert(rs.iterator.asScala.toList.length == 1)
        })
        val deleteStatements = stargate.query.write.deleteEntity(tables, payload)
        val deleted = deleteStatements.map(cassandra.executeAsync(session, _, executor))
        Await.result(Future.sequence(deleted), Duration.Inf)
        tables.foreach(table => {
          val conditions = stargate.query.write.tableConditionsForEntity(table, payload).map(_.get)
          val select = stargate.query.read.selectStatement(table.name, conditions).build
          val rs = session.execute(select)
          assert(rs.iterator.asScala.toList.isEmpty)
        })
      })
    })
  }

}


object ReadWriteTest extends CassandraTest {

  val modelConfig = ConfigFactory.parseResources("read-write-test-schema.conf")
  val inputModel = stargate.model.parser.parseModel(modelConfig)
  val model = stargate.schema.outputModel(inputModel)
  implicit val executor: ExecutionContext = ExecutionContext.global

  @BeforeClass def before = this.ensureCassandraRunning
  @AfterClass def after = this.cleanup
}
package com.tactix4.t4ADT

import com.tactix4.t4openerp.connector.OEConnector
import org.scalatest.{ParallelTestExecution, Matchers}
import org.scalatest.prop.{Checkers, PropertyChecks}
import org.apache.camel.component.hl7.HL7MLLPCodec
import org.apache.camel.test.spring.CamelSpringTestSupport
import org.springframework.context.support.{ClassPathXmlApplicationContext, AbstractApplicationContext}
import org.apache.camel.ExchangePattern
import org.junit.Test
import com.typesafe.config.ConfigFactory
import java.util.Properties
import java.io.FileInputStream
import scala.concurrent.Await
import scala.concurrent.duration._
import com.tactix4.t4openerp.connector.transport.{OEType, OEString, OEDictionary}
import org.scalautils._
import TypeCheckedTripleEquals._
import org.scalacheck.Prop.forAllNoShrink
import org.scalatest.prop.Checkers.check
import com.tactix4.t4openerp.connector.domain._
import com.tactix4.t4openerp.connector.domain.Domain._
import com.tactix4.t4openerp.connector._
import scala.concurrent.ExecutionContext.Implicits.global
import scalaz.std.option._
import scalaz.syntax.std.boolean._
import scalaz.std.option.optionSyntax._
import scalaz.std.anyVal._
import scalaz.std.string._


/**
 * Created by max on 06/06/14.
 */
class ADTGenTest extends CamelSpringTestSupport with PropertyChecks with ADTGen with Matchers with TripleEqualsSupport {
  val config = ConfigFactory.load("com.tactix4.t4ADT.conf")

  val protocol: String = config.getString("openERP.protocol")
  val host: String = config.getString("openERP.hostname")
  val port: Int = config.getInt("openERP.port")
  val username: String = config.getString("openERP.username")
  val password: String = config.getString("openERP.password")
  val database: String = config.getString("openERP.database")

  val connector = new OEConnector(protocol, host, port).startSession(username, password, database)

 def createApplicationContext(): AbstractApplicationContext = {
    new ClassPathXmlApplicationContext("META-INF/spring/testBeans.xml")
  }

  val URI:String = "mina2:tcp://localhost:31337?sync=true&codec=#hl7codec"

  override def createRegistry() ={

    val jndi = super.createRegistry()
    val codec = new HL7MLLPCodec()
    codec.setCharset("iso-8859-1")
    jndi.bind("hl7codec", codec)
    jndi
  }
  val titleMap = Map("Mr." -> "Mister","Mrs" -> "Madam", "Miss" -> "Miss", "Ms" -> "Ms")


  def notHistorical(msg:ADTMsg, msgs:List[ADTMsg]) : Boolean =  {
    val previous = msgs.takeWhile(_ != msg).dropWhile(_.evn.evnOccured != msg.evn.evnOccured)
    previous.forall(p => if(List("A01", "A02", "A03").contains(p.msh.msgType)) p.evn.evnOccured == msg.evn.evnOccured else true)
  }


  @Test
  def randomVisitTest() = {
    val property = forAllNoShrink(createVisit) { (msgs: List[ADTMsg]) =>
          log.info("Testing the visit: " + msgs.map(_.evn.msgType).mkString(" - "))
          msgs.foreach(msg => {
            if (msg.msh.msgType != "A40") {
              log.info("sending: \n" + msg.toString.replace("\r", "\n"))
              val result:String = template.requestBody(URI, msg.toString, classOf[String])
              log.info("got result: \n" + result.replace("\r", "\n"))
              assert(result contains s"MSA|AA|${msg.msh.id}", s"Result does not look like a success: $result")
              Thread.sleep(2000)
              checkPID(msg.pid)
              if(notHistorical(msg,msgs)) checkVisit(msg.msh.msgType, msg.pv1)
            }
          })
          true
      }
    check( property,Checkers.Workers(1))
  }

  def checkVisit(msgType:String, pv1:PV1Segment) : Unit = {
    val response = Await.result(
      (for {
        ids <- connector.search("t4clinical.patient.visit", "name" === pv1.visitID)
        result <- connector.read("t4clinical.patient.visit", ids,List("pos_location_parent", "pos_location","consulting_doctor_ids", "visit_start","visit_end"))
      } yield result).run ,5 seconds)
    response.fold(
    (message: ErrorMessage) => fail("Check Visit failed: " + message),
    (v:List[Map[String,OEType]]) => v.headOption.map(d =>{
        val cdID = for {
          o <- d.get("consulting_doctor_ids")
          a <- o.array
          h <- a.headOption
          i <- h.int
        } yield  i

        val cdName = Await.result(connector.read("hr.employee",List(~cdID),List("name")).run, 2 seconds).map(
          _.headOption.flatMap(_.get("name").flatMap(_.string))
        )

        val cdc = pv1.consultingDoctor.toString
        val vs = d.get("visit_start").flatMap(_.string)
        val ve = d.get("visit_end").flatMap(_.string)
        val plp = d.get("pos_location").flatMap(_.array).flatMap(_(1).string).map(_.toUpperCase())
        if(msgType == "A11" || msgType == "A03"){
          assert(plp == None, s"pos_location should be None: $plp")
        } else {
          assert(plp.flatMap(wards.get) == Some(pv1.wardCode), s"pos_locations don't match: ${plp.flatMap(wards.get)} vs ${pv1.wardCode}")
        }
        assert((cdc.isEmpty ? (None:Option[String]) | Some(cdc)) == cdName.getOrElse(None), s"Consulting doctor string doesn't match: $cdc vs $cdName")
        val admitDate =pv1.admitDate.map(_.toString(oerpDateTimeFormat))
        if(pv1.admitDate != None) assert(vs == admitDate , s"Admit dates don't match: ${pv1.admitDate}")
        if(msgType == "A13"){
          assert(ve == None, s"Discharge date should be None/Null because we just cancelled the discharge. ${~ve} was returned instead")
        } else {
          val dd = pv1.dischargeDate.map(_.toString(oerpDateTimeFormat))
          assert(ve == dd, s"Discharge dates don't match: $vs vs $dd")
        }
      })
    )
  }

  def checkPID(pid:PIDSegment): Unit = {
    val response = Await.result(connector.searchAndRead("t4clinical.patient", DomainTuple("other_identifier",com.tactix4.t4openerp.connector.domain.Equality(), OEString(pid.p.hospitalNo)), List("given_name","family_name","middle_names","title","dob","sex")).run,5 seconds)
    response.fold(
      (message: ErrorMessage) => fail("Check PID failed: " + message),
      (v: List[Map[String,OEType]]) => v.headOption.map(d =>{
        val gn = d.get("given_name").flatMap(_.string)
        val mns = d.get("middle_names").flatMap(_.string)
        val fn = d.get("family_name").flatMap(_.string)
        val t = d.get("title").flatMap(_.array).flatMap(_(1).string)
        val dob = d.get("dob").flatMap(_.string)
        val sex = d.get("sex").flatMap(_.string)
        assert(gn == pid.p.givenName)
        assert(mns == pid.p.middleNames)
        assert(fn == pid.p.familyName)
        assert( t == pid.p.title.flatMap(t2 => titleMap.get(t2)))
        assert(dob == pid.p.dob.map(_.toString(oerpDateTimeFormat)))
        assert(sex == pid.p.sex.map(_.toUpperCase))
      }) orElse fail(s"no result from server for patient ${pid.p.hospitalNo}")
    )


  }

}

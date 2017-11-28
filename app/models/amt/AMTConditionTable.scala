package models.amt

/**
  * Created by manaswi on 5/5/17.
  */

import models.amt.AMTAssignmentTable.db
import models.audit.{AuditTaskEnvironmentTable, AuditTaskTable}
import models.clustering_session.UserLabelToCluster
import models.label.{LabelTable, ProblemTemporarinessTable}
import models.route.RouteStreetTable
import models.utils.MyPostgresDriver.simple._
import models.user.{UserRole, UserRoleTable}
import play.api.Play.current
import play.api.libs.json.{JsObject, Json}
import play.extras.geojson

import scala.slick.lifted.ForeignKeyQuery
import scala.slick.jdbc.{GetResult, StaticQuery => Q}

case class AMTCondition(amtConditionId: Int, description: Option[String], parameters: String, volunteerId: String)
case class VolunteerLabel(conditionId: Int, routeId: Int, userId: String, labelId: Int, labelType: String,
                          lat: Option[Float], lng: Option[Float], severity: Option[Int], temporary: Boolean) {
  /**
    * This method converts the data into the GeoJSON format
    * @return
    */
  def toJSON: JsObject = {
    val latlngs = geojson.Point(geojson.LatLng(lat.get.toDouble, lng.get.toDouble))
    val properties = Json.obj(
      "condition_id" -> conditionId,
      "route_id" -> routeId,
      "worker_id" -> userId,
      "label_id" -> labelId,
      "label_type" -> labelType,
      "severity" -> severity,
      "temporary" -> temporary
    )
    Json.obj("type" -> "Feature", "geometry" -> latlngs, "properties" -> properties)
  }

  def toUserLabelToCluster: UserLabelToCluster = {
    UserLabelToCluster(labelId, labelType, lat, lng, severity, temporary)
  }
}


/**
  *
  */
class AMTConditionTable(tag: Tag) extends Table[AMTCondition](tag, Some("sidewalk"), "amt_condition") {
  def amtConditionId = column[Int]("amt_condition_id", O.NotNull, O.PrimaryKey, O.AutoInc)
  def description = column[Option[String]]("description", O.Nullable)
  def parameters = column[String]("parameters", O.NotNull)
  def volunteerId = column[String]("volunteer_id", O.NotNull)

  def * = (amtConditionId, description, parameters, volunteerId) <> ((AMTCondition.apply _).tupled, AMTCondition.unapply)

}

/**
  * Data access object for the Condition table
  */
object AMTConditionTable {
  val db = play.api.db.slick.DB
  val amtConditions = TableQuery[AMTConditionTable]
  val amtAssignments = TableQuery[AMTAssignmentTable]
  val maxNumConditionAssignments: Int = 5

  val registeredUserConditions: List[Int] = (70 to 122).toList
  val anonUserConditions: List[Int] = (123 to 140).toList

  def getVolunteerIdByCondition(amtConditionId: Int): String = db.withTransaction { implicit session =>
    val vId = amtConditions.filter(_.amtConditionId === amtConditionId).map(_.volunteerId).list.headOption
    vId.get
  }

  def getVolunteerIdByRouteId(routeId: Int): String = db.withTransaction { implicit session =>
    val vId = AMTVolunteerRouteTable.amtVolunteerRoutes.filter(_.routeId === routeId).map(_.volunteerId).list.headOption
    vId.get
  }

  def assignAvailableCondition: Option[Int] =  db.withTransaction { implicit session =>
    //Get the condition id with the least number of current assignments

    val selectConditionIdQuery = Q.query[Int, Int](
      """SELECT amt_condition_id
        |  FROM (SELECT amt_condition.amt_condition_id, count(condition_id) AS cnt FROM
        |  (
        |    SELECT * FROM sidewalk.amt_assignment
        |    WHERE turker_id NOT IN ('APQS1PRMDXAFH','A1SZNIADA6B4OF','A2G18P2LDT3ZUE','AKRNZU81S71QI','A1Y6PQWK6BYEDD','TESTWORKERID')) t2
        |    RIGHT JOIN sidewalk.amt_condition
        |    ON (t2.condition_id = amt_condition.amt_condition_id)
        |    GROUP BY amt_condition.amt_condition_id
        |  ) t1
        |  WHERE amt_condition_id IN (72, 120) AND cnt < ?
        |  ORDER BY cnt ASC LIMIT 1;
      """.stripMargin
    )

    selectConditionIdQuery(maxNumConditionAssignments).list.headOption
  }

  def getAllConditionIds: List[Int] = db.withTransaction { implicit session =>
    amtConditions.map(_.amtConditionId).list
  }

  def getRouteIdsForAllConditions: List[Int] = db.withTransaction { implicit session =>
    amtConditions.innerJoin(AMTVolunteerRouteTable.amtVolunteerRoutes).on(_.volunteerId === _.volunteerId).map(_._2.routeId).list
  }

  /**
    * Returns the set of route ids associated with the specified condition.
    *
    * @param conditionId
    * @return
    */
  def getRouteIdsForACondition(conditionId: Int): List[Int] = db.withTransaction {implicit session =>
    amtConditions.filter(_.amtConditionId === conditionId)
      .innerJoin(AMTVolunteerRouteTable.amtVolunteerRoutes)
      .on(_.volunteerId === _.volunteerId)
      .map(_._2.routeId).list
  }

  /**
    * Returns the condition id associated with the specified route.
    *
    * @param routeId
    * @return
    */
  def getConditionIdForRoute(routeId: Int): Int = db.withTransaction { implicit session =>
    amtConditions
      .innerJoin(AMTVolunteerRouteTable.amtVolunteerRoutes)
      .on(_.volunteerId === _.volunteerId)
      .filter(_._2.routeId === routeId)
      .map(_._1.amtConditionId).list.head
  }

  /**
    * Returns labels that were placed by a volunteer in the specified condition.
    *
    * @param conditionId
    * @return
    */
  def getVolunteerLabelsByCondition(conditionId: Int): List[VolunteerLabel] = db.withSession { implicit session =>
    val routeIds: List[Int] = getRouteIdsForACondition(conditionId)
    routeIds.flatMap(getVolunteerLabelsByRoute(_))
  }

  /**
    * Returns labels that were placed by a volunteer in the specified route.
    *
    * @param routeId
    * @return
    */
  def getVolunteerLabelsByRoute(routeId: Int): List[VolunteerLabel] = db.withSession { implicit session =>

    val nonOnboardingLabs = LabelTable.labelsWithoutDeleted.filterNot(_.gsvPanoramaId === "stxXyCKAbd73DmkM2vsIHA")

    val labels = routeId match {
      case rId if registeredUserConditions.contains(getConditionIdForRoute(rId)) =>
        for {
          _routes <- AMTVolunteerRouteTable.amtVolunteerRoutes if _routes.routeId === rId
          _condition <- AMTConditionTable.amtConditions if _condition.volunteerId === _routes.volunteerId
          _streets <- RouteStreetTable.routesStreets if _streets.routeId === _routes.routeId
          _tasks <- AuditTaskTable.auditTasks if _tasks.streetEdgeId === _streets.current_street_edge_id && _tasks.completed
          _labs <- nonOnboardingLabs if _tasks.auditTaskId === _labs.auditTaskId
          _latlngs <- LabelTable.labelPoints if _labs.labelId === _latlngs.labelId
          _types <- LabelTable.labelTypes if _labs.labelTypeId === _types.labelTypeId
        } yield (_condition.amtConditionId, _streets.routeId, _routes.volunteerId, _labs.labelId, _types.labelType, _latlngs.lat, _latlngs.lng)

      case rId if anonUserConditions.contains(getConditionIdForRoute(rId)) =>
        val tasks = for {
          _routes <- AMTVolunteerRouteTable.amtVolunteerRoutes if _routes.routeId === rId
          _condition <- AMTConditionTable.amtConditions if _condition.volunteerId === _routes.volunteerId
          _streets <- RouteStreetTable.routesStreets if _streets.routeId === _routes.routeId
          _tasks <- AuditTaskTable.auditTasks if _tasks.streetEdgeId === _streets.current_street_edge_id && _tasks.completed
          _env <- AuditTaskEnvironmentTable.auditTaskEnvironments if _env.auditTaskId === _tasks.auditTaskId
        } yield (_condition.amtConditionId, _streets.routeId, _routes.volunteerId, _tasks.auditTaskId, _routes.ipAddress, _env.ipAddress)

        val correctTasks = tasks.filter(t => t._5 === t._6).groupBy(x => x).map(_._1)
        for {
          (_tasks, _labs) <- correctTasks innerJoin nonOnboardingLabs on (_._4 === _.auditTaskId)
          _latlngs <- LabelTable.labelPoints if _labs.labelId === _latlngs.labelId
          _types <- LabelTable.labelTypes if _labs.labelTypeId === _types.labelTypeId
        } yield (_tasks._1, _tasks._2, _tasks._5, _labs.labelId, _types.labelType, _latlngs.lat, _latlngs.lng)
    }

    // left joins to get severity for any labels that have them
    val labelsWithSeverity = for {
      (_labs, _severity) <- labels.leftJoin(LabelTable.severities).on(_._2 === _.labelId)
    } yield (_labs._1, _labs._2, _labs._3, _labs._4, _labs._5, _labs._6, _labs._7,  _severity.severity.?)

    // left joins to get temporariness for any labels that have them (those that don't are marked as temporary=false)
    val labelsWithTemporariness = for {
      (_labs, _temporariness) <- labelsWithSeverity.leftJoin(ProblemTemporarinessTable.problemTemporarinesses).on(_._2 === _.labelId)
    } yield (_labs._1, _labs._2, _labs._3, _labs._4, _labs._5, _labs._6, _labs._7, _labs._8, _temporariness.temporaryProblem.?)

    labelsWithTemporariness.list.map(x => VolunteerLabel.tupled((x._1, x._2, x._3, x._4, x._5, x._6, x._7, x._8, x._9.getOrElse(false))))
  }

  def save(cond: AMTCondition): Int = db.withTransaction { implicit session =>
    val condId: Int =
      (amtConditions returning amtConditions.map(_.amtConditionId)) += cond
    condId
  }
}

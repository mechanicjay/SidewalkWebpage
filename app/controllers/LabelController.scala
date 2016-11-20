package controllers

import java.sql.Timestamp
import java.util.{Calendar, Date, TimeZone, UUID}
import javax.inject.Inject

import com.mohiva.play.silhouette.api.{Environment, Silhouette}
import com.mohiva.play.silhouette.impl.authenticators.SessionAuthenticator
import com.vividsolutions.jts.geom._
import controllers.headers.ProvidesHeader
import formats.json.LabelFormats._
import models.amt.{AMTAssignment, AMTAssignmentTable}
import models.audit._
import models.daos.slick.DBTableDefinitions.{DBUser, UserTable}
import models.gsv.{GSVData, GSVDataTable, GSVLink, GSVLinkTable}
import models.label._
import models.mission.{Mission, MissionStatus, MissionTable}
import models.region._
import models.street.StreetEdgeAssignmentCountTable
import models.user.{User, UserCurrentRegionTable}
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc._
import play.api.Play.current

import scala.concurrent.Future

/**
  * Street controller
  */
class LabelController @Inject() (implicit val env: Environment[User, SessionAuthenticator])
  extends Silhouette[User, SessionAuthenticator] with ProvidesHeader {

  /**
    *
    * @param regionId Region id
    * @return
    */
  def getLabelsFromCurrentMission(regionId: Int) = UserAwareAction.async { implicit request =>
    request.identity match {
      case Some(user) =>
        val labels = _helpGetLabelsFromCurrentMission(regionId, user.userId)
        val jsLabels = JsArray(labels.map(l => Json.toJson(l)))
        Future.successful(Ok(jsLabels))
      case None =>
        Future.successful(Ok(JsArray(List())))
    }
  }

  def _helpGetLabelsFromCurrentMission(regionId: Int, userId: UUID): List[Label] = {

    val interactions: List[AuditTaskInteraction] = AuditTaskInteractionTable.selectAuditTaskInteractionsOfAUser(regionId, userId)

    val lastIdx = interactions.map(_.action).reverse.indexOf("MissionComplete")

    val currentMissionInteractions = if (lastIdx == -1) {
      // No mission has been completed. Return all the interactions.
       interactions
    } else {
      // At least one mission has been completed. Return the labels after the last MissionComplete
      val idx = interactions.length - lastIdx - 1
      interactions.zipWithIndex.filter(_._2 > idx).map(_._1)
    }

    // Filter out all the interaction without temporary_label_id
    val filteredInteractions = currentMissionInteractions.filter(_.temporaryLabelId.isDefined)

    val labels = LabelTable.selectLabelsByInteractions(userId, filteredInteractions)

    labels
//    val CRSEpsg4326 = CRS.decode("epsg:4326")
//    val CRSEpsg26918 = CRS.decode("epsg:26918")
//    val transform = CRS.findMathTransform(CRSEpsg4326, CRSEpsg26918)
//
//    // Get tasks completed in the current region.
//    val tasks = AuditTaskTable.selectCompletedTasksInARegion(regionId, userId)
//
//    if (tasks.isEmpty) return List()
//
//    // Get missions in the current region
//    val completedMissions = MissionTable.selectCompletedMissionsByAUser(userId, regionId)
//
//    // Get the last mission distances (i.e., the cumulatirve mission distance traveled traveled).
//    if (completedMissions.isEmpty) {
//      // TODO: Return all the labels submitted so far
//      List()
//    } else {
//      // Todo: Return all the labels
//      val completedDistance: Double = completedMissions.last.distance.get
//
//      // Compute the tasks that are completed during the latest (current) mission
//      // http://stackoverflow.com/questions/3224935/in-scala-how-do-i-fold-a-list-and-return-the-intermediate-results
//      val cumulativeTaskDistances: List[Double] = tasks.map {var s: Double = 0; task => {s += JTS.transform(task.geom, transform).getLength; s}}
//
//      val lastMissionTasks: List[NewTask] = cumulativeTaskDistances.find(_ > completedDistance) match {
//        case Some(v) =>
//          val idx = cumulativeTaskDistances.indexOf(v)
//
//        case None =>
//          List(tasks.last)  // This should not happen.
//      }
//
//      List()
//    }
//
//    // Using the task id and temporarary id, retrieve all the labels.

  }
}
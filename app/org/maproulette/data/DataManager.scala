package org.maproulette.data

import java.sql.Connection
import java.text.SimpleDateFormat
import java.util.{Calendar, Date}
import javax.inject.{Inject, Singleton}

import anorm._
import anorm.SqlParser._
import org.maproulette.Config
import org.maproulette.actions.Actions
import org.maproulette.models.Task
import org.maproulette.models.utils.DALHelper
import play.api.Application
import play.api.db.Database

case class ActionSummary(available:Double,
                             fixed:Double,
                             falsePositive:Double,
                             skipped:Double,
                             deleted:Double,
                             alreadyFixed:Double)
case class UserSummary(distinctTotalUsers:Int,
                       avgUsersPerChallenge:Double,
                       activeUsers:Double,
                       avgActionsPerUser:ActionSummary,
                       avgActionsPerChallengePerUser:ActionSummary)
case class UserSurveySummary(distinctTotalUsers:Int,
                             avgUsersPerChallenge:Double,
                             activeUsers:Double,
                             answerPerUser:Double,
                             answersPerChallenge:Double)
case class ChallengeSummary(id:Long, name:String, actions:ActionSummary)
case class ChallengeActivity(date:Date, status:Int, statusName:String, count:Int)

/**
  * @author cuthbertm
  */
@Singleton
class DataManager @Inject()(config: Config, db:Database)(implicit application:Application) extends DALHelper {
  private val dateFormat = new SimpleDateFormat("yyyy-MM-dd")

  private def getDistinctUsers(projectFilter:String, survey:Boolean=false,
                               start:Option[Date]=None, end:Option[Date]=None)(implicit c:Connection) : Int = {
    SQL"""SELECT COUNT(DISTINCT osm_user_id) AS count
             FROM #${if (survey) {"survey_answers"} else {"status_actions"}}
             #${getDateClause(start, end)} #$projectFilter""".as(get[Option[Int]]("count").single).getOrElse(0)
  }

  private def getDistinctUsersPerChallenge(projectFilter:String, survey:Boolean=false,
                                           start:Option[Date]=None, end:Option[Date]=None)(implicit c:Connection) : Int = {
    SQL"""SELECT AVG(count) AS count FROM (
            SELECT #${if (survey) {"survey_id"} else {"challenge_id"}}, COUNT(DISTINCT osm_user_id) AS count
            FROM #${if (survey) {"survey_answers"} else {"status_actions"}}
            #${getDateClause(start, end)} #$projectFilter
            GROUP BY #${if (survey) {"survey_id"} else {"challenge_id"}}
          ) as t""".as(get[Option[Int]]("count").single).getOrElse(0)
  }

  private def getActiveUsers(projectFilter:String, survey:Boolean=false,
                             start:Option[Date]=None, end:Option[Date]=None)(implicit c:Connection) : Int = {
    SQL"""SELECT COUNT(DISTINCT osm_user_id) AS count
          FROM #${if (survey) {"survey_answers"} else {"status_actions"}}
          #${getDateClause(start, end)} #$projectFilter
          AND created::date BETWEEN current_date - INTERVAL '2 days' AND current_date"""
      .as(get[Option[Int]]("count").single).getOrElse(0)
  }

  def getUserSurveySummary(projectList:Option[List[Long]]=None, surveyId:Option[Long]=None,
                           start:Option[Date]=None, end:Option[Date]=None) = {
    db.withConnection { implicit c =>
      val surveyProjectFilter = surveyId match {
        case Some(id) => s"AND survey_id = $id"
        case None => projectList match {
          case Some(pl) => s"AND project_id IN (${pl.mkString(",")})"
          case None => ""
        }
      }

      val perUser:Double = SQL"""SELECT AVG(answered) AS answered FROM (
                            SELECT osm_user_id, COUNT(DISTINCT answer_id) AS answered
                            FROM survey_answers
                            #${getDateClause(start, end)} #$surveyProjectFilter
                            GROUP BY osm_user_id
                          ) AS t""".as(get[Option[Double]]("answered").single).getOrElse(0)
      val perSurvey:Double = SQL"""SELECT AVG(answered) AS answered FROM (
                              SELECT osm_user_id, survey_id, COUNT(DISTINCT answer_id) AS answered
                              FROM survey_answers
                              #${getDateClause(start, end)} #$surveyProjectFilter
                              GROUP BY osm_user_id, survey_id
                            ) AS t""".as(get[Option[Double]]("answered").single).getOrElse(0)

      UserSurveySummary(getDistinctUsers(surveyProjectFilter, true),
        getDistinctUsersPerChallenge(surveyProjectFilter, true),
        getActiveUsers(surveyProjectFilter, true),
        perUser,
        perSurvey
      )
    }
  }

  def getUserChallengeSummary(projectList:Option[List[Long]]=None, challengeId:Option[Long]=None,
                              start:Option[Date]=None, end:Option[Date]=None) : UserSummary = {
    db.withConnection { implicit c =>
      val challengeProjectFilter = challengeId match {
        case Some(id) => s"AND challenge_id = $id"
        case None => projectList match {
          case Some(pl) => s"AND project_id IN (${pl.mkString(",")})"
          case None => ""
        }
      }
      val actionParser = for {
        available <- get[Option[Double]]("available")
        fixed <- get[Option[Double]]("fixed")
        falsePositive <- get[Option[Double]]("false_positive")
        skipped <- get[Option[Double]]("skipped")
        deleted <- get[Option[Double]]("deleted")
        alreadyFixed <- get[Option[Double]]("already_fixed")
      } yield ActionSummary(available.getOrElse(0), fixed.getOrElse(0), falsePositive.getOrElse(0),
                            skipped.getOrElse(0), deleted.getOrElse(0), alreadyFixed.getOrElse(0))

      val perUser = SQL"""SELECT AVG(available) AS available, AVG(fixed) AS fixed, AVG(false_positive) AS false_positive,
                                  AVG(skipped) AS skipped, AVG(deleted) AS deleted, AVG(already_fixed) AS already_fixed
                              FROM (
                                SELECT osm_user_id,
                                    SUM(CASE status WHEN 0 THEN 1 ELSE 0 END) AS available,
                                    SUM(CASE status WHEN 1 THEN 1 ELSE 0 END) AS fixed,
                                    SUM(CASE status WHEN 2 THEN 1 ELSE 0 END) AS false_positive,
                                    SUM(CASE status WHEN 3 THEN 1 ELSE 0 END) AS skipped,
                                    SUM(CASE status WHEN 4 THEN 1 ELSE 0 END) AS deleted,
                                    SUM(CASE status WHEN 5 THEN 1 ELSE 0 END) AS already_fixed
                                FROM status_actions
                                #${getDateClause(start, end)} #$challengeProjectFilter
                                GROUP BY osm_user_id
                              ) AS t""".as(actionParser.*).head
      val perChallenge = SQL"""SELECT AVG(available) AS available, AVG(fixed) AS fixed, AVG(false_positive) AS false_positive,
                                        AVG(skipped) AS skipped, AVG(deleted) AS deleted, AVG(already_fixed) AS already_fixed
                                  FROM (
                                    SELECT osm_user_id, challenge_id,
                                        SUM(CASE status WHEN 0 THEN 1 ELSE 0 END) AS available,
                                        SUM(CASE status WHEN 1 THEN 1 ELSE 0 END) AS fixed,
                                        SUM(CASE status WHEN 2 THEN 1 ELSE 0 END) AS false_positive,
                                        SUM(CASE status WHEN 3 THEN 1 ELSE 0 END) AS skipped,
                                        SUM(CASE status WHEN 4 THEN 1 ELSE 0 END) AS deleted,
                                        SUM(CASE status WHEN 5 THEN 1 ELSE 0 END) AS already_fixed
                                    FROM status_actions
                                    #${getDateClause(start, end)} #$challengeProjectFilter
                                    GROUP BY osm_user_id, challenge_id
                                  ) AS t""".as(actionParser.*).head
      UserSummary(getDistinctUsers(challengeProjectFilter),
        getDistinctUsersPerChallenge(challengeProjectFilter),
        getActiveUsers(challengeProjectFilter),
        perUser,
        perChallenge)
    }
  }

  /**
    * Gets the summarized challenge activity
    *
    * @param projectList The projects to filter by default None, will assume all projects
    * @param challengeId The challenge to filter by default None, if set will ignore the projects parameter
    * @return
    */
  def getChallengeSummary(projectList:Option[List[Long]]=None, challengeId:Option[Long]=None,
                          start:Option[Date]=None, end:Option[Date]=None) : List[ChallengeSummary] = {
    db.withConnection { implicit c =>
      val parser = for {
        id <- int("tasks.parent_id")
        name <- str("challenges.name")
        available <- int("available")
        fixed <- int("fixed")
        falsePositive <- int("false_positive")
        skipped <- int("skipped")
        deleted <- int("deleted")
        alreadyFixed <- int("already_fixed")
      } yield ChallengeSummary(id, name, ActionSummary(available, fixed, falsePositive, skipped, deleted, alreadyFixed))
      val challengeFilter = challengeId match {
        case Some(id) if id != -1 => s"AND t.parent_id = $id"
        case _ => projectList match {
          case Some(pl) => s"AND c.parent_id IN (${pl.mkString(",")})"
          case None => ""
        }
      }
      SQL"""SELECT t.parent_id, c.name,
                          SUM(CASE t.status WHEN 0 THEN 1 ELSE 0 END) as available,
                        	SUM(CASE t.status WHEN 1 THEN 1 ELSE 0 END) as fixed,
                        	SUM(CASE t.status WHEN 2 THEN 1 ELSE 0 END) as false_positive,
                        	SUM(CASE t.status WHEN 3 THEN 1 ELSE 0 END) as skipped,
                        	SUM(CASE t.status WHEN 4 THEN 1 ELSE 0 END) as deleted,
                        	SUM(CASE t.status WHEN 5 THEN 1 ELSE 0 END) as already_fixed
                        FROM tasks t
                        INNER JOIN challenges c ON c.id = t.parent_id
                        WHERE challenge_type = #${Actions.ITEM_TYPE_CHALLENGE} #$challengeFilter
                        GROUP BY t.parent_id, c.name""".as(parser.*)
    }
  }

  /**
    * Gets the project activity (default will get data for all projects) grouped by timed action
    *
    * @param projectList The projects to filter by default None, will assume all projects
    * @param challengeId The challenge to filter by default None, if set will ignore the projects parameter
    * @param start The start date to filter by, default None will take all values. If start set and
    *              end not set, then will go from start till current day.
    * @param end The end date to filter by, default None will take all values. If start not set and
    *            end is set, then will ignore the end date
    */
  def getChallengeActivity(projectList:Option[List[Long]]=None, challengeId:Option[Long]=None,
                         start:Option[Date]=None, end:Option[Date]=None) : List[ChallengeActivity] = {
    db.withConnection { implicit c =>
      val parser = for {
        seriesDate <- date("series_date")
        status <- int("status")
        count <- int("count")
      } yield ChallengeActivity(seriesDate, status, Task.getStatusName(status).getOrElse("Unknown"), count)
      val challengeProjectFilter = challengeId match {
        case Some(id) => s"AND challenge_id = $id"
        case None => projectList match {
          case Some(pl) => s"AND project_id IN (${pl.mkString(",")})"
          case None => ""
        }
      }
      val dates = getDates(start, end)
      SQL"""
          SELECT series_date,
	          CASE WHEN status IS NULL THEN 0 ELSE status END AS status,
	          CASE WHEN count IS NULL THEN 0 ELSE count END AS count
          FROM (SELECT CURRENT_DATE + i AS series_date
	              FROM generate_series(date '#${dates._1}' - CURRENT_DATE, date '#${dates._2}' - CURRENT_DATE) i) d
          LEFT JOIN (
	          SELECT created::date, status, COUNT(status) AS count
            FROM status_actions
	          WHERE status IN (0, 1, 2, 3, 5) AND
              created::date BETWEEN '#${dates._1}' AND '#${dates._2}'
              #$challengeProjectFilter
	          GROUP BY created::date, status
            ORDER BY created::date, status ASC
          ) sa ON d.series_date = sa.created""".as(parser.*)
    }
  }

  private def getDates(start:Option[Date]=None, end:Option[Date]=None) : (String, String) = {
    val startDate = start match {
      case Some(s) => dateFormat.format(s)
      case None =>
        val cal = Calendar.getInstance()
        cal.add(Calendar.DATE, -7)
        dateFormat.format(cal.getTime)
    }
    val endDate = end match {
      case Some(e) => dateFormat.format(e)
      case None => dateFormat.format(new Date())
    }
    (startDate, endDate)
  }

  private def getDateClause(start:Option[Date]=None, end:Option[Date]=None) : String = {
    val dates = getDates(start, end)
    s"WHERE created::date BETWEEN '${dates._1}' AND '${dates._2}'"
  }
}

package com.adform.sprint

import org.scalatest._
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.time.{Seconds, Span}
import net.liftweb.json._
import scalaj.http.Http


class HighAvailabilityTest extends FlatSpec with Matchers with Eventually with IntegrationPatience
  with fixtures.Docker with fixtures.Mesos with fixtures.SprintBuilder {

  implicit override val patienceConfig = PatienceConfig(timeout = Span(60, Seconds), interval = Span(2, Seconds))

  "sprint" should "only have one leader" in withSprintInstances { (sprint1, sprint2) =>
    eventually {
      assert((sprint1.isLeader && !sprint2.isLeader) || (!sprint1.isLeader && sprint2.isLeader))
    }
  }

  it should "elect a new leader if the leading instance is stopped" in withSprintInstances { (sprint1, sprint2) =>

    eventually {
      assert(sprint1.isUp)
      assert(sprint2.isUp)
      assert(sprint1.isLeader || sprint2.isLeader)
    }

    val (leader, follower) = if (sprint1.isLeader) (sprint1, sprint2) else (sprint2, sprint1)

    eventually {
      assert(leader.isLeader)
      assert(!follower.isLeader)
    }

    docker.stopContainer(leader.id, 10)
    Thread.sleep(5000)

    eventually {
      assert(follower.isLeader === true)
    }
  }

  it should "detect leader changes when following" in withSprintInstances { (sprint1, sprint2, sprint3) =>

    eventually {
      assert(sprint1.isUp && sprint2.isUp && sprint3.isUp)
      assert(sprint1.isLeader || sprint2.isLeader || sprint3.isLeader)
    }

    val (Array(leader), Array(follower1, follower2)) = Array(sprint1, sprint2, sprint3).partition(c => c.isLeader)

    docker.stopContainer(leader.id, 10)
    Thread.sleep(5000)

    eventually {
      assert(follower1.isLeader || follower2.isLeader)
    }

    val (newLeader, newFollower) = if (follower1.isLeader) (follower1, follower2) else (follower2, follower1)

    val leaderStatusJson = Http(s"http://${newLeader.endpoint}/status").asString.body
    val leaderHostname = (parse(leaderStatusJson) \\ "leader" \\ "hostname" \\ classOf[JString]).head

    val followerStatusJson = Http(s"http://${newFollower.endpoint}/status").asString.body
    val followerLeader = (parse(followerStatusJson) \\ "leader" \\ "hostname" \\ classOf[JString]).head

    assert(leaderHostname == followerLeader)
  }

}
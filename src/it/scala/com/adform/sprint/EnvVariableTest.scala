package com.adform.sprint

import fixtures.ContainerRunDefinitions
import model.ContainerRunState

import scala.collection.JavaConverters._
import org.scalatest._
import org.scalatest.concurrent._
import org.scalatest.time.{Seconds, Span}


class EnvVariableTest extends FlatSpec with Matchers with Eventually with IntegrationPatience
  with fixtures.Docker with fixtures.Mesos with fixtures.SprintBuilder {

  implicit override val patienceConfig : PatienceConfig = PatienceConfig(timeout = Span(60, Seconds), interval = Span(2, Seconds))

  "sprint" should "set environmental variables for labels and HOST" in withSprintInstance { sprint =>

    val run = ContainerRunDefinitions.hwWebServer.copy(labels = Some(Map(
      "name" -> "test",
      "w31rd$_!@#$%^&*(" -> "aaaa!@#$%^&*()"
    )))

    val jobIdOption = sprint.postJob(run)
    jobIdOption.isDefined shouldBe true

    val jobId = jobIdOption.get

    eventually {
      sprint.getJobState(jobId) shouldBe Some(ContainerRunState.Running)
    }

    // get job container id
    val containerIdOption = for {
      leader      <- mesos.masters.find(_.isLeader)
      tasksIds    <- leader.tasksIds
      taskId      <- tasksIds.headOption
      containerId <- getContainerId(s"mesos-$taskId")
    } yield containerId

    containerIdOption.isDefined shouldBe true
    val containerId = containerIdOption.get

    // get container env variables
    val envVars = docker.inspectContainer(containerId).config().env().asScala.toList.map { v =>
      val s = v.split("=")
      s.head -> s.last
    }.toMap

    envVars.getOrElse("SPRINT_LABEL_NAME", "") shouldEqual "test"
    envVars.getOrElse("SPRINT_LABEL_W31RD___________", "") shouldEqual "aaaa!@#$%^&*()"

    mesos.slaves.map(_.hostname) should contain (envVars.getOrElse("HOST", ""))
  }

}

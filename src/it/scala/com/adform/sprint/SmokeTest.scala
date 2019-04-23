package com.adform.sprint

import org.scalatest._
import org.scalatest.concurrent._
import org.scalatest.time.{Seconds, Span}
import scalaj.http.Http


class SmokeTest extends FlatSpec with Matchers with Eventually with IntegrationPatience
  with fixtures.Docker with fixtures.Mesos with fixtures.SprintBuilder {

  implicit override val patienceConfig : PatienceConfig = PatienceConfig(timeout = Span(60, Seconds), interval = Span(2, Seconds))

  "sprint" should "respond to ping" in withSprintInstance { sprint =>
    eventually {
      val output = Http(s"http://${sprint.endpoint}/status/ping").asString
      output.isSuccess shouldBe true
      output.body should include("pong")
    }
  }

}

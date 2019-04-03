/*
 * Copyright (c) 2018 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.sprint
package mesos

import state._
import model.{Parameter => SParameter, PortMapping => SPortMapping, _}
import model.StorageUnit.conversions._
import java.util.UUID

import scala.collection.mutable.ListBuffer
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import org.apache.mesos.Protos.{Parameter => MParameter, _}
import org.apache.mesos.Protos.ContainerInfo.DockerInfo
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.PortMapping
import org.apache.mesos.Protos.Environment.Variable
import org.log4s._

import scala.annotation.tailrec


final case class SlaveNetworkingInfo(hostname: String, portMapping: Map[Int, Int])


final case class PortRange(start: Int, end: Int) {
  def portCount: Int = end - start + 1
}

final case class Resources(cpus: Double, memory: StorageUnit, ports: Seq[PortRange]) {
  def +(other: Resources) = Resources(cpus + other.cpus, memory + other.memory, ports ++ other.ports)
  def totalPortCount: Int = ports.map(_.portCount).sum
}

object Resources {
  val none = Resources(0, 0.bytes, Seq.empty)
}

sealed trait OfferResponse
final case class LaunchTasks(offers: Seq[Offer], tasks: Seq[TaskInfo]) extends OfferResponse
final case class DeclineOffers(offers: Seq[Offer]) extends OfferResponse


trait Framework {
  def considerOffers(offers: Seq[Offer]): Future[Seq[OfferResponse]]
  def updateTask(status: TaskStatus): Future[Option[ContainerRun]]
}

class SprintFramework(containerRunManager: ContainerRunManager)(implicit context: ExecutionContext) extends Framework {

  private[this] val log = getLogger

  private[this] val envVarNameIllegalChars = "[^0-9A-Z_]".r

  override def considerOffers(offers: Seq[Offer]): Future[Seq[OfferResponse]] = {
    containerRunManager
      .listContainerRuns(c => c.state == ContainerRunState.Created || c.state == ContainerRunState.WaitingForOffers)
      .map { runs =>

        val frameworkResponse = ListBuffer.empty[OfferResponse]
        val matches = matchOffers(offers, runs)

        matches.foreach {
          case (Nil, Some(run)) =>
            if (run.state != ContainerRunState.WaitingForOffers)
              containerRunManager.updateContainerRunState(id = run.id, state = ContainerRunState.WaitingForOffers)
          case (runOffers, Some(run)) =>
            val (task, jobNetwork) = makeTask(run, runOffers)
            frameworkResponse += LaunchTasks(runOffers, List(task))
            containerRunManager.updateContainerRunStateAndNetworking(
              id = run.id,
              state = ContainerRunState.Submitted,
              network = jobNetwork
            )
          case (unmatchedOffers, None) =>
            if (unmatchedOffers.nonEmpty)
              frameworkResponse += DeclineOffers(unmatchedOffers)
        }

        frameworkResponse.toList
      }
  }

  override def updateTask(status: TaskStatus): Future[Option[ContainerRun]] = {
    Try(UUID.fromString(status.getTaskId.getValue)) match {
      case Success(id) =>
        log.debug(s"Updating status of task ${status.getTaskId.getValue} to ${status.getState}")
        containerRunManager.updateContainerRunState(id, ContainerRunState.fromTaskState(status.getState))
      case Failure(f) =>
        log.error(s"Could not parse container run ID: ${f.getMessage}")
        Future.failed(f)
    }
  }

  def matchOffers(offers: Seq[Offer], runs: Seq[ContainerRun]): Map[Seq[Offer], Option[ContainerRun]] = {
    
    @tailrec
    def matchOffersRecursive(offers: Seq[Offer], runs: Seq[ContainerRun], matched: Map[Seq[Offer], Option[ContainerRun]]): Map[Seq[Offer], Option[ContainerRun]] = {
      runs match {
        case Nil => matched + (offers -> None)
        case run +: remainingRuns =>
          val (runOffers, unusedOffers) = findAndPartitionOffers(run, offers)
          matchOffersRecursive(unusedOffers, remainingRuns, matched + (runOffers -> Some(run)))
      }
    }

    matchOffersRecursive(offers, runs, Map())
  }

  // Partitions a list of offers into those that can be used to run a give container and those that are left or can't
  def findAndPartitionOffers(containerRun: ContainerRun, offers: Seq[Offer]): (Seq[Offer], Seq[Offer]) = {
    offers.find(o => canRun(containerRun, List(o))) match {
      case Some(o) => (List(o), offers.filter(_.getId.getValue != o.getId.getValue))
      case None => (List.empty[Offer], offers)
    }
  }

  def makeTask(containerRun: ContainerRun, offers: Seq[Offer]): (TaskInfo, Network) = {

    assert(offers.nonEmpty && offers.forall(o => o.getSlaveId.getValue == offers.head.getSlaveId.getValue))
    val slaveId = offers.head.getSlaveId
    val slaveHostname = offers.head.getHostname

    def buildScalarResource(name: String, value: Double): Resource = Resource.newBuilder()
      .setType(Value.Type.SCALAR)
      .setName(name)
      .setScalar(Value.Scalar.newBuilder().setValue(value).build())
      .build()

    def buildRangesResource(name: String, start: Int, end: Int): Resource = Resource.newBuilder()
      .setType(Value.Type.RANGES)
      .setName(name)
      .setRanges(Value.Ranges.newBuilder().addRange(Value.Range.newBuilder().setBegin(start).setEnd(end)))
      .build()

    def buildVariable(nameValue: (String, String)): Variable = Variable.newBuilder()
      .setName(nameValue._1)
      .setValue(nameValue._2)
      .build()

    def buildParameter(parameter: SParameter): MParameter = {
      val p = MParameter.newBuilder().setKey(parameter.key)
      if (parameter.value.isDefined) {
        p.setValue(parameter.value.get).build()
      } else {
        p.build()
      }
    }

    def buildPortMapping(mapping: SPortMapping): PortMapping = {
      log.debug(s"Adding mapping: ${mapping.containerPort} to ${mapping.hostPort}")
      PortMapping.newBuilder()
        .setContainerPort(mapping.containerPort)
        .setHostPort(mapping.hostPort.get)
        .build()
    }

    val availablePorts: Seq[Int] = for {
      offer <- offers
      resources <- offerResources(offer).toSeq
      range <- resources.ports
      port <- range.start to range.end
    } yield port

    val (portMappings, usedPorts): (List[SPortMapping], List[Int]) = containerRun.definition.container.portMappings
      .map(mappings => createPortMappings(mappings, availablePorts.toList))
      .getOrElse((List.empty, List.empty))

    val dockerInfo = {
      val builder = DockerInfo.newBuilder()
        .setImage(containerRun.definition.container.docker.image)
        .setForcePullImage(containerRun.definition.container.docker.forcePullImage.getOrElse(false))
        .addAllParameters(containerRun.definition.container.docker.parameters.getOrElse(List.empty[SParameter]).map(buildParameter).asJava)
        .setNetwork(DockerInfo.Network.BRIDGE)

      if (portMappings.nonEmpty) {
        log.debug(s"Mapping ${portMappings.length} ports")
        builder.addAllPortMappings(portMappings.map(buildPortMapping).asJava)
      }

      builder.build()
    }

    val containerInfo = ContainerInfo.newBuilder()
      .setDocker(dockerInfo)
      .setType(containerRun.definition.container.`type` match {
        case ContainerType.Docker => ContainerInfo.Type.DOCKER
        case ContainerType.Mesos => ContainerInfo.Type.MESOS
        case _ => throw new IllegalArgumentException("Container type must be DOCKER or MESOS")
      })

    val containerEnvVars = containerRun.definition.env.getOrElse(Map.empty[String, String])

    val portEnvVars = portMappings.zipWithIndex.flatMap { case (mapping, idx) =>
      mapping.hostPort.map { hostPort =>
        val portNameContainer = s"PORT_${mapping.containerPort}"
        val portNameIdx = s"PORT$idx"
        val portNameSpecific = mapping.name.map(name => s"PORT_${envVarNameIllegalChars.replaceAllIn(name.toUpperCase, "_")}")
        val portNames = portNameContainer :: portNameIdx :: portNameSpecific.toList
        portNames.map(_ -> hostPort.toString)
      }.getOrElse(Nil)
    }.toMap


    val labelEnvVars = containerRun.definition.labels.getOrElse(Map.empty[String, String]).map { case (name, value) =>
      val nameReplaced = envVarNameIllegalChars.replaceAllIn(name.toUpperCase, "_")
      ("SPRINT_LABEL_" + nameReplaced, value)
    }

    val hostEnvVar = "HOST" -> slaveHostname

    val environmentInfo = Environment.newBuilder()
      .addAllVariables((containerEnvVars ++ portEnvVars ++ labelEnvVars ++ Seq(hostEnvVar)).map(buildVariable).asJava)

    val commandInfo = CommandInfo.newBuilder()
      .setEnvironment(environmentInfo)

    if (containerRun.definition.cmd.isDefined) {
      commandInfo
        .setShell(true)
        .setValue(containerRun.definition.cmd.get)
    } else if (containerRun.definition.args.isDefined) {
      commandInfo
        .setShell(false)
        .addAllArguments(containerRun.definition.args.getOrElse(List.empty[String]).asJava)
    } else {
      commandInfo
        .setShell(false)
    }

    val taskName = containerRun.definition.labels.flatMap(l => l.get("name")).getOrElse(containerRun.id.toString)
    val taskInfo = TaskInfo.newBuilder()
      .setCommand(commandInfo.build())
      .setContainer(containerInfo)
      .setName(taskName)
      .setTaskId(TaskID.newBuilder().setValue(containerRun.id.toString).build())
      .setSlaveId(slaveId)
      .addResources(buildScalarResource("cpus", containerRun.definition.cpus.getOrElse(0.1)))
      .addResources(buildScalarResource("mem", containerRun.definition.mem.getOrElse(100L).toDouble))

    usedPorts.foreach { usedPort =>
      taskInfo.addResources(buildRangesResource("ports", usedPort, usedPort))
    }

    (taskInfo.build(), Network(slaveHostname, if (portMappings.nonEmpty) Some(portMappings) else None))
  }

  // Given all available ports from offers, create updated port mappings. Also, return a list of used ports.
  def createPortMappings(mappings: List[SPortMapping], availablePorts: List[Int]): (List[SPortMapping], List[Int]) = {

    def createPair(mapping: SPortMapping, freePorts: List[Int]): (SPortMapping, List[Int]) = {
      val requiredHostPort = mapping.hostPort
      val requiredContainerPort = mapping.containerPort

      val (assignedHostPort, remainingPorts) = requiredHostPort match {
        case Some(port) if port != 0 => (port, freePorts.diff(List(port)))
        case _ => (freePorts.head, freePorts.tail)
      }

      // should have the required port, since it was already checked in canRun(container, offers)
      assert(freePorts.size != remainingPorts.size)

      val assignedContainerPort = if (requiredContainerPort == 0) assignedHostPort else requiredContainerPort

      val updatedMapping = mapping.copy(containerPort = assignedContainerPort, hostPort = Some(assignedHostPort))
      (updatedMapping, remainingPorts)
    }

    def pairPorts(notUpdated: List[SPortMapping], updated: List[SPortMapping], freePorts: List[Int]): (List[SPortMapping], List[Int]) = notUpdated match {
      case mapping :: remainingNotMapped =>
        val (portsPair, remainingFreePorts) = createPair(mapping, freePorts)
        pairPorts(remainingNotMapped, portsPair :: updated, remainingFreePorts)
      case Nil => (updated.reverse, freePorts)
    }

    val (updatedMappings, remainingPorts) = pairPorts(mappings, Nil, availablePorts)

    (updatedMappings, availablePorts.diff(remainingPorts))
  }

  def canRun(container: ContainerRun, offers: Seq[Offer]): Boolean = {
    val offered = offers.flatMap(offerResources).fold(Resources.none)(_ + _)
    val required = containerResources(container)

    required.cpus <= offered.cpus &&
      required.memory <= offered.memory &&
      portsSatisfied(container.definition.container.portMappings, offered) &&
      offers.forall(areConstraintsMet(container, _))
  }

  def portsSatisfied(portMappings: Option[List[SPortMapping]], offered: Resources): Boolean = {
    def portInRange(port: Int, range: PortRange): Boolean = range.start <= port && port <= range.end

    def hasRequiredPorts(ranges: Seq[PortRange], hostPorts: List[Int]): Boolean = {
      hostPorts.forall { hostPort =>
        if (hostPort != 0) ranges.exists(range => portInRange(hostPort, range)) else true
      }
    }

    def noDuplicatePorts(ports: List[Int]): Boolean = {
      val without0Ports = ports.filter(_ != 0)
      without0Ports.distinct.size == without0Ports.size
    }

    portMappings match {
      case Some(mappings) =>
        val (hostPorts, containerPorts) =
          mappings.map(mapping => (mapping.hostPort.getOrElse(0), mapping.containerPort)).unzip

        mappings.length <= offered.totalPortCount &&
          noDuplicatePorts(hostPorts) &&
          noDuplicatePorts(containerPorts) &&
          hasRequiredPorts(offered.ports, hostPorts)
      case None => true
    }
  }

  def areConstraintsMet(container: ContainerRun, offer: Offer): Boolean = {
    container.definition.constraints.forall(_.forall(isConstraintMet(_, offer)))
  }

  def isConstraintMet(constraint: Constraint, offer: Offer): Boolean = {
    def fieldValue(field: String): Option[String] = {
      if (field == "hostname") {
        Some(offer.getHostname)
      } else {
        for {
          attribute <- offer.getAttributesList.asScala.find(a => a.getName == field)
          if attribute.hasText
          text = attribute.getText
          if text.hasValue
        } yield text.getValue
      }
    }

    constraint match {
      case LikeConstraint(field, arg) => fieldValue(field).exists(_ matches arg)
      case UnlikeConstraint(field, arg) => !fieldValue(field).exists(_ matches arg)
    }
  }

  def offerResources(offer: Offer): Option[Resources] = {
    val resources = offer.getResourcesList.asScala
    for {
      cpus <- resources.find(_.getName == "cpus").map(_.getScalar.getValue)
      mem <- resources.find(_.getName == "mem").map(_.getScalar.getValue.toLong)
      ports = for {
        portRanges <- resources.find(_.getName == "ports").toSeq
        range <- portRanges.getRanges.getRangeList.asScala
      } yield PortRange(range.getBegin.toInt, range.getEnd.toInt)
    } yield Resources(cpus, mem.megabytes, ports)
  }

  def containerResources(container: ContainerRun): Resources = {
    Resources(
      container.definition.cpus.getOrElse(0.1),
      container.definition.mem.getOrElse(100L).megabytes,
      Seq.empty)
  }
}

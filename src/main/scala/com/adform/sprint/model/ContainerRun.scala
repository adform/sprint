/*
 * Copyright (c) 2018 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.sprint
package model

import java.util.UUID
import org.apache.mesos.Protos.TaskState
import org.joda.time.DateTime


sealed trait ContainerType
object ContainerType {
  case object Docker extends ContainerType
  case object Mesos extends ContainerType
}

case class PortMapping(containerPort: Int, hostPort: Option[Int], name: Option[String], protocol: String = "tcp")

case class ContainerDefinition(docker: DockerDefinition, `type`: ContainerType, networks: Option[List[Network]],
                               portMappings: Option[List[PortMapping]])
case class DockerDefinition(image: String, forcePullImage: Option[Boolean], parameters: Option[List[Parameter]])

case class ContainerRunDefinition(
  cmd: Option[String],
  args: Option[List[String]],
  container: ContainerDefinition,
  cpus: Option[Double],
  mem: Option[Long],
  env: Option[Map[String, String]],
  labels: Option[Map[String, String]],
  constraints: Option[List[Constraint]]
)

case class Network(name: String, portMappings: Option[List[PortMapping]], labels: Option[Map[String, String]])
case class HostNetwork(host: String, portMappings: Option[List[PortMapping]])

case class ContainerRun(
   id: UUID,
   state: ContainerRunState,
   lastModified: DateTime,
   definition: ContainerRunDefinition,
   network: Option[HostNetwork]
)
case class ContainerRunList(runs: List[ContainerRun])

case class Parameter(key: String, value: Option[String])

sealed trait Constraint
case class LikeConstraint(key: String, argument: String) extends Constraint
case class UnlikeConstraint(key: String, argument: String) extends Constraint

sealed trait ContainerRunState {
  def isActive: Boolean
}

sealed trait ActiveContainerRunState extends ContainerRunState {
  val isActive = true
}

sealed trait InactiveContainerRunState extends ContainerRunState {
  val isActive = false
}

object ContainerRunState {
  case object Created          extends ActiveContainerRunState
  case object WaitingForOffers extends ActiveContainerRunState
  case object Submitted        extends ActiveContainerRunState
  case object Starting         extends ActiveContainerRunState
  case object Running          extends ActiveContainerRunState
  case object Finished         extends InactiveContainerRunState
  case object Failed           extends InactiveContainerRunState
  case object Killed           extends InactiveContainerRunState
  case object Lost             extends InactiveContainerRunState
  case object Staging          extends ActiveContainerRunState
  case object Error            extends InactiveContainerRunState
  case object Unknown          extends ActiveContainerRunState

  def fromTaskState(state: TaskState): ContainerRunState = state.getNumber match {
    case 0 => Starting
    case 1 => Running
    case 2 => Finished
    case 3 => Failed
    case 4 => Killed
    case 5 => Lost
    case 6 => Staging
    case 7 => Error
    case _ => Unknown
  }

  def fromString(stateStr: String): Option[ContainerRunState] = stateStr match {
    case "Created"          => Some(Created)
    case "WaitingForOffers" => Some(WaitingForOffers)
    case "Submitted"        => Some(Submitted)
    case "Starting"         => Some(Starting)
    case "Running"          => Some(Running)
    case "Finished"         => Some(Finished)
    case "Failed"           => Some(Failed)
    case "Killed"           => Some(Killed)
    case "Lost"             => Some(Lost)
    case "Staging"          => Some(Staging)
    case "Error"            => Some(Error)
    case "Unknown"          => Some(Unknown)
    case _                  => None
  }
}

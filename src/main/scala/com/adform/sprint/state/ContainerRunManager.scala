/*
 * Copyright (c) 2018 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.sprint
package state

import model._

import org.joda.time.DateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}


trait ContainerRunManager {
  def createContainerRun(definition: ContainerRunDefinition): Future[ContainerRun]
  def deleteContainerRun(id: UUID): Future[Option[UUID]]
  def getContainerRun(id: UUID): Future[Option[ContainerRun]]
  def updateContainerRunState(id: UUID, state: ContainerRunState): Future[Option[ContainerRun]]
  def updateContainerRunStateAndNetworking(id: UUID, state: ContainerRunState, network: HostNetwork): Future[Option[ContainerRun]]
  def listContainerRuns(filter: ContainerRun => Boolean): Future[Seq[ContainerRun]]
}

class StupidContainerRunManager(store: ContainerRunStore)(implicit context: ExecutionContext) extends ContainerRunManager {

  override def createContainerRun(definition: ContainerRunDefinition): Future[ContainerRun] = {
    val container = ContainerRun(UUID.randomUUID(), ContainerRunState.Created, DateTime.now, definition, None)
    store.storeContainerRun(container)
  }

  override def deleteContainerRun(id: UUID): Future[Option[UUID]] = store.deleteContainerRun(id)

  override def getContainerRun(id: UUID): Future[Option[ContainerRun]] = store.getContainerRun(id)

  override def updateContainerRunState(id: UUID, state: ContainerRunState): Future[Option[ContainerRun]] = {
    store.updateContainerRun(id, _.copy(state = state, lastModified = DateTime.now))
  }

  override def updateContainerRunStateAndNetworking(id: UUID, state: ContainerRunState, network: HostNetwork): Future[Option[ContainerRun]] = {
    store.updateContainerRun(id, _.copy(state = state, network = Some(network), lastModified = DateTime.now))
  }

  override def listContainerRuns(predicate: ContainerRun => Boolean): Future[Seq[ContainerRun]] =
    store.listContainerRuns().map(_.filter(predicate))
}

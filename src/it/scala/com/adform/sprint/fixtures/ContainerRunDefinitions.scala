package com.adform.sprint
package fixtures

import model._

object ContainerRunDefinitions {
  val hw = ContainerRunDefinition(
    None,
    None,
    ContainerDefinition(DockerDefinition("hello-world", None, None), ContainerType.Docker, None, None),
    None,
    None,
    None,
    None,
    None
  )

  // crccheck/hello-world image exposes 8000 port
  val hwWebServer = ContainerRunDefinition(
    None,
    None,
    ContainerDefinition(DockerDefinition("crccheck/hello-world", None, None), ContainerType.Docker, None, None),
    None,
    None,
    None,
    None,
    None
  )
}

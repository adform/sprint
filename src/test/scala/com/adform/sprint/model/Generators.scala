package com.adform.sprint
package model

import com.adform.sprint.mesos.{PortRange, Resources}
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary._
import org.joda.time.DateTime
import model.StorageUnit.conversions._

object Generators {

  val imageGen: Gen[String] = for {
    registry <- Gen.oneOf("evilcorp", "omnitech", "megastock")
    image <- Gen.oneOf("service", "database", "app")
    tag <- Gen.chooseNum(1, 100)
  } yield s"$registry/$image:$tag"

  val parameterGen: Gen[Parameter] = for {
    key <- Gen.alphaStr
    value <- Gen.option(Gen.alphaStr)
  } yield Parameter(key, value)

  val portMappingGen: Gen[PortMapping] = for {
    containerPort <- Gen.chooseNum(0, 65535)
    hostPort <- Gen.option(Gen.chooseNum(0, 65535))
    name <- Gen.option(Gen.alphaStr)
  } yield PortMapping(containerPort, hostPort, name)

  val containerDefinitionGen: Gen[ContainerDefinition] = for {
    image <- imageGen
    forcePull <- Gen.option(Gen.oneOf(true, false))
    parameters <- Gen.option(Gen.listOf(parameterGen))
    networks <- Gen.option(Gen.listOf(networkGen))
    portMappings <- Gen.option(Gen.listOf(portMappingGen))
  } yield ContainerDefinition(DockerDefinition(image, forcePull, parameters), ContainerType.Docker,networks,  portMappings)

  val kvGen: Gen[(String, String)] = for {
    key <- Gen.listOfN(Gen.chooseNum(3, 10).sample.get, Gen.alphaChar).map(_.mkString)
    value <- Gen.alphaStr
  } yield key -> value

  val portRangeListGen: Gen[List[PortRange]] = for {
    rangeLimits <- Gen.listOf(Gen.chooseNum(0, 65534))
    rangeLimitsSorted = rangeLimits.toSet.toList.sorted
    ranges = rangeLimitsSorted.zip(rangeLimitsSorted.drop(1)).map { case (start, end) => PortRange(start, end) }
  } yield ranges

  val resourceGen: Gen[Resources] = for {
    cpus <- Gen.chooseNum(0.01, 40.0)
    mem <- Gen.chooseNum(100, 256000)
    ports <- portRangeListGen
  } yield Resources(cpus, mem.megabytes, ports)

  implicit lazy val arbResources: Arbitrary[Resources] = Arbitrary(resourceGen)

  val constraintGen: Gen[Constraint with Product with Serializable] = for {
    field <- Gen.oneOf(Gen.const("hostname"), Gen.alphaStr)
    arg <- Gen.alphaStr
    constraint <- Gen.oneOf(LikeConstraint.apply _, UnlikeConstraint.apply _)
  } yield constraint(field, arg)

  val networkGen: Gen[Network] = for {
    name <- Gen.alphaStr
    portMappings <- Gen.option(Gen.listOf(portMappingGen))
    labels <- Gen.option(Gen.mapOf(kvGen))
  } yield Network(name, portMappings, labels)

  val hostNetworkGen: Gen[HostNetwork] = for {
    hostname <- Gen.alphaStr
    portMappings <- Gen.option(Gen.listOf(portMappingGen))
  } yield HostNetwork(hostname, portMappings)

  val containerRunDefinitionGen: Gen[ContainerRunDefinition] = for {
    cmd <- Gen.option(Gen.alphaStr)
    args <- Gen.option(Gen.listOf(Gen.alphaStr))
    container <- containerDefinitionGen
    resources <- resourceGen
    cpus <- Gen.option(resources.cpus)
    mem <- Gen.option(resources.memory.inMegabytes)
    env <- Gen.option(Gen.mapOf(kvGen))
    labels <- Gen.option(Gen.mapOf(kvGen))
    constraints <- Gen.option(Gen.listOf(constraintGen))
  } yield ContainerRunDefinition(cmd, args, container, cpus, mem, env, labels, constraints)

  implicit lazy val arbContainerRunDefinition: Arbitrary[ContainerRunDefinition] = Arbitrary(containerRunDefinitionGen)

  def containerRunGen: Gen[ContainerRun] = for {
    id <- Gen.uuid
    millis <- Gen.chooseNum(new DateTime(2015, 1, 1, 0, 0).getMillis, new DateTime(2016, 1, 1, 0, 0).getMillis)
    time = new DateTime(millis)
    state <- Gen.oneOf(Seq(ContainerRunState.Finished, ContainerRunState.Failed, ContainerRunState.Running))
    definition <- arbitrary[ContainerRunDefinition]
    network <- Gen.option(hostNetworkGen)
  } yield ContainerRun(id, state, time, definition, network)

  implicit lazy val arbContainerRun: Arbitrary[ContainerRun] = Arbitrary(containerRunGen)
}

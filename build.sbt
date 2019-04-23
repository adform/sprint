name := "sprint"
organization := "com.adform"
organizationName := "Adform"
startYear := Some(2018)
licenses += ("MPL-2.0", new URL("http://mozilla.org/MPL/2.0/"))

scalaVersion  := "2.12.8"
scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8")

val akkaVersion = "2.5.21"
val akkaHttpVersion = "10.1.5"
val curatorVersion = "2.12.0"
val mesosVersion = "1.7.1"
val zookeeperVersion = "3.4.13"
val mesosPkgVersion = s"$mesosVersion-2.0.1"

libraryDependencies ++= Seq(
  "org.apache.mesos"         % "mesos"                 % mesosVersion,
  "com.typesafe.akka"       %% "akka-actor"            % akkaVersion,
  "com.typesafe.akka"       %% "akka-slf4j"            % akkaVersion,
  "com.typesafe.akka"       %% "akka-stream"           % akkaVersion,
  "com.typesafe.akka"       %% "akka-http"             % akkaHttpVersion,
  "com.typesafe.akka"       %% "akka-http-spray-json"  % akkaHttpVersion,
  "org.apache.curator"       % "curator-framework"     % curatorVersion   exclude("log4j", "log4j"),
  "org.apache.curator"       % "curator-recipes"       % curatorVersion   exclude("log4j", "log4j"),
  "com.typesafe"             % "config"                % "1.3.3",
  "com.github.nscala-time"  %% "nscala-time"           % "2.22.0",
  "org.log4s"               %% "log4s"                 % "1.7.0",
  "ch.qos.logback"           % "logback-classic"       % "1.2.3",
  "org.slf4j"                % "log4j-over-slf4j"      % "1.7.26",
  "org.scalatest"           %% "scalatest"             % "3.0.6"         % "test,it",
  "com.typesafe.akka"       %% "akka-http-testkit"     % akkaHttpVersion % "test",
  "org.mockito"              % "mockito-core"          % "2.25.0"        % "test",
  "org.scalacheck"          %% "scalacheck"            % "1.14.0"        % "test",
  "org.scalaj"              %% "scalaj-http"           % "2.4.1"         % "it",
  "net.liftweb"             %% "lift-json"             % "3.1.1"         % "it",
  "com.spotify"              % "docker-client"         % "8.15.1"        % "it"
)

dependencyOverrides ++= Seq(
  "com.typesafe.akka" %% "akka-actor"  % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion
)

lazy val root = (project in file("."))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .enablePlugins(PackPlugin)
  .enablePlugins(DockerPlugin)
  .enablePlugins(GitVersioning)
  .enablePlugins(BuildInfoPlugin)

val zookeeperImage = settingKey[String]("Zookeeper image used in it tests")
zookeeperImage := s"zookeeper:$zookeeperVersion"

val mesosMasterImage = settingKey[String]("Mesos master image used in it tests")
mesosMasterImage := s"mesosphere/mesos-master:$mesosVersion"

val mesosSlaveImage = settingKey[String]("Mesos slave image used in it tests")
mesosSlaveImage := s"mesosphere/mesos-slave:$mesosVersion"

buildInfoPackage := s"${organization.value}.${name.value}"
buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, git.gitHeadCommit, zookeeperImage, mesosMasterImage, mesosSlaveImage)

git.useGitDescribe := true

dockerfile in docker := {

  val bin = new File("bin")
  val (depLib, appLib) = packAndSplitJars.value
  val ramlPath = raml.value

  new Dockerfile {

    from("openjdk@sha256:143e37a40011243684acf5e0cca99db04cf2675dae54aefcc464d616916dd27b") // 8u181-jre
    runRaw("java -version 2>&1 | grep 1.8.0_181") // validate java version

    env("TERM" -> "xterm")

    runRaw(
      "wget http://ftp.de.debian.org/debian/pool/main/o/openssl/libssl1.0.0_1.0.1t-1+deb8u8_amd64.deb && " +
      "dpkg -i libssl1.0.0_1.0.1t-1+deb8u8_amd64.deb && rm -rf libssl1.0.0_1.0.1t-1+deb8u8_amd64.deb &&" +
      "echo 'deb http://repos.mesosphere.io/ubuntu/ trusty main' > /etc/apt/sources.list.d/mesosphere.list && " +
      "apt-key adv --keyserver keyserver.ubuntu.com --recv E56151BF && " +
      "apt-get -y update && " +
      s"apt-get --no-install-recommends -y install mesos=$mesosPkgVersion && "+
      "apt-get clean && rm -rf /var/lib/apt/lists/*"
    )

    copy(depLib, s"/opt/${name.value}/lib") // add dependencies first to maximize docker cache usage
    copy(appLib, s"/opt/${name.value}/lib")
    copy(ramlPath, s"/opt/${name.value}/doc")
    copy(bin, s"/opt/${name.value}/bin")

    expose(9090)

    cmd(s"/opt/${name.value}/bin/run.sh")

    label(
      "mesos.version" -> mesosVersion,
      "sprint.version" -> version.value,
      "sprint.git.hash" -> git.gitHeadCommit.value.get
    )
  }
}

imageNames in docker := Seq(
  ImageName(
    namespace = Some("adform"),
    repository = name.value,
    tag = Some(version.value)
  )
)

lazy val packAndSplitJars = taskKey[(File, File)]("Runs pack and splits out the application jars from the external dependency jars")

packAndSplitJars := {
  val scalaMajorVersion = scalaVersion.value.split('.').take(2).mkString(".")
  val mainJar = s"${name.value}_$scalaMajorVersion-${version.value}.jar"
  val libDir = pack.value / "lib"
  val appLibDir = pack.value / "app-lib"
  appLibDir.mkdirs()
  IO.move(libDir / mainJar, appLibDir / mainJar)
  (libDir, appLibDir)
}

lazy val raml = taskKey[File]("Generates HTML output from the RAML documentation")

raml := {
  import scala.sys.process._
  val out = new File("target/raml")
  out.mkdirs()
  ("raml2html src/main/raml/api.raml" #> file("target/raml/api.html")).!
  out
}

fork in run := true
cancelable in Global := true

test in assembly := {}
test in IntegrationTest := (test in IntegrationTest).dependsOn(docker).value

// Prevents slf4j replay warnings during tests
testOptions += sbt.Tests.Setup(cl =>
  cl.loadClass("org.slf4j.LoggerFactory")
    .getMethod("getLogger", cl.loadClass("java.lang.String"))
    .invoke(null,"ROOT")
)

# Sprint [![CircleCI](https://circleci.com/gh/adform/sprint.svg?style=svg)](https://circleci.com/gh/adform/sprint) [![](https://images.microbadger.com/badges/version/adform/sprint.svg)](https://hub.docker.com/r/adform/sprint/)

Sprint is an [Apache Mesos](https://mesos.apache.org/) framework that lets you run one-off tasks on your mesos cluster using a dead simple REST API. It can be used to run various adhoc tasks or as a building block for other systems such as job schedulers.

## Getting Started

Sprint is distributed as a docker image and the preferred method of running it is via [Marathon](https://mesosphere.github.io/marathon/), an example app definition would look something like the following:

```json
{
  "id": "/frameworks/sprint",
  "cpus": 0.2,
  "mem": 1024,
  "instances": 1,
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "adform/sprint:0.2.0",
      "portMappings": [
        { "containerPort": 9090, "hostPort": 0, "name": "api"   },
        { "containerPort": 0,    "hostPort": 0, "name": "mesos" },
        { "containerPort": 0,    "hostPort": 0, "name": "jmx"   }
      ]
    }
  },
  "env": {
    "MESOS_CONNECT": "zk://zookeeper:2181/mesos",
    "ZOOKEEPER_CONNECT": "zk://zookeeper:2181/sprint",
    "JAVA_OPTS": "-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -XX:MaxRAMFraction=1"
  },
  "healthChecks": [
    {
      "protocol": "HTTP",
      "portIndex": 0,
      "path": "/status/ping",
      "maxConsecutiveFailures": 3
    }
  ]
}
```

The API is exposed on port 9090 in the container, sprint also needs a port to communicate with mesos and optionally a port to accept JMX connections for debugging. The last two ports are randomized, it is only important that they match inside the container and on the host.

The only required configuration options are specified via the environmental variables `MESOS_CONNECT`, which is the connection string to the mesos master and `ZOOKEEPER_CONNECT`, which specifies a zookeeper path where sprint will store its state. Any configuration setting from `application.conf` can be overriden by specifying the `SPRINT_OPTS` environmental variable and setting e.g. `-Dmesos.framework.name=my-sprint`.

## Usage

Once sprint is up and running you can submit a container run by sending a `POST` request to `/v1/runs`, e.g.

```bash
$ cat > run.json <<EOL
{
  "labels": {
    "name": "testing",
    "tier": "local"
  },
  "cpus": 0.1,
  "mem": 100,
  "container": {
    "type": "DOCKER",
    "docker": {
      "image": "hello-world:latest"
    }
  }
}
EOL

$ curl -s -X POST -H "Content-Type: application/json" --data @run.json http://localhost:9090/v1/runs | jq .id
"08028217-5270-41dd-81c6-de2dcfd832cb"

$ curl -s http://localhost:9090/v1/runs/08028217-5270-41dd-81c6-de2dcfd832cb | jq .state
"Finished"
```

The response will contain a unique ID assigned to the task, which you can later use to check the status of the task by `GET`ing `/v1/runs/{id}`. If the task fails sprint will not attempt to restart it. You can set arbitrary labels for tasks which later can be used to filter for specific tasks. The label `name` is special in that if set, it will be used as the mesos task name, otherwise the ID will be used. The full documentation about all available methods can be found by accessing the `/docs` endpoint.
See the [JSON schema](src/main/raml/schemas/ContainerRunDefinition.json) for detailed information on what can be submitted to the API.

## Features

Networking and port mappings are defined similarly to [Marathon 1.5](https://mesosphere.github.io/marathon/docs/networking.html).

High availability is supported by simply scaling sprint to more instances, one of them will be elected master and all follower instances will simply proxy their requests to the master.

The state store is periodically cleaned up, by default container runs are kept for 7 days or until a cap of 2000 runs is reached. Both settings are tunable.

## License

Sprint is licensed under the [Mozilla Public License Version 2.0](https://www.mozilla.org/en-US/MPL/2.0/).

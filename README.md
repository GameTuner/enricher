
# GameTuner Enricher

## Overview

GameTuner Enricher is a set of applications and libraries for processing raw GameTuner events into validated and enriched GameTuner events. This project is a fork of the [Snowplow Enrich project][snowplow-enrich]. 

GameTuner Enricher ingest raw events from Google PubSub topic, enrich them and publish them back to PubSub topic. Faild enrichemts are sent to bad events PubSub topic. The enricher is build to be deployed on GCP (Google Cloud Platform). For that reason only PubSub module of application is supported.

## Requirements

For building and running the application you need:

- sbt >= 2.6
- scala = 2.12.10
- jdk = 11

### Dependencies

GameTuner Enricher application is dependent on [GameTuner Metadata][gametuner-metadata] service. MetaData service is used for fetching configurations of applications for enricher. If MetaData service is not available, enricher will not start.

## Installation

### Running locally

#### Pre-requisites

Befor running the application locally, you should start GameTuner MetaData service. You can find instructions for running MetaData service in [GameTuner MetaData][gametuner-metadata] repository.

#### Configuration

Enricher requires three configuration files to be present in the system.
- path to enrichments file - example file can be found in [`config/demo/dev-enrichments`][dev-enrichments] directory.
- [`iglu_resolver.json`][iglu-resolver] - Iglu resolver configuration file. Example file can be found in `config` directory. Here you should specify the Iglu server URL or URI to GCP bucket that contains event schemas.
- [`pubsub.hocon`][pubsub-hocon] - Enricher configuration file. Example file can be found in `config` directory. Here you should specify the PubSub topics and subscription names. Also, you should specify endpoint to MetaData service.

#### Setup

We developed application using IntelliJ IDEA IDE, so we recommend you to use it for running in local. To run application locally you should follow next steps:
- Import project into IntelliJ IDEA from sources. 
- If needed, you can change Scala version in `File -> Project Structure`.
- Run `sbt 'project pubsub' assembly` to build the application.
- If JDK 11 is not the default JDK, run `sbt -java-home '<path-to-jdk-11>' 'project pubsub' assembly`
- After that you can run the application by running the main class [`Main.scala`][run-class] in `pubsub` module or by running the jar file with the following command:

```bash
java -jar <path-to-jar>  \
  --enrichments /config/demo/dev-enrichments \
  --iglu-config /config/demo/iglu_resolver.json \
  --config /config/demo/pubsub.hocon
```

### Running on GCP

For deploying the application on GCP, you should first build the application by running google cloud build `gcloud builds submit --config=cloudbuild.yaml .`. Script submits docker image to GCP artifact registry. Once the image is submitted, you should deploy the application on GCP. You can do that by running terraform script in [GameTuner terraform][gametuner-terraform] project.

## Licence

This project is fork of [Snowplow Enrich version 3.3.2][snowplow-enrich-3.3.2], that is licenced under Apache 2.0 Licence.

The GameTuner Enricher is copyright 2022-2024 AlgebraAI.

GameTuner Enricher is released under the [Apache 2.0 License][license].

[snowplow-enrich]:https://github.com/snowplow/enrich
[gametuner-metadata]:https://github.com/GameTuner/metadata.git
[run-class]:modules/pubsub/src/main/scala/com/snowplowanalytics/snowplow/enrich/pubsub/Main.scala
[dev-enrichments]:config/demo/dev-enrichments
[iglu-resolver]:config/demo/iglu_resolver.json
[pubsub-hocon]:config/demo/pubsub.hocon
[snowplow-enrich-3.3.2]:https://github.com/snowplow/enrich/releases/tag/3.3.2
[gametuner-terraform]:https://github.com/GameTuner/gametuner-terraform-gcp.git
[license]: https://www.apache.org/licenses/LICENSE-2.0
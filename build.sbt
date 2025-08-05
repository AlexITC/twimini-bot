ThisBuild / scalaVersion := "3.7.1"

Global / onChangedBuildSource := ReloadOnSourceChanges

val http4sVersion = "0.23.30"

// Define the "server" module
lazy val server = project
  .in(file("server"))
  .settings(
    name := "twimini-bot-server",
    // fork the JVM process for the server to keep it running
    fork := true,
    libraryDependencies ++= Seq(
      "com.twilio.sdk" % "twilio" % "10.9.2",
      "com.lihaoyi" %% "cask" % "0.10.2",
      "com.lihaoyi" %% "upickle" % "4.2.1",
      "com.lihaoyi" %% "scalatags" % "0.13.1",
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      "org.slf4j" % "slf4j-api" % "2.0.17",
      "com.google.genai" % "google-genai" % "1.10.0",
      "co.fs2" %% "fs2-core" % "3.12.0",
      "co.fs2" %% "fs2-io" % "3.12.0",
      "com.microsoft.onnxruntime" % "onnxruntime" % "1.22.0"
    ),
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion
    )
  )

lazy val root = project
  .in(file("."))
  .aggregate(server) // Aggregate the server module
  .settings(
    name := "twimini-bot"
  )

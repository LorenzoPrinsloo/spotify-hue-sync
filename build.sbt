val scala3Version = "3.3.7"
val circeVersion = "0.14.15"
val http4sVersion = "0.23.33"
val fs2Version = "3.12.2"
val catsEffectVersion = "3.6.3"
val catsVersion       = "2.13.0"
val pureConfigVersion = "0.17.10"
val scandiumVersion = "3.14.0"
val refinedVersion = "0.11.3"
val laminarVersion = "17.2.1"
val munitVersion = "1.0.0"

ThisBuild / scalaVersion := scala3Version
// Scalafix's semantic rules read the SemanticDB the compiler writes alongside the classes.
ThisBuild / semanticdbEnabled := true
ThisBuild / scalafixDependencies ++= Seq(
  "com.github.xuwei-k" %% "scalafix-rules"     % "0.6.30",
  "org.typelevel"      %% "typelevel-scalafix" % "0.6.0"
)

// `sbt fix` applies the rules' rewrites; `sbt lint` fails if anything is left; `sbt check` lints, then tests.
addCommandAlias("fix", "scalafixAll")
addCommandAlias("lint", "scalafixAll --check")
addCommandAlias("check", ";lint ;test")

lazy val lintSettings = Seq(
  Test / scalafixConfig := Some((ThisBuild / baseDirectory).value / ".scalafix.test.conf")
)

/** The settings and colours, shared by the app and the settings page in the browser. */
lazy val model = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("model"))
  .settings(lintSettings)
  .settings(
    name := "spotify-hue-sync-model",
    scalacOptions ++= Seq("-deprecation", "-feature"),
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core"   % catsVersion,
      "io.circe"      %%% "circe-core"  % circeVersion,
      "eu.timepit"    %%% "refined"     % refinedVersion,
      "org.scodec"    %%% "scodec-bits" % "1.2.4",
      "org.scodec"    %%% "scodec-core" % "2.3.3"
    )
  )

/** The settings page, compiled to JavaScript and served by the app. */
lazy val ui = project
  .in(file("ui"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(model.js)
  .settings(lintSettings)
  .settings(
    name := "spotify-hue-sync-ui",
    scalacOptions ++= Seq("-deprecation", "-feature"),
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "com.raquo"     %%% "laminar"      % laminarVersion,
      "io.circe"      %%% "circe-parser" % circeVersion,
      "org.scalameta" %%% "munit"        % munitVersion % Test
    )
  )

lazy val root = project
  .in(file("."))
  .dependsOn(model.jvm)
  .aggregate(model.jvm, model.js, ui)
  .settings(lintSettings)
  .settings(
    name         := "spotify-hue-sync",
    version      := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,

    // `sbt run` starts the app only, not the settings page's own (browser-only) entry point.
    run / aggregate := false,

    // The compiled settings page is served from the classpath, at ui/main.js beside ui/index.html.
    Compile / resourceGenerators += Def.task {
      val linked = (ui / Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
      val _ = (ui / Compile / fastLinkJS).value
      val target = (Compile / resourceManaged).value / "ui"
      IO.copyDirectory(linked, target, overwrite = true)
      (target ** "*.js*").get
    }.taskValue,

    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-indent"
    ),

    libraryDependencies ++= Seq(
      // Category Theory / Typeclasses
      "org.typelevel" %% "cats-core" % catsVersion,
      // Functional Effect System
      "org.typelevel" %% "cats-effect" % catsEffectVersion,

      // Streaming & Networking
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io"   % fs2Version,

      // HTTP Client/Server
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl"          % http4sVersion,
      "org.http4s" %% "http4s-circe"        % http4sVersion,

      // JSON
      "io.circe" %% "circe-core"    % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser"  % circeVersion,

      // Binary Protocol for Hue UDP
      "org.scodec" %% "scodec-bits" % "1.2.4",
      "org.scodec" %% "scodec-core" % "2.3.3",

      // HOCON Config files
      "com.github.pureconfig" %% "pureconfig-core" % pureConfigVersion,
      "com.github.pureconfig" %% "pureconfig-generic-scala3" % pureConfigVersion,

      // Logging
      "org.typelevel" %% "log4cats-slf4j"  % "2.7.1",
      "ch.qos.logback" % "logback-classic" % "1.5.32",

      // DTLS Networking
      "org.eclipse.californium" % "scandium" % scandiumVersion,
      "org.eclipse.californium" % "element-connector" % scandiumVersion,

      "eu.timepit" %% "refined" % refinedVersion,

      // Testing
      "org.scalameta" %% "munit"            % munitVersion % Test,
      "org.scalameta" %% "munit-scalacheck" % munitVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.0.0" % Test,
      "org.typelevel" %% "cats-effect-testkit" % catsEffectVersion % Test
    )
  )
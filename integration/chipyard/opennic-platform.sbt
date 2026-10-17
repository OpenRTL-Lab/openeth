// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: integration/chipyard/opennic-platform.sbt
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// OpenNIC平台适配独立编译/测试入口；仅引用公开平台依赖。
lazy val opennicPlatform = (project in file("opennic/platform"))
  .dependsOn(LocalProject("rocketchip"))
  .settings(scalaVersion := "2.13.12")
  .settings(libraryDependencies += "org.chipsalliance" %% "chisel" % "6.5.0")
  .settings(addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % "6.5.0" cross CrossVersion.full))
  .settings(libraryDependencies ++= Seq(
    "edu.berkeley.cs" %% "chiseltest" % "6.0.0" % Test,
    "org.scalatest" %% "scalatest" % "3.2.19" % Test))
  .settings(Compile / unmanagedSourceDirectories += file("opennic/core/src/main/scala"))
  // Diplomacy使用全局构建作用域，测试套件不能并行elaborate。
  .settings(Test / parallelExecution := false)

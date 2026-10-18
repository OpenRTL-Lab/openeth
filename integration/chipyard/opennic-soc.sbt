// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: integration/chipyard/opennic-soc.sbt
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
lazy val opennicSoc = (project in file("opennic/soc"))
  .dependsOn(LocalProject("chipyard"), LocalProject("opennicPlatform"))
  .settings(scalaVersion := "2.13.12")
  .settings(libraryDependencies += "org.chipsalliance" %% "chisel" % "6.5.0")
  .settings(addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % "6.5.0" cross CrossVersion.full))

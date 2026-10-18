// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: integration/chipyard/soc/OpenEthSystem.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
package opennic.soc

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.{BaseSubsystem, SBUS}
import freechips.rocketchip.tilelink._
import chipyard.iobinders.{Port, SPIPort, OverrideLazyIOBinder}
import chipyard.harness.{HarnessBinder, HasHarnessInstantiators}
import sifive.blocks.devices.spi.HasPeripherySPI
import opennic.FrameBeat
import opennic.platform.{OpenNicTL, OpenNicParams}

case object OpenNicKey extends Field[Option[OpenNicParams]](None)
class OpenNicPins extends Bundle {
  val tx = Decoupled(new FrameBeat)
  val rx = Flipped(Decoupled(new FrameBeat))
}

trait CanHaveOpenNic { this: BaseSubsystem =>
  val openNicPort = p(OpenNicKey).map { params =>
    val sbus = locateTLBusWrapper(SBUS)
    val nic = LazyModule(new OpenNicTL(params))
    nic.clockNode := sbus.fixedClockNode
    // Avoid peripheral atomic read-modify-write emulation on destructive CSRs.
    sbus.coupleTo("opennic-csr") {
      nic.csrNode := TLBuffer() := TLFragmenter(4, sbus.blockBytes, holdFirstDeny = true) :=
        TLWidthWidget(sbus.beatBytes) := _
    }
    sbus.coupleFrom("opennic-dma") { _ := TLBuffer() := TLWidthWidget(8) := nic.dmaNode }
    ibus.fromSync := nic.intNode
    InModuleBody {
      val port = IO(new OpenNicPins).suggestName("opennic")
      port.tx <> nic.module.tx
      nic.module.rx <> port.rx
      port
    }
  }
}

case class OpenNicPort(getIO: () => OpenNicPins) extends Port[OpenNicPins]
class WithOpenNicPorts extends OverrideLazyIOBinder({ (system: CanHaveOpenNic) =>
  InModuleBody {
    system.openNicPort.map { inner =>
      val port = IO(new OpenNicPins).suggestName("opennic")
      port <> inner
      (Seq(OpenNicPort(() => port)), Nil)
    }.getOrElse((Nil, Nil))
  }
})

// Native-stream simulation only; this does not test the Ethernet wire path.
class WithOpenNicLoopback extends HarnessBinder({
  case (_: HasHarnessInstantiators, port: OpenNicPort, _: Int) =>
    port.getIO().rx <> port.getIO().tx
})

// Unused SPI inputs in the simulation harness; not a memory-card model.
class WithUnusedSPI extends HarnessBinder({
  case (_: HasHarnessInstantiators, port: SPIPort, _: Int) =>
    port.io.dq(0).i := false.B
    port.io.dq(1).i := true.B
    port.io.dq(2).i := true.B
    port.io.dq(3).i := true.B
})

// Generic SPI-mode memory-card description; no board pinout is embedded.
private class PollingMMCDevice(spi: Device, maxMHz: Double = 1)
    extends SimpleDevice("mmc", Seq("mmc-spi-slot")) {
  override def parent = Some(spi)
  override def describe(resources: ResourceBindings): Description = {
    val Description(name, mapping) = super.describe(resources)
    Description(name, mapping ++ Map(
      "voltage-ranges" -> Seq(ResourceInt(3300), ResourceInt(3300)),
      "disable-wp" -> Nil, "broken-cd" -> Nil,
      "spi-max-frequency" -> Seq(ResourceInt((maxMHz * 1000000).toInt))))
  }
}
class WithPollingSPI extends OverrideLazyIOBinder({ (system: HasPeripherySPI) =>
  if (system.tlSpiNodes.nonEmpty) ResourceBinding {
    Resource(new PollingMMCDevice(system.tlSpiNodes.head.device), "reg").bind(ResourceAddress(0))
  }
  InModuleBody {
    (system.spi.zipWithIndex.map { case (s, i) =>
      val io = IO(s.cloneType).suggestName(s"spi_$i")
      io <> s
      SPIPort(() => io)
    }, Nil)
  }
})

class OpenNicSystem(implicit p: Parameters) extends chipyard.ChipyardSystem
  with testchipip.boot.CanHavePeripheryCustomBootPin
  with testchipip.boot.CanHavePeripheryBootAddrReg
  with testchipip.serdes.CanHavePeripheryTLSerial
  with sifive.blocks.devices.uart.HasPeripheryUART
  with sifive.blocks.devices.spi.HasPeripherySPIFlash
  with sifive.blocks.devices.spi.HasPeripherySPI
  with chipyard.eth.CanHavePeripheryEth
  with chipyard.clocking.HasChipyardPRCI
  with chipyard.clocking.CanHaveClockTap
  with CanHaveOpenNic {
  override lazy val module = new OpenNicSystemModule(this)
}
class OpenNicSystemModule(l: OpenNicSystem) extends chipyard.ChipyardSystemModule(l)

class WithOpenNicSystem extends Config((site, here, up) => {
  case chipyard.BuildSystem => (p: Parameters) => new OpenNicSystem()(p)
  case OpenNicKey => Some(OpenNicParams())
})
class WithOpenNicMemoryWidth extends Config((site, here, up) => {
  case freechips.rocketchip.subsystem.MemoryBusKey =>
    up(freechips.rocketchip.subsystem.MemoryBusKey).copy(beatBytes = 64)
})

// Digital system parameters. Clock generation and the DDR controller are external.
class OpenEthReferenceConfig extends Config(
  new WithOpenNicSystem ++
  new WithOpenNicPorts ++
  new WithOpenNicLoopback ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new WithPollingSPI ++
  new WithUnusedSPI ++
  new chipyard.config.WithSPI(address = 0x10031000L) ++
  new chipyard.eth.WithEthTiedOff ++
  new chipyard.eth.WithEthPunchthrough ++
  new chipyard.eth.WithEth(address = 0x6000, dataPlaneEnabled = false) ++
  new chipyard.harness.WithDontTouchChipTopPorts(false) ++
  new chipyard.harness.WithSimAXIMem ++
  new freechips.rocketchip.subsystem.WithNBanks(1) ++
  new freechips.rocketchip.subsystem.WithExtMemSize(4L << 30) ++
  new WithOpenNicMemoryWidth ++
  new chipyard.config.WithPeripheryBusFrequency(50.0) ++
  new chipyard.config.WithMemoryBusFrequency(50.0) ++
  new chipyard.config.WithControlBusFrequency(50.0) ++
  new chipyard.config.WithSystemBusFrequency(50.0) ++
  new chipyard.config.WithFrontBusFrequency(50.0) ++
  new chipyard.config.WithOffchipBusFrequency(50.0) ++
  new testchipip.serdes.WithNoSerialTLClient ++
  new testchipip.serdes.WithNoSerialTL ++
  new testchipip.soc.WithNoScratchpads ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)

// Keep ports when lowering TestHarness, so the complete ChipTop is exportable.
class OpenEthExportConfig extends Config(
  new chipyard.harness.WithDontTouchChipTopPorts(true) ++ new OpenEthReferenceConfig)

class WithOpenEthSimDmi extends Config((site, here, up) => {
  case freechips.rocketchip.devices.debug.ExportDebug =>
    up(freechips.rocketchip.devices.debug.ExportDebug).copy(
      protocols = Set(freechips.rocketchip.devices.debug.DMI))
})
class OpenEthSimConfig extends Config(new WithOpenEthSimDmi ++ new OpenEthReferenceConfig)

object CheckOpenEthConfig extends App {
  import freechips.rocketchip.subsystem._
  import freechips.rocketchip.tile.{BuildRoCC, RocketTileParams}
  val c = new OpenEthExportConfig
  require(c(BuildRoCC).isEmpty)
  require(c(SystemBusKey).beatBytes == 16 && c(MemoryBusKey).beatBytes == 64)
  require(c(ExtMem).get.master.size == (BigInt(4) << 30))
  val tiles = c(TilesLocated(InSubsystem))
  require(tiles.size == 1)
  val tile = tiles.head.tileParams.asInstanceOf[RocketTileParams]
  require(tile.core.fpu.nonEmpty)
  require(tile.dcache.get.nWays == 8 && tile.icache.get.nWays == 8)
  require(c(chipyard.eth.EthKey).exists(p => !p.dataPlaneEnabled && p.address == 0x6000))
  require(c(OpenNicKey).contains(OpenNicParams()))
  println("OPENETH_SOC_CONFIG_PASS cpu=1 sbus=128 mbus=512 memory_gib=4 nic_queue=32 max_frame=2048")
}

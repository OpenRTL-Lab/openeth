// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: nic/platform/src/test/scala/opennic/platform/DmaTileLinkSpec.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
package opennic.platform

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorCFlags
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import opennic._

class DmaBridgeHarness(implicit p: Parameters) extends LazyModule {
  val master = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(
    TLMasterParameters.v1(name = "test-dma", sourceId = IdRange(0, 2))))))
  val memory = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(
    TLSlaveParameters.v1(address = Seq(AddressSet(0x80000000L, 65535)),
      regionType = RegionType.UNCACHED, mayDenyGet = true, mayDenyPut = true,
      supportsGet = TransferSizes(1, 8),
      supportsPutFull = TransferSizes(1, 8), supportsPutPartial = TransferSizes(1, 8))),
    beatBytes = 8, minLatency = 1)))
  memory := master
  lazy val module = new HarnessImpl
  class HarnessImpl extends LazyModuleImp(this) {
    val bridge = Module(new DmaTileLinkBridge(master.out(0)._2))
    val io = IO(new Bundle {
      val native = Flipped(new DmaPorts)
      val bus = new TLBundle(memory.in(0)._2.bundle)
    })
    bridge.io.native <> io.native
    master.out(0)._1 <> bridge.io.tl
    io.bus <> memory.in(0)._1
  }
}

class DmaTileLinkSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "OpenNIC TileLink内存桥"
  it should "保持背压负载、分离读写source并就地拒绝非法地址" in {
    implicit val p: Parameters = Parameters.empty
    val harness = LazyModule(new DmaBridgeHarness)
    test(harness.module).withAnnotations(Seq(VerilatorBackendAnnotation,
      VerilatorCFlags(Seq("-DWData=EData")))) { d =>
      val n = d.io.native; val b = d.io.bus
      n.read.valid.poke(false.B); n.write.valid.poke(false.B)
      n.read.bits.poke(0.U)
      n.write.bits.address.poke(0.U); n.write.bits.data.poke(0.U); n.write.bits.mask.poke(255.U)
      n.readResult.ready.poke(false.B); n.writeResult.ready.poke(false.B)
      b.a.ready.poke(false.B); b.d.valid.poke(false.B)
      b.d.bits.opcode.poke(0.U); b.d.bits.param.poke(0.U); b.d.bits.size.poke(3.U)
      b.d.bits.source.poke(0.U); b.d.bits.sink.poke(0.U)
      b.d.bits.denied.poke(false.B); b.d.bits.corrupt.poke(false.B); b.d.bits.data.poke(0.U)
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
      def read(address: BigInt): Unit = {
        n.read.bits.poke(address.U); n.read.valid.poke(true.B)
        n.read.ready.expect(true.B); d.clock.step(); n.read.valid.poke(false.B)
      }
      def write(address: BigInt, data: BigInt, mask: Int): Unit = {
        n.write.bits.address.poke(address.U); n.write.bits.data.poke(data.U)
        n.write.bits.mask.poke(mask.U); n.write.valid.poke(true.B)
        n.write.ready.expect(true.B); d.clock.step(); n.write.valid.poke(false.B)
      }
      // 高地址不能被截断为0x80000000；未映射、非对齐、零mask不访问TL。
      Seq(BigInt("180000000", 16), BigInt("90000000", 16), BigInt("80000001", 16)).foreach { a =>
        read(a); b.a.valid.expect(false.B); n.readResult.valid.expect(true.B)
        n.readResult.bits.error.expect(true.B)
        d.clock.step(4); n.readResult.valid.expect(true.B)
        n.readResult.ready.poke(true.B); d.clock.step(); n.readResult.ready.poke(false.B)
      }
      write(BigInt("80000000", 16), 5, 0)
      b.a.valid.expect(false.B); n.writeResult.valid.expect(true.B); n.writeResult.bits.expect(true.B)
      n.writeResult.ready.poke(true.B); d.clock.step(); n.writeResult.ready.poke(false.B)
      read(BigInt("80000000", 16))
      b.a.valid.expect(true.B); b.a.bits.source.expect(0.U); b.a.bits.address.expect("h80000000".U)
      b.a.bits.opcode.expect(TLMessages.Get)
      write(BigInt("80000008", 16), BigInt("fedcba9876543210", 16), 7)
      d.clock.step(5)
      b.a.bits.source.expect(0.U); b.a.bits.address.expect("h80000000".U)
      b.a.ready.poke(true.B); d.clock.step()
      b.a.bits.source.expect(1.U); b.a.bits.opcode.expect(TLMessages.PutPartialData)
      b.a.bits.data.expect("hfedcba9876543210".U); b.a.bits.mask.expect(7.U)
      d.clock.step(); b.a.ready.poke(false.B); b.a.valid.expect(false.B)
      // 允许写应答先于读返回，且完成接收方背压期间保持。
      b.d.valid.poke(true.B); b.d.bits.source.poke(1.U); b.d.bits.opcode.poke(TLMessages.AccessAck)
      n.writeResult.valid.expect(true.B); n.writeResult.bits.expect(false.B)
      b.d.ready.expect(false.B); d.clock.step(4)
      n.writeResult.ready.poke(true.B); d.clock.step(); n.writeResult.ready.poke(false.B)
      b.d.bits.source.poke(0.U); b.d.bits.opcode.poke(TLMessages.AccessAckData)
      b.d.bits.data.poke("h0123456789abcdef".U)
      n.readResult.bits.data.expect("h0123456789abcdef".U); n.readResult.bits.error.expect(false.B)
      d.clock.step(4); n.readResult.ready.poke(true.B); d.clock.step()
      b.d.valid.poke(false.B); n.readResult.ready.poke(false.B)
      read(BigInt("80000010", 16)); b.a.ready.poke(true.B); d.clock.step(); b.a.ready.poke(false.B)
      b.d.valid.poke(true.B); b.d.bits.denied.poke(true.B); b.d.bits.corrupt.poke(true.B)
      n.readResult.bits.error.expect(true.B)
      n.readResult.ready.poke(true.B); d.clock.step(); b.d.valid.poke(false.B)
    }
  }
}

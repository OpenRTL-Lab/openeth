// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: nic/platform/src/test/scala/opennic/platform/OpenNicPlatformSpec.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
package opennic.platform

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorCFlags
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci._
import freechips.rocketchip.interrupts._
import opennic._

class PlatformHarness(implicit p: Parameters) extends LazyModule {
  val nic = LazyModule(new OpenNicTL(OpenNicParams(queueDepth = 4, maxFrameBytes = 128)))
  val host = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(
    TLMasterParameters.v1(name = "csr-host", sourceId = IdRange(0, 1))))))
  nic.csrNode := host
  val memory = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(
    TLSlaveParameters.v1(address = Seq(AddressSet(0x80000000L, 65535)),
      regionType = RegionType.UNCACHED, supportsGet = TransferSizes(1, 8),
      supportsPutFull = TransferSizes(1, 8), supportsPutPartial = TransferSizes(1, 8))), beatBytes = 8)))
  memory := nic.dmaNode
  val irqs = IntSinkNode(IntSinkPortSimple())
  irqs := nic.intNode
  val clocks = ClockSourceNode(Seq(ClockSourceParameters()))
  nic.clockNode := clocks
  lazy val module = new HarnessImpl
  class HarnessImpl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val csr = Flipped(new TLBundle(host.out(0)._2.bundle))
      val memory = new TLBundle(PlatformHarness.this.memory.in(0)._2.bundle)
      val tx = Decoupled(new FrameBeat)
      val rx = Flipped(Decoupled(new FrameBeat))
      val irq = Output(UInt(2.W))
    })
    host.out(0)._1 <> io.csr
    io.memory <> PlatformHarness.this.memory.in(0)._1
    io.tx <> nic.module.tx
    nic.module.rx <> io.rx
    io.irq := irqs.in(0)._1.asUInt
    clocks.out(0)._1.clock := clock
    clocks.out(0)._1.reset := reset
  }
}

class OpenNicPlatformSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "OpenNIC完整平台桥"
  it should "通过TL寄存器执行真实TX搬运并保证完成读取只弹出一次" in {
    implicit val p: Parameters = Parameters.empty
    val h = LazyModule(new PlatformHarness)
    test(h.module).withAnnotations(Seq(VerilatorBackendAnnotation,
      VerilatorCFlags(Seq("-DWData=EData")))) { d =>
      val c = d.io.csr; val m = d.io.memory
      c.a.valid.poke(false.B); c.d.ready.poke(false.B)
      c.a.bits.param.poke(0.U); c.a.bits.size.poke(2.U); c.a.bits.source.poke(0.U)
      c.a.bits.mask.poke(15.U); c.a.bits.corrupt.poke(false.B)
      c.a.bits.opcode.poke(TLMessages.Get); c.a.bits.address.poke("h10016000".U); c.a.bits.data.poke(0.U)
      m.a.ready.poke(false.B); m.d.valid.poke(false.B)
      m.d.bits.opcode.poke(TLMessages.AccessAckData); m.d.bits.param.poke(0.U)
      m.d.bits.size.poke(3.U); m.d.bits.source.poke(0.U); m.d.bits.sink.poke(0.U)
      m.d.bits.denied.poke(false.B); m.d.bits.corrupt.poke(false.B); m.d.bits.data.poke(0.U)
      d.io.tx.ready.poke(false.B); d.io.rx.valid.poke(false.B)
      d.io.rx.bits.data.poke(0.U); d.io.rx.bits.keep.poke(255.U)
      d.io.rx.bits.last.poke(false.B); d.io.rx.bits.error.poke(false.B)
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
      def bounded(condition: => Boolean): Unit = {
        var cycles = 0
        while (!condition && cycles < 200) { d.clock.step(); cycles += 1 }
        assert(condition, "总线测试等待超时")
      }
      def csr(offset: Int, write: Boolean = false, data: BigInt = 0,
              stall: Int = 0, denied: Boolean = false, size: Int = 2): BigInt = {
        c.a.bits.size.poke(size.U)
        c.a.bits.mask.poke((((1 << (1 << size)) - 1) << (offset & 3)).U)
        c.a.bits.opcode.poke(if (write) TLMessages.PutFullData else TLMessages.Get)
        c.a.bits.address.poke((BigInt("10016000", 16) + offset).U); c.a.bits.data.poke(data.U)
        c.a.valid.poke(true.B); bounded(c.a.ready.peek().litToBoolean)
        d.clock.step(); c.a.valid.poke(false.B)
        bounded(c.d.valid.peek().litToBoolean)
        c.d.bits.denied.expect(denied.B)
        c.d.bits.opcode.expect(if (write) TLMessages.AccessAck else TLMessages.AccessAckData)
        val result = c.d.bits.data.peek().litValue
        (0 until stall).foreach { _ =>
          d.clock.step(); c.d.valid.expect(true.B); c.d.bits.data.expect(result.U)
        }
        c.d.ready.poke(true.B); d.clock.step(); c.d.ready.poke(false.B)
        result
      }
      csr(0) shouldBe BigInt("4f4e4943", 16)
      csr(4) shouldBe 65536
      csr(0, denied = true, size = 0, stall = 4) shouldBe 0
      csr(8, true, 1, denied = true, size = 1)
      csr(8) shouldBe 0 // 窄写CONTROL不得改变使能。
      csr(0x600, denied = true)
      csr(0x2c, true, 1, denied = true) // disabled时doorbell没有副作用
      csr(8, true, 1); csr(0x10, true, 3)
      csr(0x20, true, BigInt("80000000", 16)); csr(0x24, true, 0)
      csr(0x28, true, BigInt("1234000b", 16)); csr(0x2c, true, 1)
      Seq(BigInt("0807060504030201", 16), BigInt("00000000000b0a09", 16)).zipWithIndex.foreach {
        case (word, index) =>
          bounded(m.a.valid.peek().litToBoolean)
          m.a.bits.address.expect((BigInt("80000000", 16) + 8 * index).U)
          m.a.bits.source.expect(0.U); m.a.bits.opcode.expect(TLMessages.Get)
          d.clock.step(3); m.a.ready.poke(true.B); d.clock.step(); m.a.ready.poke(false.B)
          m.d.bits.data.poke(word.U); m.d.valid.poke(true.B)
          bounded(m.d.ready.peek().litToBoolean); d.clock.step(); m.d.valid.poke(false.B)
      }
      Seq((BigInt("0807060504030201", 16), 255, false),
        (BigInt("00000000000b0a09", 16), 7, true)).foreach { case (word, mask, last) =>
        bounded(d.io.tx.valid.peek().litToBoolean)
        d.io.tx.bits.data.expect(word.U); d.io.tx.bits.keep.expect(mask.U)
        d.io.tx.bits.last.expect(last.B); d.io.tx.bits.error.expect(false.B)
        d.clock.step(3); d.io.tx.ready.poke(true.B); d.clock.step(); d.io.tx.ready.poke(false.B)
      }
      bounded(d.io.irq.peek().litValue == 1)
      csr(0x44) shouldBe 1
      csr(0x40, denied = true, size = 0, stall = 7) shouldBe 0
      csr(0x42, denied = true, size = 1) shouldBe 0
      csr(0x44) shouldBe 1 // 窄读完成寄存器不得出队。
      csr(0x40, stall = 9) shouldBe BigInt("12340000", 16)
      csr(0x44) shouldBe 0; d.io.irq.expect(0.U)
      // 空读同样只回应，不引起隐藏的描述符/队列操作。
      csr(0x40, stall = 5) shouldBe 0
      csr(0x44) shouldBe 0; csr(0x5c) shouldBe 0
    }
  }
}

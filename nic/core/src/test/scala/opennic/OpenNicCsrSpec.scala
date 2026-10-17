// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: nic/core/src/test/scala/opennic/OpenNicCsrSpec.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
package opennic

import chisel3._
import chiseltest._
import chiseltest.simulator.{VerilatorBackendAnnotation, VerilatorCFlags}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OpenNicCsrSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "OpenNic CSR"
  it should "make posts atomic preserve queued completions and reset only when quiescent" in {
    test(new OpenNic(queueDepth = 2)).withAnnotations(
      Seq(VerilatorBackendAnnotation, VerilatorCFlags(Seq("-DWData=EData")))) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.address.poke(0.U); dut.io.request.bits.write.poke(false.B)
      dut.io.request.bits.data.poke(0.U); dut.io.request.bits.strobe.poke(15.U)
      dut.io.response.ready.poke(false.B)
      dut.io.tx.ready.poke(false.B); dut.io.rx.valid.poke(false.B)
      dut.io.rx.bits.data.poke(0.U); dut.io.rx.bits.keep.poke(0.U)
      dut.io.rx.bits.last.poke(false.B); dut.io.rx.bits.error.poke(false.B)
      dut.io.memory.read.ready.poke(false.B); dut.io.memory.readResult.valid.poke(false.B)
      dut.io.memory.readResult.bits.data.poke(0.U); dut.io.memory.readResult.bits.error.poke(false.B)
      dut.io.memory.write.ready.poke(false.B); dut.io.memory.writeResult.valid.poke(false.B)
      dut.io.memory.writeResult.bits.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B)
      def access(address: Int, write: Boolean = false, data: BigInt = 0, strobe: Int = 15,
                 error: Boolean = false, responseStall: Int = 0): BigInt = {
        dut.io.request.ready.expect(true.B)
        dut.io.request.bits.address.poke(address.U); dut.io.request.bits.write.poke(write.B)
        dut.io.request.bits.data.poke(data.U); dut.io.request.bits.strobe.poke(strobe.U)
        dut.io.request.valid.poke(true.B); dut.clock.step(); dut.io.request.valid.poke(false.B)
        dut.io.response.valid.expect(true.B); dut.io.response.bits.error.expect(error.B)
        val result = dut.io.response.bits.data.peek().litValue
        (0 until responseStall).foreach { _ =>
          dut.io.request.ready.expect(false.B)
          dut.clock.step()
          dut.io.response.valid.expect(true.B); dut.io.response.bits.data.expect(result.U)
        }
        dut.io.response.ready.poke(true.B); dut.clock.step(); dut.io.response.ready.poke(false.B)
        result
      }
      access(0) shouldBe BigInt("4f4e4943", 16)
      access(4) shouldBe BigInt("10000", 16)
      access(1, error = true); access(0x600, error = true)
      access(0, write = true, error = true)
      access(0x08, write = true, data = 1, strobe = 1, error = true)
      access(0x2c, write = true, data = 1, error = true)
      access(0x08, write = true, data = 1)
      access(0x10, write = true, data = 3)
      // 非法TX描述符产生完成，不触碰内存；填满完成和提交队列。
      (1 to 5).foreach { cookie =>
        access(0x20, write = true, data = 1)
        access(0x28, write = true, data = (cookie << 16) | 64)
        access(0x2c, write = true, data = 1)
      }
      access(0x54) shouldBe BigInt(2 << 16)
      access(0x2c, write = true, data = 1, error = true)
      access(0x44) shouldBe BigInt(2)
      dut.io.irq.expect(1.U)
      access(0x08, write = true, data = 2, error = true)
      (1 to 5).foreach { cookie =>
        access(0x40, responseStall = 5) shouldBe BigInt((cookie << 16) | 1)
      }
      access(0x44) shouldBe BigInt(0)
      access(0x40) shouldBe BigInt(0)
      dut.io.irq.expect(0.U)
      access(0x10, write = true, data = 0)
      access(0x08, write = true, data = 0)
      access(0x08, write = true, data = 2)
      access(0x54) shouldBe BigInt((2 << 16) | 2)
      // 已提交地址与META不能被稍后暂存寄存器写覆盖。
      access(0x08, write = true, data = 1)
      access(0x20, write = true, data = 0x1000)
      access(0x24, write = true, data = 1)
      access(0x28, write = true, data = (44 << 16) | 8)
      access(0x2c, write = true, data = 1)
      access(0x20, write = true, data = 0x2000)
      dut.io.memory.read.valid.expect(true.B)
      dut.io.memory.read.bits.expect(BigInt("100001000", 16).U)
      access(0x08, write = true, data = 0)
      access(0x08, write = true, data = 2, error = true)
      dut.io.memory.read.ready.poke(true.B); dut.clock.step(); dut.io.memory.read.ready.poke(false.B)
      dut.io.memory.readResult.valid.poke(true.B)
      dut.io.memory.readResult.bits.data.poke(BigInt("0706050403020100", 16).U)
      dut.clock.step(); dut.io.memory.readResult.valid.poke(false.B)
      dut.io.tx.valid.expect(true.B); dut.io.tx.bits.last.expect(true.B)
      dut.io.tx.ready.poke(true.B); dut.clock.step(5)
      access(0x40) shouldBe BigInt(44 << 16)
      access(0x08, write = true, data = 2)
      // 未启用时进入无描述符排空，flush无需等待外部last且不得产生DMA。
      dut.io.rx.valid.poke(true.B); dut.io.rx.bits.keep.poke(255.U)
      dut.io.rx.bits.last.poke(false.B); dut.clock.step()
      dut.io.rx.valid.poke(false.B)
      (access(0x0c).toInt & 6) shouldBe 0
      access(0x08, write = true, data = 2)
      // 排空状态保留帧边界：重新使能、投递RX也不能接纳旧帧尾部。
      access(0x08, write = true, data = 1)
      access(0x30, write = true, data = 0x3000)
      access(0x38, write = true, data = (55 << 16) | 64)
      access(0x3c, write = true, data = 1)
      dut.io.rx.valid.poke(true.B); dut.io.rx.bits.keep.poke(1.U)
      dut.io.rx.bits.last.poke(true.B); dut.clock.step()
      dut.io.rx.valid.poke(false.B); dut.clock.step(3)
      dut.io.memory.write.valid.expect(false.B)
      access(0x4c) shouldBe 0
      (access(0x54).toInt >> 16) shouldBe 1
      access(0x08, write = true, data = 0)
      access(0x08, write = true, data = 2)
    }
  }
}

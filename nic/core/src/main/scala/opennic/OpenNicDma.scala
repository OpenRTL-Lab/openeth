// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: nic/core/src/main/scala/opennic/OpenNicDma.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
package opennic

import chisel3._
import chisel3.util._

/** 独立双向包缓存DMA。每方向一个内存事务；完成必须等待真实应答。 */
class OpenNicDma(val maxFrameBytes: Int = 2048) extends Module {
  require(maxFrameBytes >= 8 && maxFrameBytes <= 4095 && maxFrameBytes % 8 == 0)
  val io = IO(new Bundle {
    val txDescriptor = Flipped(Decoupled(new Descriptor))
    val rxDescriptor = Flipped(Decoupled(new Descriptor))
    val txCompletion = Decoupled(new Completion)
    val rxCompletion = Decoupled(new Completion)
    val tx = Decoupled(new FrameBeat)
    val rx = Flipped(Decoupled(new FrameBeat))
    val memory = new DmaPorts
    val txBusy = Output(Bool())
    val rxBusy = Output(Bool())
    val dropped = Output(UInt(32.W))
    val errors = Output(UInt(32.W))
  })
  private val words = maxFrameBytes / 8
  private val indexWidth = log2Ceil(words) max 1
  private val txBuffer = Mem(words, UInt(64.W))
  private val rxBuffer = Mem(words, UInt(64.W))

  val tIdle :: tRead :: tWait :: tSend :: tDone :: Nil = Enum(5)
  val ts = RegInit(tIdle)
  val td = Reg(new Descriptor)
  val ti = RegInit(0.U(indexWidth.W))
  val tstatus = RegInit(0.U(4.W))
  val txErrors = RegInit(0.U(32.W))
  val tlast = (ti +& 1.U) * 8.U >= td.length

  io.txBusy := ts =/= tIdle
  io.txDescriptor.ready := ts === tIdle
  io.memory.read.valid := ts === tRead
  io.memory.read.bits := td.address + (ti << 3)
  io.memory.readResult.ready := ts === tWait
  io.tx.valid := ts === tSend
  io.tx.bits.data := txBuffer(ti)
  io.tx.bits.keep := Mux(tlast, FrameRules.tailMask(td.length), 255.U)
  io.tx.bits.last := tlast
  io.tx.bits.error := false.B
  io.txCompletion.valid := ts === tDone
  io.txCompletion.bits.cookie := td.cookie
  io.txCompletion.bits.length := Mux(tstatus === 0.U, td.length, 0.U)
  io.txCompletion.bits.status := tstatus

  switch(ts) {
    is(tIdle) {
      when(io.txDescriptor.fire) {
        td := io.txDescriptor.bits
        ti := 0.U
        tstatus := 0.U
        when(FrameRules.validDescriptor(io.txDescriptor.bits, maxFrameBytes)) {
          ts := tRead
        }.otherwise {
          tstatus := 1.U
          txErrors := txErrors + 1.U
          ts := tDone
        }
      }
    }
    is(tRead) { when(io.memory.read.fire) { ts := tWait } }
    is(tWait) {
      when(io.memory.readResult.fire) {
        when(io.memory.readResult.bits.error) {
          tstatus := 2.U
          txErrors := txErrors + 1.U
          ts := tDone
        }.otherwise {
          txBuffer(ti) := io.memory.readResult.bits.data
          when(tlast) { ti := 0.U; ts := tSend }
            .otherwise { ti := ti + 1.U; ts := tRead }
        }
      }
    }
    is(tSend) {
      when(io.tx.fire) {
        when(tlast) { ts := tDone }.otherwise { ti := ti + 1.U }
      }
    }
    is(tDone) { when(io.txCompletion.fire) { ts := tIdle } }
  }

  val rIdle :: rStore :: rDrain :: rWrite :: rWait :: rDone :: Nil = Enum(6)
  val rs = RegInit(rIdle)
  val rd = Reg(new Descriptor)
  val ri = RegInit(0.U(indexWidth.W))
  val rlength = RegInit(0.U(16.W))
  val rstatus = RegInit(0.U(4.W))
  val owned = RegInit(false.B)
  val drops = RegInit(0.U(32.W))
  val rxErrors = RegInit(0.U(32.W))
  val incomingBytes = PopCount(io.rx.bits.keep)
  val accumulated = rlength +& incomingBytes
  val rlast = (ri +& 1.U) * 8.U >= rlength

  // 无描述符帧只排空包流，不拥有任何DMA缓冲，不能阻止软件安全停机。
  io.rxBusy := rs =/= rIdle && !(rs === rDrain && !owned)
  io.dropped := drops
  io.errors := txErrors + rxErrors
  io.rx.ready := rs === rIdle || rs === rStore || rs === rDrain
  io.rxDescriptor.ready := rs === rIdle && io.rx.valid
  io.memory.write.valid := rs === rWrite
  io.memory.write.bits.address := rd.address + (ri << 3)
  io.memory.write.bits.data := rxBuffer(ri)
  io.memory.write.bits.mask := Mux(rlast, FrameRules.tailMask(rlength), 255.U)
  io.memory.writeResult.ready := rs === rWait
  io.rxCompletion.valid := rs === rDone
  io.rxCompletion.bits.cookie := rd.cookie
  io.rxCompletion.bits.length := Mux(rstatus === 0.U, rlength, 0.U)
  io.rxCompletion.bits.status := rstatus

  switch(rs) {
    is(rIdle) {
      when(io.rx.fire) {
        ri := 0.U
        rlength := incomingBytes
        rstatus := 0.U
        owned := io.rxDescriptor.valid
        when(!io.rxDescriptor.valid) {
          when(io.rx.bits.last) { drops := drops + 1.U }
            .otherwise { rs := rDrain }
        }.otherwise {
          rd := io.rxDescriptor.bits
          val descriptorOk = FrameRules.validDescriptor(io.rxDescriptor.bits, maxFrameBytes)
          val beatOk = FrameRules.validKeep(io.rx.bits.keep, io.rx.bits.last) &&
            !io.rx.bits.error && incomingBytes <= io.rxDescriptor.bits.length
          when(!descriptorOk || !beatOk) {
            rstatus := Mux(descriptorOk, 3.U, 1.U)
            rxErrors := rxErrors + 1.U
            rs := Mux(io.rx.bits.last, rDone, rDrain)
          }.otherwise {
            rxBuffer(0.U) := io.rx.bits.data
            when(io.rx.bits.last) { rs := rWrite }
              .otherwise { ri := 1.U; rs := rStore }
          }
        }
      }
    }
    is(rStore) {
      when(io.rx.fire) {
        val beatOk = FrameRules.validKeep(io.rx.bits.keep, io.rx.bits.last) &&
          !io.rx.bits.error && accumulated <= rd.length && accumulated <= maxFrameBytes.U
        when(!beatOk) {
          rstatus := 3.U
          rxErrors := rxErrors + 1.U
          rs := Mux(io.rx.bits.last, rDone, rDrain)
        }.otherwise {
          rxBuffer(ri) := io.rx.bits.data
          rlength := accumulated
          when(io.rx.bits.last) { ri := 0.U; rs := rWrite }
            .otherwise { ri := ri + 1.U }
        }
      }
    }
    is(rDrain) {
      when(io.rx.fire && io.rx.bits.last) {
        when(owned) { rs := rDone }.otherwise { drops := drops + 1.U; rs := rIdle }
      }
    }
    is(rWrite) { when(io.memory.write.fire) { rs := rWait } }
    is(rWait) {
      when(io.memory.writeResult.fire) {
        when(io.memory.writeResult.bits) {
          rstatus := 2.U
          rxErrors := rxErrors + 1.U
          rs := rDone
        }.elsewhen(rlast) { rs := rDone }
          .otherwise { ri := ri + 1.U; rs := rWrite }
      }
    }
    is(rDone) { when(io.rxCompletion.fire) { rs := rIdle } }
  }
}

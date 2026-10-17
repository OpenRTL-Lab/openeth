// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: nic/core/src/main/scala/opennic/OpenNic.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
package opennic

import chisel3._
import chisel3.util._

class CsrRequest extends Bundle {
  val address = UInt(12.W)
  val write = Bool()
  val data = UInt(32.W)
  val strobe = UInt(4.W)
}
class CsrResult extends Bundle {
  val data = UInt(32.W)
  val error = Bool()
}

/** 原生32位CSR + 独立DMA/包流接口；无平台总线或CPU类型依赖。 */
class OpenNic(val queueDepth: Int = 32, val maxFrameBytes: Int = 2048) extends Module {
  require(queueDepth >= 2 && queueDepth <= 256)
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new CsrRequest))
    val response = Decoupled(new CsrResult)
    val memory = new DmaPorts
    val tx = Decoupled(new FrameBeat)
    val rx = Flipped(Decoupled(new FrameBeat))
    val irq = Output(UInt(2.W))
  })
  val engine = Module(new OpenNicDma(maxFrameBytes))
  val flush = WireDefault(false.B)
  val txDescriptors = withReset(reset.asBool || flush) { Module(new Queue(new Descriptor, queueDepth)) }
  val rxDescriptors = withReset(reset.asBool || flush) { Module(new Queue(new Descriptor, queueDepth)) }
  val txDone = withReset(reset.asBool || flush) { Module(new Queue(new Completion, queueDepth)) }
  val rxDone = withReset(reset.asBool || flush) { Module(new Queue(new Completion, queueDepth)) }
  val enabled = RegInit(false.B)
  val irqMask = RegInit(0.U(2.W))
  val txAddress = RegInit(0.U(64.W))
  val rxAddress = RegInit(0.U(64.W))
  val txMeta = RegInit(0.U(32.W))
  val rxMeta = RegInit(0.U(32.W))
  val responseValid = RegInit(false.B)
  val response = Reg(new CsrResult)
  val irqStatus = Cat(rxDone.io.deq.valid, txDone.io.deq.valid)
  io.irq := irqStatus & irqMask
  io.request.ready := !responseValid
  io.response.valid := responseValid
  io.response.bits := response
  when(io.response.fire) { responseValid := false.B }

  engine.io.txDescriptor.valid := enabled && txDescriptors.io.deq.valid
  engine.io.txDescriptor.bits := txDescriptors.io.deq.bits
  txDescriptors.io.deq.ready := enabled && engine.io.txDescriptor.ready
  engine.io.rxDescriptor.valid := enabled && rxDescriptors.io.deq.valid
  engine.io.rxDescriptor.bits := rxDescriptors.io.deq.bits
  rxDescriptors.io.deq.ready := enabled && engine.io.rxDescriptor.ready
  txDone.io.enq <> engine.io.txCompletion
  rxDone.io.enq <> engine.io.rxCompletion
  io.memory <> engine.io.memory
  io.tx <> engine.io.tx
  engine.io.rx <> io.rx
  txDescriptors.io.enq.valid := false.B
  txDescriptors.io.enq.bits.address := txAddress
  txDescriptors.io.enq.bits.cookie := txMeta(31, 16)
  txDescriptors.io.enq.bits.length := txMeta(15, 0)
  rxDescriptors.io.enq.valid := false.B
  rxDescriptors.io.enq.bits.address := rxAddress
  rxDescriptors.io.enq.bits.cookie := rxMeta(31, 16)
  rxDescriptors.io.enq.bits.length := rxMeta(15, 0)
  txDone.io.deq.ready := false.B
  rxDone.io.deq.ready := false.B

  val request = io.request.bits
  when(io.request.fire) {
    responseValid := true.B
    response.data := 0.U
    response.error := true.B
    when(request.address(1, 0) === 0.U) {
      when(request.write && request.strobe === 15.U) {
        switch(request.address) {
          is(0x08.U) {
            val mayFlush = !enabled && !engine.io.txBusy && !engine.io.rxBusy
            when(!request.data(1) || (mayFlush && !request.data(0))) {
              enabled := request.data(0)
              flush := request.data(1)
              response.error := false.B
            }
          }
          is(0x10.U) { irqMask := request.data(1, 0); response.error := false.B }
          is(0x20.U) { txAddress := Cat(txAddress(63, 32), request.data); response.error := false.B }
          is(0x24.U) { txAddress := Cat(request.data, txAddress(31, 0)); response.error := false.B }
          is(0x28.U) { txMeta := request.data; response.error := false.B }
          is(0x2c.U) {
            when(enabled && txDescriptors.io.enq.ready && request.data === 1.U) {
              txDescriptors.io.enq.valid := true.B; response.error := false.B
            }
          }
          is(0x30.U) { rxAddress := Cat(rxAddress(63, 32), request.data); response.error := false.B }
          is(0x34.U) { rxAddress := Cat(request.data, rxAddress(31, 0)); response.error := false.B }
          is(0x38.U) { rxMeta := request.data; response.error := false.B }
          is(0x3c.U) {
            when(enabled && rxDescriptors.io.enq.ready && request.data === 1.U) {
              rxDescriptors.io.enq.valid := true.B; response.error := false.B
            }
          }
        }
      }.elsewhen(!request.write) {
        switch(request.address) {
          is(0x00.U) { response.data := "h4f4e4943".U; response.error := false.B }
          is(0x04.U) { response.data := "h00010000".U; response.error := false.B }
          is(0x08.U) { response.data := enabled; response.error := false.B }
          is(0x0c.U) { response.data := Cat(0.U(29.W), engine.io.rxBusy, engine.io.txBusy, enabled); response.error := false.B }
          is(0x10.U) { response.data := irqMask; response.error := false.B }
          is(0x14.U) { response.data := irqStatus; response.error := false.B }
          is(0x20.U) { response.data := txAddress(31, 0); response.error := false.B }
          is(0x24.U) { response.data := txAddress(63, 32); response.error := false.B }
          is(0x28.U) { response.data := txMeta; response.error := false.B }
          is(0x30.U) { response.data := rxAddress(31, 0); response.error := false.B }
          is(0x34.U) { response.data := rxAddress(63, 32); response.error := false.B }
          is(0x38.U) { response.data := rxMeta; response.error := false.B }
          is(0x40.U) {
            response.data := Mux(txDone.io.deq.valid,
              Cat(txDone.io.deq.bits.cookie, 0.U(12.W), txDone.io.deq.bits.status), 0.U)
            txDone.io.deq.ready := true.B; response.error := false.B
          }
          is(0x44.U) { response.data := txDone.io.count; response.error := false.B }
          is(0x48.U) {
            response.data := Mux(rxDone.io.deq.valid,
              Cat(rxDone.io.deq.bits.cookie, rxDone.io.deq.bits.length(11, 0), 0.U(4.W)), 0.U)
            rxDone.io.deq.ready := true.B; response.error := false.B
          }
          is(0x4c.U) { response.data := rxDone.io.count; response.error := false.B }
          is(0x50.U) { response.data := Mux(rxDone.io.deq.valid, rxDone.io.deq.bits.status, 0.U); response.error := false.B }
          is(0x54.U) {
            response.data := Cat((queueDepth.U - rxDescriptors.io.count).pad(16),
              (queueDepth.U - txDescriptors.io.count).pad(16))
            response.error := false.B
          }
          is(0x58.U) { response.data := engine.io.dropped; response.error := false.B }
          is(0x5c.U) { response.data := engine.io.errors; response.error := false.B }
        }
      }
    }
  }
}

// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: nic/platform/src/main/scala/opennic/platform/OpenNicTL.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
package opennic.platform

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci._
import freechips.rocketchip.interrupts._
import opennic._

/** 平台内存桥：仅64位Get/PutPartial，两个source分别对应读/写。 */
class DmaTileLinkBridge(edge: TLEdgeOut) extends Module {
  require(edge.bundle.dataBits == 64)
  require(edge.bundle.addressBits <= 64)
  val io = IO(new Bundle {
    val native = Flipped(new DmaPorts)
    val tl = new TLBundle(edge.bundle)
  })
  val readBusy = RegInit(false.B)
  val writeBusy = RegInit(false.B)
  val readError = RegInit(false.B)
  val writeError = RegInit(false.B)
  val addressBits = edge.bundle.addressBits
  val ra = io.native.read.bits(addressBits - 1, 0)
  val wa = io.native.write.bits.address(addressBits - 1, 0)
  def fits(address: UInt): Bool =
    if (addressBits == 64) true.B else (address >> addressBits) === 0.U
  val readLegal = fits(io.native.read.bits) && ra(2, 0) === 0.U &&
    edge.manager.supportsGetSafe(ra, 3.U)
  val writeLegal = fits(io.native.write.bits.address) && wa(2, 0) === 0.U &&
    io.native.write.bits.mask.orR && edge.manager.supportsPutPartialSafe(wa, 3.U)
  val arb = Module(new RRArbiter(new TLBundleA(edge.bundle), 2))
  // 寄存后的A队列确保新到达另一方向请求不会改变被背压的A负载。
  val requests = Module(new Queue(new TLBundleA(edge.bundle), 2))
  requests.io.enq <> arb.io.out
  io.tl.a <> requests.io.deq
  arb.io.in(0).valid := io.native.read.valid && !readBusy && readLegal
  arb.io.in(0).bits := edge.Get(0.U, ra, 3.U)._2
  arb.io.in(1).valid := io.native.write.valid && !writeBusy && writeLegal
  arb.io.in(1).bits := edge.Put(1.U, wa, 3.U, io.native.write.bits.data, io.native.write.bits.mask)._2
  io.native.read.ready := !readBusy && Mux(readLegal, arb.io.in(0).ready, true.B)
  io.native.write.ready := !writeBusy && Mux(writeLegal, arb.io.in(1).ready, true.B)
  when(io.native.read.fire) { readBusy := true.B; readError := !readLegal }
  when(io.native.write.fire) { writeBusy := true.B; writeError := !writeLegal }
  val readResponse = io.tl.d.valid && io.tl.d.bits.source === 0.U
  val writeResponse = io.tl.d.valid && io.tl.d.bits.source === 1.U
  io.native.readResult.valid := readError || readResponse
  io.native.readResult.bits.data := Mux(readError, 0.U, io.tl.d.bits.data)
  io.native.readResult.bits.error := readError || io.tl.d.bits.denied || io.tl.d.bits.corrupt
  io.native.writeResult.valid := writeError || writeResponse
  io.native.writeResult.bits := writeError || io.tl.d.bits.denied || io.tl.d.bits.corrupt
  io.tl.d.ready := Mux(io.tl.d.bits.source === 0.U,
    io.native.readResult.ready && !readError, io.native.writeResult.ready && !writeError)
  when(io.native.readResult.fire) { readBusy := false.B; readError := false.B }
  when(io.native.writeResult.fire) { writeBusy := false.B; writeError := false.B }
  when(io.tl.d.valid) {
    assert(io.tl.d.bits.source < 2.U)
    assert(Mux(io.tl.d.bits.source === 0.U, readBusy && !readError, writeBusy && !writeError))
    assert(io.tl.d.bits.opcode === Mux(io.tl.d.bits.source === 0.U,
      TLMessages.AccessAckData, TLMessages.AccessAck))
  }
  io.tl.b.ready := true.B
  io.tl.c.valid := false.B
  io.tl.c.bits := 0.U.asTypeOf(io.tl.c.bits)
  io.tl.e.valid := false.B
  io.tl.e.bits := 0.U.asTypeOf(io.tl.e.bits)
}

case class OpenNicParams(address: BigInt = 0x10016000L, queueDepth: Int = 32,
  maxFrameBytes: Int = 2048)

class OpenNicTL(params: OpenNicParams)(implicit p: Parameters)
    extends ClockSinkDomain(ClockSinkParameters())(p) {
  val device = new SimpleDevice("opennic", Seq("openhw,opennic-v1"))
  val csrNode = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(
    TLSlaveParameters.v1(address = Seq(AddressSet(params.address, 4095)),
      resources = device.reg("control"), regionType = RegionType.GET_EFFECTS,
      executable = false, mayDenyGet = true, mayDenyPut = true,
      // 总线可接纳1/2/4字节事务；子字节访问只返回denied，不触发原生CSR。
      supportsGet = TransferSizes(1, 4),
      supportsPutFull = TransferSizes(1, 4), fifoId = Some(0))), beatBytes = 4, minLatency = 1)))
  val dmaNode = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(
    TLMasterParameters.v1(name = "opennic-dma", sourceId = IdRange(0, 2))))))
  val intNode = IntSourceNode(IntSourcePortSimple(num = 2, resources = device.int))
  override lazy val module = new OpenNicImpl
  class OpenNicImpl extends Impl {
    val tx = IO(Decoupled(new FrameBeat))
    val rx = IO(Flipped(Decoupled(new FrameBeat)))
    withClockAndReset(clock, reset) {
    val core = Module(new OpenNic(params.queueDepth, params.maxFrameBytes))
    tx <> core.io.tx
    core.io.rx <> rx
    val (csr, csrEdge) = csrNode.in(0)
    val metadata = Reg(new TLBundleA(csrEdge.bundle))
    val active = RegInit(false.B)
    val rejected = RegInit(false.B)
    val fullWord = csr.a.bits.size === 2.U && csr.a.bits.address(1, 0) === 0.U
    core.io.request.valid := csr.a.valid && !active && fullWord
    core.io.request.bits.address := csr.a.bits.address(11, 0)
    core.io.request.bits.write := csr.a.bits.opcode === TLMessages.PutFullData
    core.io.request.bits.data := csr.a.bits.data
    core.io.request.bits.strobe := csr.a.bits.mask
    csr.a.ready := !active && (!fullWord || core.io.request.ready)
    when(csr.a.fire) {
      metadata := csr.a.bits
      active := true.B
      rejected := !fullWord
      assert(csr.a.bits.opcode === TLMessages.Get || csr.a.bits.opcode === TLMessages.PutFullData)
    }
    val error = rejected || core.io.response.bits.error
    csr.d.valid := active && (rejected || core.io.response.valid)
    csr.d.bits := Mux(metadata.opcode === TLMessages.Get,
      csrEdge.AccessAck(metadata, Mux(rejected, 0.U, core.io.response.bits.data), error, error),
      csrEdge.AccessAck(metadata, error))
    core.io.response.ready := active && !rejected && csr.d.ready
    when(csr.d.fire) { active := false.B }
    csr.b.valid := false.B
    csr.b.bits := 0.U.asTypeOf(csr.b.bits)
    csr.c.ready := true.B
    csr.e.ready := true.B
    val (dma, dmaEdge) = dmaNode.out(0)
    val bridge = Module(new DmaTileLinkBridge(dmaEdge))
    bridge.io.native <> core.io.memory
    dma <> bridge.io.tl
    intNode.out(0)._1(0) := core.io.irq(0)
    intNode.out(0)._1(1) := core.io.irq(1)
    }
  }
}

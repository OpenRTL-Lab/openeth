// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: nic/core/src/main/scala/opennic/Protocol.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
package opennic

import chisel3._
import chisel3.util._

class FrameBeat extends Bundle {
  val data = UInt(64.W)
  val keep = UInt(8.W)
  val last = Bool()
  val error = Bool()
}

class Descriptor extends Bundle {
  val address = UInt(64.W)
  val length = UInt(16.W)
  val cookie = UInt(16.W)
}

class Completion extends Bundle {
  val cookie = UInt(16.W)
  val length = UInt(16.W)
  val status = UInt(4.W)
}

class ReadResult extends Bundle {
  val data = UInt(64.W)
  val error = Bool()
}

class WriteRequest extends Bundle {
  val address = UInt(64.W)
  val data = UInt(64.W)
  val mask = UInt(8.W)
}

class DmaPorts extends Bundle {
  val read = Decoupled(UInt(64.W))
  val readResult = Flipped(Decoupled(new ReadResult))
  val write = Decoupled(new WriteRequest)
  val writeResult = Flipped(Decoupled(Bool()))
}

object FrameRules {
  def validKeep(keep: UInt, last: Bool): Bool =
    Mux(last, keep =/= 0.U && ((keep & (keep + 1.U)) === 0.U), keep === 255.U)
  def tailMask(length: UInt): UInt = {
    val bytes = length(2, 0)
    Mux(bytes === 0.U, 255.U(8.W), ((1.U(9.W) << bytes) - 1.U)(7, 0))
  }
  def validDescriptor(d: Descriptor, maximum: Int): Bool =
    d.address(2, 0) === 0.U && d.length =/= 0.U && d.length <= maximum.U &&
      !(d.address +& (maximum - 1).U)(64)
}

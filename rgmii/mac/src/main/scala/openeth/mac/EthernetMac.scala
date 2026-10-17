// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: rgmii/mac/src/main/scala/openeth/mac/EthernetMac.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
package openeth.mac

import chisel3._
import chisel3.util._

/** 八次反射位递推；不借用第三方实现。 */
object EthernetCrc {
  def step(crc: UInt, byte: UInt): UInt = {
    (0 until 8).foldLeft(crc) { (c, i) =>
      (c >> 1) ^ Mux(c(0) ^ byte(i), "hedb88320".U(32.W), 0.U(32.W))
    }
  }
}

class ByteBeat extends Bundle {
  val data = UInt(8.W)
  val last = Bool()
  val error = Bool()
}

/** 字节时钟域发送核：无整帧缓存，输入欠载用线路错误结束。 */
class MacTx(val maxBytes: Int = 2048) extends Module {
  require(maxBytes >= 60 && maxBytes <= 65535)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ByteBeat))
    val data = Output(UInt(8.W))
    val enable = Output(Bool())
    val error = Output(Bool())
    val done = Output(Bool())
    val aborted = Output(Bool())
    val underrun = Output(Bool())
    val active = Output(Bool())
  })
  val idle :: preamble :: payload :: padding :: fcs :: abort :: gap :: Nil = Enum(7)
  val state = RegInit(idle)
  val held = Reg(new ByteBeat)
  val crc = RegInit("hffffffff".U(32.W))
  val bytes = RegInit(0.U(16.W))
  val phase = RegInit(0.U(4.W))
  val fcsValue = Reg(UInt(32.W))
  val draining = RegInit(false.B)

  io.in.ready := ((state === idle || (state === gap && phase === 11.U)) && !draining) ||
    (state === payload && !held.last) || draining
  io.data := 0.U
  io.enable := false.B
  io.error := false.B
  io.done := false.B
  io.aborted := false.B
  io.underrun := false.B
  io.active := state =/= idle || draining

  // 已经在线路终止的坏输入，仍要排空至其输入 last 才能开始下一帧。
  when(draining && io.in.fire && io.in.bits.last) { draining := false.B }

  switch(state) {
    is(idle) {
      when(!draining && io.in.fire) {
        held := io.in.bits
        bytes := 0.U
        crc := "hffffffff".U
        phase := 0.U
        state := preamble
      }
    }
    is(preamble) {
      io.enable := true.B
      io.data := Mux(phase === 7.U, "hd5".U, "h55".U)
      when(phase === 7.U) { state := payload }.otherwise { phase := phase + 1.U }
    }
    is(payload) {
      val nextCrc = EthernetCrc.step(crc, held.data)
      io.enable := true.B
      io.data := held.data
      crc := nextCrc
      bytes := bytes + 1.U
      when(held.error || (bytes === (maxBytes - 1).U && !held.last)) {
        io.error := true.B
        io.aborted := true.B
        draining := !held.last && !(io.in.fire && io.in.bits.last)
        phase := 0.U
        state := gap
      }.elsewhen(held.last) {
        when(bytes < 59.U) { state := padding }
          .otherwise { fcsValue := ~nextCrc; phase := 0.U; state := fcs }
      }.elsewhen(io.in.valid) {
        held := io.in.bits
      }.otherwise {
        io.underrun := true.B
        draining := true.B
        state := abort
      }
    }
    is(padding) {
      val nextCrc = EthernetCrc.step(crc, 0.U(8.W))
      io.enable := true.B
      io.data := 0.U
      crc := nextCrc
      bytes := bytes + 1.U
      when(bytes === 59.U) { fcsValue := ~nextCrc; phase := 0.U; state := fcs }
    }
    is(fcs) {
      io.enable := true.B
      io.data := fcsValue(7, 0)
      fcsValue := fcsValue >> 8
      when(phase === 3.U) {
        io.done := true.B
        phase := 0.U
        state := gap
      }.otherwise { phase := phase + 1.U }
    }
    is(abort) {
      io.enable := true.B
      io.error := true.B
      io.aborted := true.B
      phase := 0.U
      state := gap
    }
    is(gap) {
      // 本状态恰好保持12个空闲周期。
      when(phase === 11.U) {
        when(!draining && io.in.fire) {
          held := io.in.bits
          bytes := 0.U
          crc := "hffffffff".U
          phase := 0.U
          state := preamble
        }.otherwise { state := idle }
      }.otherwise { phase := phase + 1.U }
    }
  }
}

/** 连续线路接收核：五字节尾窗剥FCS，末拍集中报告坏帧。 */
class MacRx(val maxBytes: Int = 2048) extends Module {
  require(maxBytes >= 60 && maxBytes <= 65535)
  val io = IO(new Bundle {
    val data = Input(UInt(8.W))
    val enable = Input(Bool())
    val error = Input(Bool())
    val out = Valid(new ByteBeat)
    val good = Output(Bool())
    val bad = Output(Bool())
    val active = Output(Bool())
  })
  val idle :: preamble :: payload :: discard :: Nil = Enum(4)
  val state = RegInit(idle)
  val preCount = RegInit(0.U(4.W))
  val count = RegInit(0.U(17.W))
  val tail = Reg(Vec(5, UInt(8.W)))
  val crc = RegInit("hffffffff".U(32.W))
  val sawError = RegInit(false.B)
  io.out.valid := false.B
  io.out.bits.data := tail(0)
  io.out.bits.last := false.B
  io.out.bits.error := false.B
  io.good := false.B
  io.bad := false.B
  io.active := state =/= idle

  switch(state) {
    is(idle) {
      when(io.enable) {
        when(io.data === "h55".U && !io.error) { preCount := 1.U; state := preamble }
          .otherwise { state := discard; io.bad := true.B }
      }
    }
    is(preamble) {
      when(!io.enable) { io.bad := true.B; state := idle }
        .elsewhen(io.error) { io.bad := true.B; state := discard }
        .elsewhen(preCount < 7.U && io.data === "h55".U) { preCount := preCount + 1.U }
        .elsewhen(preCount === 7.U && io.data === "hd5".U) {
          count := 0.U
          crc := "hffffffff".U
          sawError := false.B
          state := payload
        }.otherwise { io.bad := true.B; state := discard }
    }
    is(payload) {
      when(io.enable) {
        val over = count === (maxBytes + 4).U
        crc := EthernetCrc.step(crc, io.data)
        sawError := sawError || io.error
        for(i <- 0 until 4) { tail(i) := tail(i + 1) }
        tail(4) := io.data
        count := count + 1.U
        when(count >= 5.U) {
          io.out.valid := true.B
          io.out.bits.last := over
          io.out.bits.error := over
        }
        when(over) { state := discard; io.bad := true.B }
      }.otherwise {
        val bad = sawError || count < 64.U || crc =/= "hdebb20e3".U
        when(count >= 5.U) {
          io.out.valid := true.B
          io.out.bits.last := true.B
          io.out.bits.error := bad
        }
        io.bad := bad
        io.good := !bad
        state := idle
      }
    }
    is(discard) { when(!io.enable) { state := idle } }
  }
}

/** DDR I/O以外的纯数字编码；采样对已由外部按同一字节对齐。 */
class RgmiiDigital extends Module {
  val io = IO(new Bundle {
    val txData = Input(UInt(8.W))
    val txEnable = Input(Bool())
    val txError = Input(Bool())
    val txRise = Output(UInt(4.W))
    val txFall = Output(UInt(4.W))
    val txCtlRise = Output(Bool())
    val txCtlFall = Output(Bool())
    val rxRise = Input(UInt(4.W))
    val rxFall = Input(UInt(4.W))
    val rxCtlRise = Input(Bool())
    val rxCtlFall = Input(Bool())
    val rxData = Output(UInt(8.W))
    val rxEnable = Output(Bool())
    val rxError = Output(Bool())
  })
  io.txRise := io.txData(3, 0)
  io.txFall := io.txData(7, 4)
  io.txCtlRise := io.txEnable
  io.txCtlFall := io.txEnable ^ io.txError
  io.rxData := Cat(io.rxFall, io.rxRise)
  io.rxEnable := io.rxCtlRise
  io.rxError := io.rxCtlRise ^ io.rxCtlFall
}

/** 单时钟仅用于独立核回归；真实产品用各自TX/RX时钟实例化。 */
class MacCoreHarness(val maxBytes: Int = 2048) extends Module {
  val tx = Module(new MacTx(maxBytes))
  val rx = Module(new MacRx(maxBytes))
  val rg = Module(new RgmiiDigital)
  val io = IO(new Bundle {
    val txIn = Flipped(Decoupled(new ByteBeat))
    val txData = Output(UInt(8.W))
    val txEnable = Output(Bool())
    val txError = Output(Bool())
    val txDone = Output(Bool())
    val txAbort = Output(Bool())
    val txUnderrun = Output(Bool())
    val txActive = Output(Bool())
    val rxData = Input(UInt(8.W))
    val rxEnable = Input(Bool())
    val rxError = Input(Bool())
    val rxOut = Valid(new ByteBeat)
    val rxGood = Output(Bool())
    val rxBad = Output(Bool())
    val crcIn = Input(UInt(32.W))
    val crcByte = Input(UInt(8.W))
    val crcOut = Output(UInt(32.W))
    val rgTxData = Input(UInt(8.W))
    val rgTxEn = Input(Bool())
    val rgTxEr = Input(Bool())
    val rgTxRise = Output(UInt(4.W))
    val rgTxFall = Output(UInt(4.W))
    val rgTxCtlRise = Output(Bool())
    val rgTxCtlFall = Output(Bool())
    val rgRxRise = Input(UInt(4.W))
    val rgRxFall = Input(UInt(4.W))
    val rgRxCtlRise = Input(Bool())
    val rgRxCtlFall = Input(Bool())
    val rgRxData = Output(UInt(8.W))
    val rgRxEn = Output(Bool())
    val rgRxEr = Output(Bool())
  })
  tx.io.in <> io.txIn
  io.txData := tx.io.data
  io.txEnable := tx.io.enable
  io.txError := tx.io.error
  io.txDone := tx.io.done
  io.txAbort := tx.io.aborted
  io.txUnderrun := tx.io.underrun
  io.txActive := tx.io.active
  rx.io.data := io.rxData
  rx.io.enable := io.rxEnable
  rx.io.error := io.rxError
  io.rxOut := rx.io.out
  io.rxGood := rx.io.good
  io.rxBad := rx.io.bad
  io.crcOut := EthernetCrc.step(io.crcIn, io.crcByte)
  rg.io.txData := io.rgTxData
  rg.io.txEnable := io.rgTxEn
  rg.io.txError := io.rgTxEr
  io.rgTxRise := rg.io.txRise
  io.rgTxFall := rg.io.txFall
  io.rgTxCtlRise := rg.io.txCtlRise
  io.rgTxCtlFall := rg.io.txCtlFall
  rg.io.rxRise := io.rgRxRise
  rg.io.rxFall := io.rgRxFall
  rg.io.rxCtlRise := io.rgRxCtlRise
  rg.io.rxCtlFall := io.rgRxCtlFall
  io.rgRxData := rg.io.rxData
  io.rgRxEn := rg.io.rxEnable
  io.rgRxEr := rg.io.rxError
}

object EmitMac extends App {
  require(args.length >= 1, "输出目录必须显式指定")
  val maxBytes = if(args.length > 1) args(1).toInt else 2048
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(new MacCoreHarness(maxBytes),
    args = Array("--target-dir", args(0)),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"))
}

/** 产品GMII边界：两个独立字节时钟，无平台原语、无NIC或帧缓存。 */
class MacGmiiCore(val maxBytes: Int = 2048) extends RawModule {
  val io = IO(new Bundle {
    val txClock = Input(Clock())
    val txReset = Input(Bool())
    val rxClock = Input(Clock())
    val rxReset = Input(Bool())
    val txIn = Flipped(Decoupled(new ByteBeat))
    val txData = Output(UInt(8.W))
    val txEnable = Output(Bool())
    val txError = Output(Bool())
    val txDone = Output(Bool())
    val txAbort = Output(Bool())
    val txUnderrun = Output(Bool())
    val rxData = Input(UInt(8.W))
    val rxEnable = Input(Bool())
    val rxError = Input(Bool())
    val rxOut = Valid(new ByteBeat)
    val rxGood = Output(Bool())
    val rxBad = Output(Bool())
  })
  val tx = withClockAndReset(io.txClock, io.txReset) { Module(new MacTx(maxBytes)) }
  val rx = withClockAndReset(io.rxClock, io.rxReset) { Module(new MacRx(maxBytes)) }
  tx.io.in <> io.txIn
  io.txData := tx.io.data
  io.txEnable := tx.io.enable
  io.txError := tx.io.error
  io.txDone := tx.io.done
  io.txAbort := tx.io.aborted
  io.txUnderrun := tx.io.underrun
  rx.io.data := io.rxData
  rx.io.enable := io.rxEnable
  rx.io.error := io.rxError
  io.rxOut := rx.io.out
  io.rxGood := rx.io.good
  io.rxBad := rx.io.bad
}

object EmitMacProducts extends App {
  require(args.length >= 1, "输出目录必须显式指定")
  val maxBytes = if(args.length > 1) args(1).toInt else 2048
  val opts = Array("-disable-all-randomization", "-strip-debug-info")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(new MacGmiiCore(maxBytes),
    args = Array("--target-dir", args(0) + "/gmii"), firtoolOpts = opts)
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(new RgmiiDigital,
    args = Array("--target-dir", args(0) + "/rgmii-digital"), firtoolOpts = opts)
}

/** 仅导出既有纯组合数字层，不重生成或更改MAC/cache产品。 */
object EmitRgmiiDigital extends App {
  require(args.length == 1, "输出目录必须显式指定")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(new RgmiiDigital,
    args = Array("--target-dir", args(0)),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"))
}

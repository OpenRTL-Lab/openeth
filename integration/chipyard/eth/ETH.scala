package chipyard.eth

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.prci._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.subsystem.{BaseSubsystem, PBUS}
import freechips.rocketchip.tilelink._
import chipyard.iobinders.{OverrideLazyIOBinder, Port}
import chipyard.harness.{HarnessBinder, HasHarnessInstantiators}

// ============================================================================
// chipyard.eth —— verilog-ethernet 1G RGMII MAC 接入 chipyard 的薄壳。
// MAC 本体住在 FPGATop wrapper（gtx_clk 在那边生成）；本 wrapper 这边只做：
//   - TLRegisterNode MMIO 外设（默认 0x6000）
//   - 字节级 TX / RX FIFO，对外 AXI-Stream 8-bit
//   - MDIO 位带（mdc/mdio_o/mdio_t）+ mdio_i 状态读回
//   - PHY 复位输出
//   - WithEthPunchthrough IOBinder 把 EthExtIO 顶到 ChipTop 端口
//
// 寄存器映射 (base + offset)：
//   0x00 STATUS      (R)   bit0=tx_ready, bit1=rx_valid, bit2=mdio_i,
//                          bit[4:3]=mac_speed, bit5=mmcm_locked, bit6=gtx_rst,
//                          bit7=calib_done (DDR4 calib complete, ETH-C.1)
//   0x04 TX_PUSH     (W,9)  入队字节：data[7:0]，bit8=tlast (一帧最后一字节置 1)
//   0x08 RX_PEEK     (R,32) 不出队，查看队头: {21'b0, last, valid, data[7:0]}
//   0x0C RX_POP      (W)    写任意值，弹出一字节（必须先确认 RX_PEEK.valid=1）
//   0x10 MDIO_OUT    (RW)   bit0=mdc, bit1=mdio_o, bit2=mdio_t (1=三态)
//   0x14 PHY_RSTN    (RW)   bit0=phy_rstn (0=保持复位)
//   0x40 MDIO_COMMAND(W,32)  bit31=start, bit30=read, bit[29:25]=phy,
//                           bit[24:20]=reg, bit[15:0]=write data
//   0x44 MDIO_STATUS (R,32)  bit0=busy, bit1=done, bit2=error, bit3=read_valid
//   0x48 MDIO_RDATA  (R,16)  最近一次成功自动 read 的 16-bit data
//   0x4C MDIO_DIVIDER(RW,16) MDC half-period divider，f_mdc=f_pbus/(2*(div+1))
// ============================================================================

case class EthParams(
  address: BigInt = 0x6000,
  txFifoDepth: Int = 4096,
  rxFifoDepth: Int = 4096,
  mdioDefaultDivider: Int = 19,
  dataPlaneEnabled: Boolean = true)

case object EthKey extends Field[Option[EthParams]](None)

class EthExtIO extends Bundle {
  val tx_axis_tdata  = Output(UInt(8.W))
  val tx_axis_tvalid = Output(Bool())
  val tx_axis_tready = Input(Bool())
  val tx_axis_tlast  = Output(Bool())
  val rx_axis_tdata  = Input(UInt(8.W))
  val rx_axis_tvalid = Input(Bool())
  val rx_axis_tready = Output(Bool())
  val rx_axis_tlast  = Input(Bool())
  val mdio_mdc       = Output(Bool())
  val mdio_o         = Output(Bool())
  val mdio_t         = Output(Bool())
  val mdio_i         = Input(Bool())
  val phy_rstn       = Output(Bool())
  // MAC 状态信号（FpgaTop 驱动 → chipyard 计数）
  val mac_tx_good_frame    = Input(Bool())  // 1 周期脉冲：完成一帧 TX
  val mac_tx_bad_frame     = Input(Bool())
  val mac_tx_underflow     = Input(Bool())
  val mac_rx_good_frame    = Input(Bool())
  val mac_rx_bad_frame     = Input(Bool())
  val mac_rx_bad_fcs       = Input(Bool())
  val mac_speed            = Input(UInt(2.W))  // 00=10M 01=100M 10=1G
  // ETH-B.1: 暴露 MMCM lock + gtx 域 reset 状态到软件，让 debug 时排除 clock domain 问题
  val mmcm_locked          = Input(Bool())  // clk_wiz_eth locked，跨域，destination 端 sync
  val gtx_rst_status       = Input(Bool())  // gtx_clk 域当前 reset 状态（high=in reset），跨域同
  // ETH-C.1: 暴露 DDR4 calibration done，软件 cold-burn 后立即可读判 DDR4 是否 ready
  val calib_done           = Input(Bool())  // DDR4 IP c0_init_calib_complete，跨域，destination sync
  // ETH-C.1 round 4: 暴露 Si5338 配置 done，跟 calib_done 一起做 reset-decoupled 诊断
  val si5338_done          = Input(Bool())  // Si5338 I2C config done，跨域，destination sync
}

class EthTL(params: EthParams, beatBytes: Int)(implicit p: Parameters)
    extends ClockSinkDomain(ClockSinkParameters())(p) {
  val device = new SimpleDevice("eth", Seq(
    if (params.dataPlaneEnabled) "ucbbar,eth" else "openhw,openeth-management-v1"))
  // concurrency=1 在 regmap 和总线间加一拍 skid 缓冲，避免我们 FIFO 的反压
  // 沿 a/d 通道形成组合环（不加会触发 firrtl CheckCombLoops）
  val node = TLRegisterNode(
    address = Seq(AddressSet(params.address, 0xfff)),
    device = device,
    concurrency = 1,
    beatBytes = beatBytes)

  override lazy val module = new EthImpl
  class EthImpl extends Impl {
    val external = IO(new EthExtIO)

    withClockAndReset(clock, reset) {
      val mdcRaw   = RegInit(false.B)
      val mdioORaw = RegInit(false.B)
      val mdioTRaw = RegInit(true.B)
      val phy_rstn = RegInit(false.B)

      val mdioController = Module(new Clause22MdioController(params.mdioDefaultDivider))
      val mdioDivider = RegInit(params.mdioDefaultDivider.U(16.W))
      val mdioCommandWrite = Wire(Decoupled(UInt(32.W)))
      val mdioCommandStart = mdioCommandWrite.bits(31)

      mdioController.io.command.valid := mdioCommandWrite.valid && mdioCommandStart
      mdioController.io.command.bits.read := mdioCommandWrite.bits(30)
      mdioController.io.command.bits.phyAddress := mdioCommandWrite.bits(29, 25)
      mdioController.io.command.bits.registerAddress := mdioCommandWrite.bits(24, 20)
      mdioController.io.command.bits.writeData := mdioCommandWrite.bits(15, 0)
      mdioCommandWrite.ready := !mdioCommandStart || mdioController.io.command.ready
      mdioController.io.divider := mdioDivider

      // TX 单 push 端口：9 位（高位 tlast，低 8 位 data）；软件写 0x04 时填
      //   data=val&0xff, tlast=(val>>8)&1
      val txPush = Wire(new DecoupledIO(UInt(9.W)))
      val rxPopCmd = Wire(new DecoupledIO(UInt(1.W)))
      val rxPeek = Wire(UInt(32.W))
      val rxValid = Wire(Bool())
      rxPopCmd.ready := true.B

      // 展开期选择：OpenETH只保留管理面，不生成旧包FIFO/指针/数据状态。
      // 旧寄存器写接受但无副作用，端口空壳只维持参考板封装兼容。
      if (params.dataPlaneEnabled) {
        val txFifo = Module(new Queue(UInt(9.W), params.txFifoDepth))
        val rxFifo = Module(new Queue(UInt(9.W), params.rxFifoDepth))
        txPush.ready := txFifo.io.enq.ready
        txFifo.io.enq.valid := txPush.valid
        txFifo.io.enq.bits := txPush.bits
        external.tx_axis_tdata := txFifo.io.deq.bits(7, 0)
        external.tx_axis_tlast := txFifo.io.deq.bits(8)
        external.tx_axis_tvalid := txFifo.io.deq.valid
        txFifo.io.deq.ready := external.tx_axis_tready
        rxFifo.io.enq.valid := external.rx_axis_tvalid
        rxFifo.io.enq.bits := Cat(external.rx_axis_tlast, external.rx_axis_tdata)
        external.rx_axis_tready := rxFifo.io.enq.ready
        rxValid := rxFifo.io.deq.valid
        rxPeek := Cat(0.U(21.W), rxFifo.io.deq.bits(8), rxValid, rxFifo.io.deq.bits(7, 0))
        rxFifo.io.deq.ready := rxPopCmd.valid && rxValid
      } else {
        txPush.ready := true.B
        external.tx_axis_tdata := 0.U
        external.tx_axis_tlast := false.B
        external.tx_axis_tvalid := false.B
        external.rx_axis_tready := false.B
        rxValid := false.B
        rxPeek := 0.U
      }

      external.mdio_mdc := Mux(mdioController.io.busy, mdioController.io.mdc, mdcRaw)
      external.mdio_o   := Mux(mdioController.io.busy, mdioController.io.mdio_o, mdioORaw)
      external.mdio_t   := Mux(mdioController.io.busy, mdioController.io.mdio_t, mdioTRaw)
      external.phy_rstn := phy_rstn
      val mdio_i_live = external.mdio_i
      mdioController.io.mdio_i := mdio_i_live

      val mdioStatus = Cat(
        0.U(28.W),
        mdioController.io.readValid,
        mdioController.io.error,
        mdioController.io.done,
        mdioController.io.busy)

      // MAC 状态计数器（边沿计数，按 PBUS clock）
      def edgeCounter(sig: Bool, w: Int = 32): UInt = {
        val cnt = RegInit(0.U(w.W))
        val prev = RegNext(sig, false.B)
        when (sig && !prev) { cnt := cnt + 1.U }
        cnt
      }
      val cntTxGood    = edgeCounter(external.mac_tx_good_frame)
      val cntTxBad     = edgeCounter(external.mac_tx_bad_frame)
      val cntTxUnder   = edgeCounter(external.mac_tx_underflow)
      val cntRxGood    = edgeCounter(external.mac_rx_good_frame)
      val cntRxBad     = edgeCounter(external.mac_rx_bad_frame)
      val cntRxBadFcs  = edgeCounter(external.mac_rx_bad_fcs)

      // ETH-B.1 (L031 fix) + ETH-C.1: 把 mmcm_locked + gtx_rst + calib_done + si5338_done 同步到 PBUS 时钟域，2-stage sync
      // 来自 FpgaTopEth：clk_wiz_eth.locked / gtx_clk 域 reset / DDR4 IP calib_done / Si5338 done，跨域
      val mmcm_locked_sync = RegNext(RegNext(external.mmcm_locked, false.B), false.B)
      val gtx_rst_sync     = RegNext(RegNext(external.gtx_rst_status, true.B), true.B)
      val calib_done_sync  = RegNext(RegNext(external.calib_done, false.B), false.B)
      val si5338_done_sync = RegNext(RegNext(external.si5338_done, false.B), false.B)

      // STATUS layout (32-bit):
      //   [8]   si5338_done Si5338 I2C config done (ETH-C.1 round 4)
      //   [7]   calib_done  DDR4 IP calibration complete (ETH-C.1)
      //   [6]   gtx_rst     gtx_clk 域 reset 状态 (1=in reset, MAC TX 域死)
      //   [5]   mmcm_locked clk_wiz_eth MMCM lock 状态 (1=locked)
      //   [4:3] mac_speed   00=10M 01=100M 10=1G
      //   [2]   mdio_i      当前 PHY MDIO 回读位
      //   [1]   rx_valid    RX FIFO 非空
      //   [0]   tx_ready    TX FIFO 有空间
      val status = Cat(0.U(23.W), si5338_done_sync, calib_done_sync, gtx_rst_sync, mmcm_locked_sync,
                       external.mac_speed, mdio_i_live,
                       rxValid, (if (params.dataPlaneEnabled) txPush.ready else false.B))

      node.regmap(
        0x00 -> Seq(RegField.r(32, status)),
        0x04 -> Seq(RegField.w(9, txPush)),
        0x08 -> Seq(RegField.r(32, rxPeek)),
        0x0C -> Seq(RegField.w(1, rxPopCmd)),
        0x10 -> Seq(RegField(1, mdcRaw), RegField(1, mdioORaw), RegField(1, mdioTRaw)),
        0x14 -> Seq(RegField(1, phy_rstn)),
        // 诊断计数器：从 MAC 内部状态信号边沿计数（per PBUS clock）
        0x20 -> Seq(RegField.r(32, cntTxGood)),     // 完成 TX 好帧数
        0x24 -> Seq(RegField.r(32, cntTxBad)),      // TX 坏帧（中断 / 丢弃）
        0x28 -> Seq(RegField.r(32, cntTxUnder)),    // TX underflow（MAC FIFO 饿死）
        0x2C -> Seq(RegField.r(32, cntRxGood)),     // 完成 RX 好帧数
        0x30 -> Seq(RegField.r(32, cntRxBad)),      // RX 坏帧
        0x34 -> Seq(RegField.r(32, cntRxBadFcs)),   // RX FCS 错
        0x40 -> Seq(RegField.w(32, mdioCommandWrite)),
        0x44 -> Seq(RegField.r(32, mdioStatus)),
        0x48 -> Seq(RegField.r(16, mdioController.io.readData)),
        0x4C -> Seq(RegField(16, mdioDivider))
      )
    }
  }
}

// trait：创建 LazyModule + 总线挂接 + InModuleBody surface external IO 到 BaseSubsystem 层
trait CanHavePeripheryEth { this: BaseSubsystem =>
  private val portName = "eth"
  private val pbus = locateTLBusWrapper(PBUS)

  // 把 EthImpl 内部的 external IO 提到 BaseSubsystem.module 层级，对应到 system 上的端口
  val ethExtOpt = p(EthKey).map { params =>
    val eth = LazyModule(new EthTL(params, pbus.beatBytes)(p))
    eth.clockNode := pbus.fixedClockNode
    pbus.coupleTo(portName) { eth.node := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _ }
    InModuleBody {
      val ext = IO(new EthExtIO).suggestName("eth_0")
      ext <> eth.module.external
      ext
    }
  }
}

// IOBinder：在 ChipTop 这一层再 punch 一个 eth_0 端口，把 system.ethExtOpt 连过去
case class EthExtPort(getIO: () => EthExtIO) extends Port[EthExtIO]

class WithEthPunchthrough extends OverrideLazyIOBinder({
  (system: CanHavePeripheryEth) => {
    InModuleBody {
      system.ethExtOpt.map { ethExt =>
        val io = IO(new EthExtIO).suggestName("eth_0")
        io <> ethExt   // ModuleValue auto-unwrap 在 InModuleBody scope
        (Seq(EthExtPort(() => io)), Nil)
      }.getOrElse((Nil, Nil))
    }
  }
})

class WithEth(address: BigInt = 0x6000, mdioDefaultDivider: Int = 19,
              dataPlaneEnabled: Boolean = true)
    extends Config((site, here, up) => {
  case EthKey => Some(EthParams(
    address = address,
    mdioDefaultDivider = mdioDefaultDivider,
    dataPlaneEnabled = dataPlaneEnabled))
})

// HarnessBinder：仿真 TestHarness 不需要真的接 MAC，把 eth 输入侧 tie off 让生成通过。
// FPGA 流程不经 TestHarness，所以这只影响 verilator 仿真。
class WithEthTiedOff extends HarnessBinder({
  case (_: HasHarnessInstantiators, port: EthExtPort, _: Int) => {
    val io = port.getIO()
    io.tx_axis_tready := false.B
    io.rx_axis_tdata  := 0.U
    io.rx_axis_tvalid := false.B
    io.rx_axis_tlast  := false.B
    io.mdio_i         := false.B
    io.mac_tx_good_frame := false.B
    io.mac_tx_bad_frame  := false.B
    io.mac_tx_underflow  := false.B
    io.mac_rx_good_frame := false.B
    io.mac_rx_bad_frame  := false.B
    io.mac_rx_bad_fcs    := false.B
    io.mac_speed         := 0.U
    io.mmcm_locked       := false.B
    io.gtx_rst_status    := true.B
    io.calib_done        := false.B
    io.si5338_done       := false.B
  }
})

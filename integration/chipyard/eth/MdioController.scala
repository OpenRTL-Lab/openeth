package chipyard.eth

import chisel3._
import chisel3.util._

/** One IEEE 802.3 Clause 22 management transaction. */
class Clause22MdioCommand extends Bundle {
  val read = Bool()
  val phyAddress = UInt(5.W)
  val registerAddress = UInt(5.W)
  val writeData = UInt(16.W)
}

/**
  * Synthesizable IEEE 802.3 Clause 22 MDIO master.
  *
  * The controller emits the full 32-one preamble and one 64-bit transaction.
  * `divider` specifies the number of peripheral-clock cycles minus one in each
  * MDC half-period:
  *
  *   f_mdc = f_clock / (2 * (divider + 1))
  *
  * The divider is clamped to at least two and latched when a command fires.
  * Read transactions release MDIO immediately after REGAD.  A Clause 22 PHY
  * leaves the first turnaround sample high-Z, drives zero on the second, then
  * returns 16 data bits.  A missing turnaround zero sets `error` and leaves
  * `readValid` clear.
  */
class Clause22MdioController(defaultDivider: Int = 19) extends Module {
  require(defaultDivider >= 2 && defaultDivider <= 0xffff,
    s"defaultDivider must be in [2, 65535], got ${defaultDivider}")

  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new Clause22MdioCommand))
    val divider = Input(UInt(16.W))

    val mdio_i = Input(Bool())
    val mdc = Output(Bool())
    val mdio_o = Output(Bool())
    val mdio_t = Output(Bool())

    val busy = Output(Bool())
    val done = Output(Bool())
    val error = Output(Bool())
    val readValid = Output(Bool())
    val readData = Output(UInt(16.W))
  })

  val busyReg = RegInit(false.B)
  val doneReg = RegInit(false.B)
  val errorReg = RegInit(false.B)
  val readValidReg = RegInit(false.B)
  val readDataReg = RegInit(0.U(16.W))

  val commandReadReg = RegInit(false.B)
  val bitIndexReg = RegInit(0.U(6.W))
  val dividerReg = RegInit(defaultDivider.U(16.W))
  val dividerCountReg = RegInit(0.U(16.W))
  val mdcReg = RegInit(false.B)
  val transmitShiftReg = RegInit(0.U(64.W))
  val receiveShiftReg = RegInit(0.U(16.W))
  val turnaroundErrorReg = RegInit(false.B)

  // MDIO is asynchronous to the peripheral clock even though MDC is generated
  // here.  Synchronize the return pin before sampling it on generated MDC rises.
  val mdioSync0 = RegNext(io.mdio_i, true.B)
  val mdioSync1 = RegNext(mdioSync0, true.B)

  io.command.ready := !busyReg

  io.mdc := mdcReg
  io.mdio_o := transmitShiftReg(63)
  io.mdio_t := !busyReg || (commandReadReg && bitIndexReg >= 46.U)

  io.busy := busyReg
  io.done := doneReg
  io.error := errorReg
  io.readValid := readValidReg
  io.readData := readDataReg

  when (io.command.fire) {
    val operation = Mux(io.command.bits.read, "b10".U(2.W), "b01".U(2.W))
    val tail = Mux(
      io.command.bits.read,
      0.U(18.W),
      Cat("b10".U(2.W), io.command.bits.writeData))

    transmitShiftReg := Cat(
      "hffffffff".U(32.W),
      "b01".U(2.W),
      operation,
      io.command.bits.phyAddress,
      io.command.bits.registerAddress,
      tail)

    commandReadReg := io.command.bits.read
    bitIndexReg := 0.U
    dividerReg := Mux(io.divider < 2.U, 2.U, io.divider)
    dividerCountReg := 0.U
    mdcReg := false.B
    receiveShiftReg := 0.U
    turnaroundErrorReg := false.B

    busyReg := true.B
    doneReg := false.B
    errorReg := false.B
    readValidReg := false.B
  }.elsewhen (busyReg) {
    when (dividerCountReg === dividerReg) {
      dividerCountReg := 0.U

      when (!mdcReg) {
        // Low-to-high MDC edge: the PHY/MAC samples driven data, and the
        // controller samples released turnaround/read data.
        mdcReg := true.B
        // Clause 22 read turnaround is Z at index 46 and PHY-driven zero at
        // index 47.  The 16 data bits are sampled MSB-first at indices 48--63.
        when (commandReadReg && bitIndexReg === 47.U) {
          turnaroundErrorReg := mdioSync1
        }
        when (commandReadReg && bitIndexReg >= 48.U && bitIndexReg <= 63.U) {
          receiveShiftReg := Cat(receiveShiftReg(14, 0), mdioSync1)
        }
      }.otherwise {
        // High-to-low MDC edge: advance to the next bit, changing MDIO only
        // while MDC is low.  Finishing here guarantees an idle-low MDC.
        mdcReg := false.B
        when (bitIndexReg === 63.U) {
          busyReg := false.B
          doneReg := true.B
          errorReg := commandReadReg && turnaroundErrorReg
          readValidReg := commandReadReg && !turnaroundErrorReg
          when (commandReadReg) {
            readDataReg := receiveShiftReg
          }
        }.otherwise {
          bitIndexReg := bitIndexReg + 1.U
          transmitShiftReg := Cat(transmitShiftReg(62, 0), 0.U(1.W))
        }
      }
    }.otherwise {
      dividerCountReg := dividerCountReg + 1.U
    }
  }

  when (!busyReg) {
    assert(!mdcReg, "Clause22MdioController must leave MDC low while idle")
  }
}

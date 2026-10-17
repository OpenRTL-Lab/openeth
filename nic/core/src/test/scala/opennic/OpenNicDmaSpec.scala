// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: nic/core/src/test/scala/opennic/OpenNicDmaSpec.scala
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
import scala.collection.mutable
import scala.util.Random

class OpenNicDmaSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "OpenNicDma"
  case class D(address: Long, length: Int, cookie: Int)
  case class Beat(data: BigInt, keep: Int, last: Boolean, error: Boolean = false)
  case class C(cookie: Int, length: Int, status: Int)
  private val annotations = Seq(VerilatorBackendAnnotation, VerilatorCFlags(Seq("-DWData=EData")))
  private def beats(bytes: Seq[Int]): Seq[Beat] = bytes.grouped(8).toSeq.zipWithIndex.map {
    case (chunk, index) => Beat(chunk.zipWithIndex.map { case (b, i) => BigInt(b) << (8 * i) }.sum,
      (1 << chunk.size) - 1, index == (bytes.size - 1) / 8)
  }
  private def payload(length: Int, seed: Int): Seq[Int] = {
    val random = new Random(seed); Seq.fill(length)(random.nextInt(256))
  }
  private def pokeDescriptor(port: chisel3.util.DecoupledIO[Descriptor], d: D): Unit = {
    port.bits.address.poke(BigInt(d.address).U)
    port.bits.length.poke(d.length.U)
    port.bits.cookie.poke(d.cookie.U)
  }

  class Model(dut: OpenNicDma, seed: Int = 1) {
    val random = new Random(seed)
    val memory = mutable.Map.empty[Long, Int].withDefaultValue(0xa5)
    val txDescriptors = mutable.Queue.empty[D]
    val rxDescriptors = mutable.Queue.empty[D]
    val rxBeats = mutable.Queue.empty[Beat]
    val txPackets = mutable.ArrayBuffer.empty[Seq[Int]]
    val txCompletions = mutable.ArrayBuffer.empty[C]
    val rxCompletions = mutable.ArrayBuffer.empty[C]
    val writes = mutable.ArrayBuffer.empty[(Long, Int)]
    val readErrors = mutable.Set.empty[Long]
    val writeErrors = mutable.Set.empty[Long]
    private var readPending: Option[(Long, Int)] = None
    private var writePending: Option[(Boolean, Int)] = None
    private var txFrame = Vector.empty[Int]
    private var txHeld: Option[Beat] = None
    private var writeHeld: Option[(Long, BigInt, Int)] = None
    private var readHeld: Option[Long] = None
    private var txDoneHeld: Option[C] = None
    private var rxDoneHeld: Option[C] = None
    var cycles = 0L
    var reads = 0L
    val txSubmitCycles = mutable.ArrayBuffer.empty[Long]
    val txDoneCycles = mutable.ArrayBuffer.empty[Long]
    val rxSubmitCycles = mutable.ArrayBuffer.empty[Long]
    val rxDoneCycles = mutable.ArrayBuffer.empty[Long]
    var allowCompletion = true
    var stalls = true

    dut.io.txDescriptor.valid.poke(false.B)
    pokeDescriptor(dut.io.txDescriptor, D(0, 0, 0))
    dut.io.rxDescriptor.valid.poke(false.B)
    pokeDescriptor(dut.io.rxDescriptor, D(0, 0, 0))
    dut.io.tx.ready.poke(false.B)
    dut.io.rx.valid.poke(false.B)
    dut.io.rx.bits.data.poke(0.U); dut.io.rx.bits.keep.poke(0.U)
    dut.io.rx.bits.last.poke(false.B); dut.io.rx.bits.error.poke(false.B)
    dut.io.txCompletion.ready.poke(false.B); dut.io.rxCompletion.ready.poke(false.B)
    dut.io.memory.read.ready.poke(false.B)
    dut.io.memory.readResult.valid.poke(false.B)
    dut.io.memory.readResult.bits.data.poke(0.U); dut.io.memory.readResult.bits.error.poke(false.B)
    dut.io.memory.write.ready.poke(false.B)
    dut.io.memory.writeResult.valid.poke(false.B); dut.io.memory.writeResult.bits.poke(false.B)
    dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B)
    dut.clock.setTimeout(500000)

    def put(address: Long, bytes: Seq[Int]): Unit = bytes.zipWithIndex.foreach {
      case (b, i) => memory(address + i) = b
    }
    def get(address: Long, size: Int): Seq[Int] = (0 until size).map(i => memory(address + i))
    private def ready(): Boolean = !stalls || random.nextInt(4) != 0
    private def completion(port: chisel3.util.DecoupledIO[Completion]): C =
      C(port.bits.cookie.peek().litValue.toInt, port.bits.length.peek().litValue.toInt,
        port.bits.status.peek().litValue.toInt)
    private def held[A](previous: Option[A], valid: Boolean, value: => A, ready: Boolean): Option[A] = {
      previous.foreach { old => assert(valid, "backpressured valid withdrawn"); assert(value == old, "backpressured payload changed") }
      if (valid && !ready) Some(value) else None
    }

    def tick(): Unit = {
      dut.io.txDescriptor.valid.poke(txDescriptors.nonEmpty.B)
      txDescriptors.headOption.foreach(d => pokeDescriptor(dut.io.txDescriptor, d))
      dut.io.rxDescriptor.valid.poke(rxDescriptors.nonEmpty.B)
      rxDescriptors.headOption.foreach(d => pokeDescriptor(dut.io.rxDescriptor, d))
      dut.io.rx.valid.poke(rxBeats.nonEmpty.B)
      rxBeats.headOption.foreach { b =>
        dut.io.rx.bits.data.poke(b.data.U); dut.io.rx.bits.keep.poke(b.keep.U)
        dut.io.rx.bits.last.poke(b.last.B); dut.io.rx.bits.error.poke(b.error.B)
      }
      val tr = ready(); val tc = allowCompletion && ready(); val rc = allowCompletion && ready()
      val rr = readPending.isEmpty && ready(); val wr = writePending.isEmpty && ready()
      dut.io.tx.ready.poke(tr.B); dut.io.txCompletion.ready.poke(tc.B); dut.io.rxCompletion.ready.poke(rc.B)
      dut.io.memory.read.ready.poke(rr.B); dut.io.memory.write.ready.poke(wr.B)
      val rp = readPending.filter(_._2 == 0)
      dut.io.memory.readResult.valid.poke(rp.nonEmpty.B)
      rp.foreach { case (addr, _) =>
        val data = (0 until 8).map(i => BigInt(memory(addr + i)) << (i * 8)).sum
        dut.io.memory.readResult.bits.data.poke(data.U)
        dut.io.memory.readResult.bits.error.poke(readErrors(addr).B)
      }
      val wp = writePending.filter(_._2 == 0)
      dut.io.memory.writeResult.valid.poke(wp.nonEmpty.B)
      wp.foreach { case (err, _) => dut.io.memory.writeResult.bits.poke(err.B) }

      def valid(port: chisel3.util.DecoupledIO[_]): Boolean = port.valid.peek().litToBoolean
      def fire(port: chisel3.util.DecoupledIO[_]): Boolean = valid(port) && port.ready.peek().litToBoolean
      if (fire(dut.io.txDescriptor)) { txDescriptors.dequeue(); txSubmitCycles += cycles }
      if (fire(dut.io.rxDescriptor)) { rxDescriptors.dequeue(); rxSubmitCycles += cycles }
      if (fire(dut.io.rx)) rxBeats.dequeue()
      val txBeat = Beat(dut.io.tx.bits.data.peek().litValue, dut.io.tx.bits.keep.peek().litValue.toInt,
        dut.io.tx.bits.last.peek().litToBoolean, dut.io.tx.bits.error.peek().litToBoolean)
      txHeld = held(txHeld, valid(dut.io.tx), txBeat, tr)
      txDoneHeld = held(txDoneHeld, valid(dut.io.txCompletion), completion(dut.io.txCompletion), tc)
      rxDoneHeld = held(rxDoneHeld, valid(dut.io.rxCompletion), completion(dut.io.rxCompletion), rc)
      val wa = dut.io.memory.write.bits.address.peek().litValue.toLong
      val wd = dut.io.memory.write.bits.data.peek().litValue
      val wm = dut.io.memory.write.bits.mask.peek().litValue.toInt
      val ra = dut.io.memory.read.bits.peek().litValue.toLong
      writeHeld = held(writeHeld, valid(dut.io.memory.write), (wa, wd, wm), wr)
      readHeld = held(readHeld, valid(dut.io.memory.read), ra, rr)
      if (fire(dut.io.tx)) {
        assert(!txBeat.error)
        assert(FrameRulesSoftware.valid(txBeat.keep, txBeat.last))
        txFrame ++= (0 until 8).filter(i => (txBeat.keep & (1 << i)) != 0).map(i => ((txBeat.data >> (8 * i)) & 255).toInt)
        if (txBeat.last) { txPackets += txFrame; txFrame = Vector.empty }
      }
      if (fire(dut.io.txCompletion)) { txCompletions += completion(dut.io.txCompletion); txDoneCycles += cycles }
      if (fire(dut.io.rxCompletion)) { rxCompletions += completion(dut.io.rxCompletion); rxDoneCycles += cycles }
      if (fire(dut.io.memory.readResult)) readPending = None
      else readPending = readPending.map { case (a, delay) => (a, (delay - 1) max 0) }
      if (fire(dut.io.memory.writeResult)) writePending = None
      else writePending = writePending.map { case (e, delay) => (e, (delay - 1) max 0) }
      if (fire(dut.io.memory.read)) { assert(readPending.isEmpty); reads += 1; readPending = Some((ra, if (stalls) random.nextInt(4) else 0)) }
      if (fire(dut.io.memory.write)) {
        assert(writePending.isEmpty)
        writes += ((wa, wm))
        if (!writeErrors(wa)) (0 until 8).filter(i => (wm & (1 << i)) != 0).foreach(i => memory(wa + i) = ((wd >> (8 * i)) & 255).toInt)
        writePending = Some((writeErrors(wa), if (stalls) random.nextInt(4) else 0))
      }
      dut.clock.step(); cycles += 1
    }
    def until(condition: => Boolean, limit: Int = 300000): Unit = {
      var n = 0
      while (!condition && n < limit) { tick(); n += 1 }
      assert(condition, s"timeout after $n cycles")
    }
    def settle(): Unit = (0 until 12).foreach(_ => tick())
  }

  object FrameRulesSoftware {
    def valid(keep: Int, last: Boolean): Boolean =
      if (last) keep != 0 && (keep & (keep + 1)) == 0 else keep == 255
  }

  it should "transfer exact full duplex bytes with randomized memory MAC and completion stalls" in {
    test(new OpenNicDma()).withAnnotations(annotations) { dut =>
      val m = new Model(dut, 17)
      val lengths = Seq(1, 2, 7, 8, 9, 60, 64, 65, 1514, 2047, 2048) ++
        Seq.fill(30)(1 + m.random.nextInt(2048))
      val txExpected = lengths.zipWithIndex.map { case (n, i) => payload(n, 100 + i) }
      val rxExpected = lengths.reverse.zipWithIndex.map { case (n, i) => payload(n, 200 + i) }
      txExpected.zipWithIndex.foreach { case (bytes, i) =>
        val a = 0x10000L + i * 4096L; m.put(a, bytes)
        m.txDescriptors += D(a, bytes.size, i)
      }
      rxExpected.zipWithIndex.foreach { case (bytes, i) =>
        m.rxDescriptors += D(0x100000L + i * 4096L, 2048, 1000 + i)
        m.rxBeats ++= beats(bytes)
      }
      m.until(m.txCompletions.size == lengths.size && m.rxCompletions.size == lengths.size)
      m.settle()
      m.txPackets.toSeq shouldBe txExpected
      m.txCompletions.toSeq shouldBe txExpected.zipWithIndex.map { case (b, i) => C(i, b.size, 0) }
      m.rxCompletions.toSeq shouldBe rxExpected.zipWithIndex.map { case (b, i) => C(1000 + i, b.size, 0) }
      rxExpected.zipWithIndex.foreach { case (bytes, i) =>
        val a = 0x100000L + i * 4096L
        m.get(a, bytes.size) shouldBe bytes
        m.get(a + bytes.size, 16) shouldBe Seq.fill(16)(0xa5)
        m.get(a - 8, 8) shouldBe Seq.fill(8)(0xa5)
      }
      dut.io.errors.expect(0.U); dut.io.dropped.expect(0.U)
      // 参考比较负控：翻转一个字节或完成标签必须被比较器区分。
      m.txPackets.head.updated(0, m.txPackets.head.head ^ 1) should not be txExpected.head
      m.txCompletions.head.copy(cookie = 999) should not be C(0, lengths.head, 0)
      println(s"OPENNIC_DMA_PASS frames=${lengths.size * 2} cycles=${m.cycles} read_beats=${m.reads} write_beats=${m.writes.size}")
    }
  }

  it should "reject bad descriptors and never send partial frames after read errors" in {
    test(new OpenNicDma()).withAnnotations(annotations) { dut =>
      val m = new Model(dut, 22)
      m.txDescriptors ++= Seq(D(0x1001, 64, 1), D(0x2000, 0, 2), D(0x2000, 2049, 3), D(0x3000, 64, 4))
      m.readErrors += 0x3010L
      m.until(m.txCompletions.size == 4)
      m.txCompletions.map(_.status).toSeq shouldBe Seq(1, 1, 1, 2)
      m.txPackets shouldBe empty
      m.reads shouldBe 3L
      dut.io.errors.expect(4.U)
    }
  }

  it should "drain malformed and oversized frames preserve boundaries and report write failures" in {
    test(new OpenNicDma()).withAnnotations(annotations) { dut =>
      val m = new Model(dut, 42)
      val inputs = Seq(
        beats(payload(9, 1)).updated(0, Beat(55, 127, false)),
        beats(payload(32, 2)).updated(3, Beat(0, 255, true, true)),
        beats(payload(2057, 3)),
        Seq(Beat(0, 5, true)),
        Seq(Beat(0, 0, true)),
        beats(payload(61, 8)))
      inputs.zipWithIndex.foreach { case (frame, i) =>
        m.rxDescriptors += D(0x20000 + i * 4096L, 2048, i)
        m.rxBeats ++= frame
      }
      m.writeErrors += (0x20000 + 5 * 4096L + 8)
      m.until(m.rxCompletions.size == inputs.size)
      m.rxCompletions.map(_.status).toSeq shouldBe Seq(3, 3, 3, 3, 3, 2)
      m.writes.size shouldBe 2
      dut.io.errors.expect(6.U)
      dut.io.dropped.expect(0.U)
      m.rxDescriptors += D(0x50000, 2048, 99)
      m.rxBeats ++= beats(payload(63, 77))
      m.until(m.rxCompletions.size == 7)
      m.rxCompletions.last shouldBe C(99, 63, 0)
      m.get(0x50000, 63) shouldBe payload(63, 77)
    }
  }

  it should "drop whole frames without buffers and hold completions until acknowledged" in {
    test(new OpenNicDma()).withAnnotations(annotations) { dut =>
      val m = new Model(dut)
      m.rxBeats ++= beats(payload(101, 90))
      m.until(m.rxBeats.isEmpty); m.settle()
      dut.io.dropped.expect(1.U)
      m.writes shouldBe empty; m.rxCompletions shouldBe empty
      m.allowCompletion = false
      m.rxDescriptors += D(0x10000, 64, 40)
      m.rxBeats ++= beats(payload(64, 91))
      m.txDescriptors += D(0x20000, 8, 50)
      m.until(dut.io.rxCompletion.valid.peek().litToBoolean && dut.io.txCompletion.valid.peek().litToBoolean)
      (0 until 200).foreach(_ => m.tick())
      m.rxCompletions shouldBe empty; m.txCompletions shouldBe empty
      m.allowCompletion = true
      m.until(m.rxCompletions.size == 1 && m.txCompletions.size == 1)
      m.rxCompletions.head shouldBe C(40, 64, 0)
      m.txCompletions.head shouldBe C(50, 8, 0)
    }
  }

  it should "reject receive descriptors and undersized buffers then recover after system reset" in {
    test(new OpenNicDma()).withAnnotations(annotations) { dut =>
      val m = new Model(dut, 100)
      m.rxDescriptors ++= Seq(D(0x1001, 2048, 1), D(0x2000, 0, 2), D(0x2000, 2049, 3), D(0x3000, 63, 4))
      (0 until 4).foreach(i => m.rxBeats ++= beats(payload(64, i)))
      m.until(m.rxCompletions.size == 4)
      m.rxCompletions.map(_.status).toSeq shouldBe Seq(1, 1, 1, 3)
      m.writes shouldBe empty
      m.txDescriptors += D(0x4000, 2048, 90)
      m.until(m.reads > 2)
      // 全系统复位同时取消外部仿真内存事务，不把复位当运行时总线取消。
      val afterReset = new Model(dut, 200)
      val p = payload(1514, 999)
      afterReset.put(0x4000, p)
      afterReset.txDescriptors += D(0x4000, p.size, 91)
      afterReset.rxDescriptors += D(0x6000, 2048, 92)
      afterReset.rxBeats ++= beats(p)
      afterReset.until(afterReset.txCompletions.nonEmpty && afterReset.rxCompletions.nonEmpty)
      afterReset.txPackets.toSeq shouldBe Seq(p)
      afterReset.get(0x6000, p.size) shouldBe p
      afterReset.txCompletions.toSeq shouldBe Seq(C(91, 1514, 0))
      afterReset.rxCompletions.toSeq shouldBe Seq(C(92, 1514, 0))
      dut.io.errors.expect(0.U)
    }
  }

  it should "measure single frame latency separately from sustained ideal interface throughput" in {
    test(new OpenNicDma()).withAnnotations(annotations) { dut =>
      val m = new Model(dut)
      m.stalls = false
      val count = 64
      val bytes = payload(1514, 123)
      (0 until count).foreach { i =>
        m.put(0x10000L + i * 4096L, bytes)
        m.txDescriptors += D(0x10000L + i * 4096L, bytes.size, i)
        m.rxDescriptors += D(0x100000L + i * 4096L, 2048, i)
        m.rxBeats ++= beats(bytes)
      }
      m.until(m.txCompletions.size == count && m.rxCompletions.size == count)
      m.txPackets.foreach(_ shouldBe bytes)
      val txLatency = m.txDoneCycles.head - m.txSubmitCycles.head
      val rxLatency = m.rxDoneCycles.head - m.rxSubmitCycles.head
      val txSpan = m.txDoneCycles.last - m.txDoneCycles.head
      val rxSpan = m.rxDoneCycles.last - m.rxDoneCycles.head
      println(s"OPENNIC_IDEAL_PERF frame_bytes=1514 samples=$count tx_latency_clks=$txLatency rx_latency_clks=$rxLatency tx_completion_span_clks=$txSpan rx_completion_span_clks=$rxSpan completion_intervals=${count - 1}")
      txLatency should be > 0L
      rxLatency should be > 0L
      txSpan should be >= (count - 1).toLong
    }
  }
}

<!-- OpenRTL-source-header
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: OpenRTL-Lab/openeth
Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
File: docs/VERIFICATION.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# Verification coverage

[简体中文](VERIFICATION.zh-CN.md)

OpenETH verification has two hardware levels: **component RTL simulation** and **execution on the reference FPGA system**. OpenNIC is the memory-facing register/DMA controller; the MAC, frame buffers and RGMII connect it to the PHY. This document states what has actually been checked, not just which tests exist. Build commands and tool versions are in [Reproduction](REPRODUCE.md), and physical connections are in [Reference system](REFERENCE_SYSTEM.md).

<a id="simulation"></a>

## 1. RTL simulation

These tests execute circuit models; no FPGA or Ethernet cable is used. A negative control deliberately introduces an error and must be rejected, preventing a test from passing without checking the relevant behavior.

| Component | Executed coverage and expected evidence |
| --- | --- |
| OpenNIC registers and DMA | One CSR test and six DMA tests: descriptor/completion behavior, byte accuracy, memory backpressure, error handling and reset. Main DMA result: `OPENNIC_DMA_PASS`, 82 frames, 25016 cycles, 4826 read beats and 4826 write beats. Tests also use smaller queues to exercise boundary conditions. |
| MAC and CRC | 165 randomized frames, 201 CRC vectors and 1024 RGMII vectors, maximum frame size 2048 bytes; `MAC_CORE_PASS`. Two deliberately corrupted controls must be rejected. |
| MAC independent clocks | Product wrapper driven with independent TX/RX clocks; `MAC_PRODUCT_CLOCK_PASS`. This is not board-level timing verification. |
| Frame buffers, CDC and stream width conversion | Four configurations with three seeds each: 12 cases, four rejected corruption controls, byte-primitive checks, exported-wrapper lint and six ideal-interface performance rows. Small parameterized fixtures are not a full-size board stress test. |
| RGMII FPGA I/O | Vivado 2025.2 UNISIM functional simulation: 1025 TX pairs, 1025 RX pairs and three reset cases at a 2 ns TX clock phase; `RGMII_1G_PASS`. `WRONG_BYTE`, `WRONG_CTL` and `UNSHIFTED_CLOCK` must each report `RGMII_1G_FAIL`, without PASS. |

The native NIC and standalone DMA are also generated as complete, separately compiled SV units, containing eight and three module definitions respectively. Generation or lint alone is not a simulation pass. Do not compile both units together: they contain duplicate module names.

The standalone flow uses Mill 1.1.7, Scala 2.13.12, Chisel 6.5.0, chiseltest 6.0.0, ScalaTest 3.2.19 and firtool 1.75.0. The exercised Verilator version is 5.050. Exact Java/C++ dependencies, commands, fresh output directories and markers are given in [RTL test instructions](REPRODUCE.md#chisel-and-rtl-tests).

RGMII digital generation is not the FPGA I/O test. The latter requires legally installed vendor primitives and the [dedicated runner](../tools/test_rgmii_io.py). That runner checks the markers as well as process results: an xsim negative-control process can exit zero despite printing a failure. Functional UNISIM results do not validate PCB delays, analog signal quality or full process/voltage/temperature timing.

## 2. Host-only software checks

The [host smoke](../tools/smoke.py) executes 1573632 copy cases with 4098 guard-page lengths, 524544 RX merge cases with 67617 protected cases, corruption controls and the PHY policy self-test without hardware access. The [TCP program test](../tools/test_tcp_integrity.py) transfers 1 MiB each direction over localhost and confirms rejection of one altered byte. Source-header checking and [measurement recomputation](../tools/check_performance.py) are additional host checks, not circuit simulation or fresh board measurements. Commands and trusted-network precautions are in [Reproduction](REPRODUCE.md).

<a id="board"></a>

## 3. Actual FPGA board validation

The recorded platform is a VU13P FPGA reference system with a 50 MHz Rocket CPU/NIC domain and an RTL8211E-VL 1G full-duplex PHY. The reference system is not distributed as a complete board image; see [hardware and scope](REFERENCE_SYSTEM.md). FPGA RX/TX below are relative to the FPGA.

### Bare-metal validation and the supplied program

The reference system's **wire-level bare-metal test** checked 64 frames through the physical Ethernet link, including payload/padding boundaries, an 8192-byte DDR memory pattern, PHY identity/link, interrupts, buffer guards and DMA status. Its recorded success marker is `OPENETH_BOARD_PASS code=0000000000000000`. This establishes the tested packet/DMA path; it is not a Linux test, throughput benchmark or exhaustive Ethernet conformance test.

The public [bare-metal program](../nic/software/baremetal) is a different, **native-stream loopback** test. It sends 256 payloads through OpenNIC and a direct stream loopback, checking bytes, guards and interrupts. It does not traverse the MAC, PHY or cable. Its marker is `OPENNIC_SOC_PASS frames=256 bytecheck=1 guards=1 irq=1`; some 1–2048-byte payloads are not legal Ethernet wire frames. Its ELF was built from the supplied sources; that build does not prove execution of this exact program on the reference board. See [Bare-metal build and requirements](REPRODUCE.md#bare-metal-program).

The reference wire-level harness and board loader are not bundled. Do not present the public 256-payload program as the source of the 64-wire-frame result. Another platform needs its own address map, interrupt wiring and physical-link harness.

### Linux validation

The driver runs on the FPGA CPU, while the peer runs the client/test programs. The measured configuration enables `wide_copy`, `fix_rx_align`, `merge_copy` and `merge_rx_copy`; [Driver selection](REPRODUCE.md#linux-driver-build-and-selection) defines them and the matched-kernel requirement.

| Check | Recorded result and boundary |
| --- | --- |
| PHY and connectivity | RTL8211E-VL identity, 1000/full link, small ping and full-MTU ping passed. Ping alone does not prove arbitrary-payload integrity. |
| Driver lifecycle | Interface down/up, module unload/reload, parameter readback and ping after transitions passed. Perform only with a local console and no active network workload. |
| Bidirectional payload integrity | Each direction transferred 268435456 bytes (256 MiB), with zero mismatches and matching received/expected SHA-256. Uses the supplied TCP protocol, not iperf's generated traffic. |
| Integrity negative control | One byte changed in 1048576 bytes (1 MiB); exactly one target mismatch detected and target server exit 10, as required. |
| Throughput | All 13 iperf cases produced usable receiver results. UDP loss remains; successful collection is not zero-loss acceptance. |
| Post-load inspection | No DMA error or kernel-failure signature observed; MTU connectivity checked. Socket overflows and no-descriptor drops are retained, not described as an all-zero error run. |

The public [Linux utilities and peer instructions](REPRODUCE.md#linux-programs) describe how to build and run the same integrity protocol on a compatible integrated system. The exact throughput settings, order, receiver statistics and counters are in [Performance](PERFORMANCE.md).

## 4. What a pass does not establish

The recorded byte-integrity tests passed within their tested lengths and configuration. They do not prove unlimited-duration stability, all error modes, 1-Gbit/s Linux throughput or lossless UDP. Component simulation, software compilation, board functionality, throughput and timing signoff are separate outcomes.

Reference routing reports setup +0.189 ns, hold +0.010 ns and pulse width +0.039 ns, TNS/THS zero, 467239 required nets routed and zero route errors. External delay constraints are absent for 14 inputs and 22 outputs. Non-dedicated JTAG-clock conditions, board/package delays across process/voltage/temperature and whole-design CDC are not signed off. These results do not supply portable board constraints or production qualification.

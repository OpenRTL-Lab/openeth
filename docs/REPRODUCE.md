<!-- OpenRTL-source-header
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: OpenRTL-Lab/openeth
Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
File: docs/REPRODUCE.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# Reproduction

[简体中文](REPRODUCE.zh-CN.md)

For the test coverage and pass boundaries, see [Verification](VERIFICATION.md).
For hardware models and connections, see [Reference system](REFERENCE_SYSTEM.md).
The exact Linux throughput procedure is in [Performance](PERFORMANCE.md).

This guide covers reusable components and software, not reconstruction of the
reference FPGA system. Board schematics, PCB data, pin assignments, board timing
constraints, vendor-IP configurations and complete system images are not supplied.
Run from the repository root unless a subshell changes directory. Python 3.10+
and GCC-compatible C/C++ are needed for source and smoke checks; the tools do not
install dependencies.

```sh
python3 tools/source_headers.py check
python3 tools/test_source_headers.py
python3 tools/smoke.py
python3 tools/test_tcp_integrity.py
python3 tools/check_performance.py
```

Expected markers include `PUBLIC_SOURCE_CHECK_PASS`, `COPY_PASS`,
`RX_MERGE_PASS`, `AUTO_PHY_POLICY_TEST_PASS hardware_access=0` and
`PERFORMANCE_RECOMPUTED`. The C tests check exact bytes, guard pages, all tested
alignments/lengths and corruption negatives. Host tests do not establish RV64
performance or replace FPGA tests. The TCP host test compiles the C server and
checks 1 MiB in each direction plus one-byte corruption rejection. It connects
to localhost, but the server listens on all host interfaces; use a trusted or
network-isolated host.

## Chisel and RTL tests

The module builds pin Mill 1.1.7, Scala 2.13.12 and Chisel 6.5.0;
core tests use chiseltest 6.0.0 and ScalaTest 3.2.19. Supply JDK 21, Mill,
firtool 1.75.0, Verilator, g++ and zlib development headers/libraries.
Put `mill`, `verilator`, `java` and `g++` on PATH. Set
`CHISEL_FIRTOOL_PATH` to the directory containing the exact `firtool` executable.
The MAC runner accepts `MILL` as a command string and `ZLIB_PREFIX` as an optional
zlib installation prefix containing `include/` and `lib/`; omit `ZLIB_PREFIX`
when using system zlib. Maven access is required on the first build. A prefilled
dependency cache can avoid downloads but is not itself an independent cold build.
The tested Linux x86-64 tool combination is Mill 1.1.7 using Zulu JDK
21.0.10, command-line Temurin JDK 21.0.11, Verilator 5.050, GNU g++ 16.2.0
and zlib 1.2.13, with the pinned Scala/Chisel/firtool versions above.
Build MAC and RGMII in their separate module directories: they share Scala
package/class names and must not be combined into one Scala project.

```sh
(cd nic && mill -i core.test)
(cd nic && mill -i core.runMain opennic.EmitOpenNic build/generated-nic)
(cd nic && mill -i core.runMain opennic.EmitOpenNicDma build/generated-dma)
(cd mac && python3 tools/verify.py --out build/core-test --jobs 4)
(cd buffer && python3 tools/verify.py --out build/regression --jobs 4)
(cd rgmii && mill -i mac.runMain openeth.mac.EmitRgmiiDigital build/generated)
python3 tools/source_headers.py stamp-generated nic/build/generated-nic
python3 tools/source_headers.py stamp-generated nic/build/generated-dma
python3 tools/source_headers.py stamp-generated rgmii/build/generated
```

The NIC `rtl/` files are separate compilation units: `OpenNic.sv` includes its
own DMA and queues, whereas `OpenNicDma.sv` is for standalone DMA use. Do not
compile both together. Generation commands and source hashes are recorded in
`rtl/nic/GENERATED.json`.

Output directories must be new. A missing compiler, Java dependency or FPGA
primitive is a failed/unexecuted stage, not a pass. The generated-header step
preserves tool comments and is idempotent; it changes comments only. It does
not update the supplied `rtl/` files or guarantee byte-identical regeneration.
Frame-buffer tests use parameterized smaller fixtures as well as
their documented configurations; they are not a 2048-byte board stress test.

The NIC generator emits the complete hierarchy, including descriptor/completion
queues, DMA and inferred memories. Use that complete output as one compilation
unit; do not also compile another generated copy of the same modules. The DMA
generator emits a standalone DMA unit. Default NIC generation uses 32 queue
entries and 2048-byte frames; unit tests also use smaller queues to exercise
backpressure. MAC verification requires `MAC_CORE_PASS` and
`MAC_PRODUCT_CLOCK_PASS`, and checks that both corruption controls are rejected.
Buffer verification requires all 12 seeded cases, four corruption controls and
the exported-wrapper lint checks to succeed; inspect its `completion.json`.

For source-level integration, choose one implementation of each module; do not
glob every `.sv` file in the repository, because `rtl/` and component source
directories contain alternate copies of the same modules:

| Compilation target | Required component sources |
| --- | --- |
| Native NIC | The complete generated `OpenNic.sv` alone; its external interfaces are CSR, memory and packet streams. |
| MAC with 64-bit frame streams | `mac/rtl/OpenEthMac.sv`, generated `MacGmiiCore.sv`, and all four files in `buffer/rtl`. |
| RGMII I/O | Generated `RgmiiDigital.sv`, both files in `rgmii/rtl`, and the three `third_party/verilog-ethernet` wrappers; Xilinx mode also needs vendor primitives. |

`nic/integration/OpenEthBoardLink.sv` connects the MAC/frame-buffer and RGMII
groups above and exposes 64-bit packet streams. Despite its name, it supplies
no board pin map, CPU, memory controller or clock generator. OpenNIC's memory
and CSR interfaces still need system integration. The supplied `rtl/` copies
describe the documented fixed configuration; regenerating component outputs
does not create a complete SoC.

`nic/platform` contains optional integration sources/tests requiring the
external Chipyard base `69eba860a352343e4ac6b6df0f3638a79a86ec78`, its pinned
submodules, CDE and Rocket Diplomacy/TileLink. They are not compiled by the
standalone `nic/core` Mill build. The included sbt fragment is an overlay,
not a full Chipyard distribution or an automatic installer. Acquire these
dependencies separately. The adapter obtains its legal address ranges from
the integrating TileLink bus; no board address-decoder snapshot is provided.
These optional adapters are not required to generate or test the native core.

The RGMII digital generator does not simulate FPGA I/O primitives. The separate
`rgmii/sim/rgmii_1g_test.sv` test requires the licensed Vivado 2025.2 UNISIM
library, the three included `third_party/verilog-ethernet` wrappers,
`rtl/rgmii/RgmiiDigital.sv` and the two handwritten `rgmii/rtl` modules.
Its success marker is `RGMII_1G_PASS tx_pairs=1025 rx_pairs=1025 pair_reset_cases=3`;
`WRONG_BYTE`, `WRONG_CTL` and `UNSHIFTED_CLOCK` must each produce
`RGMII_1G_FAIL` rather than PASS. These are functional simulations, not pad-delay,
PCB or PVT timing verification. Obtain vendor libraries under their own terms;
they are not included or substituted with behavioral models.

After installing Vivado 2025.2, run the supplied functional-test driver:

```sh
python3 tools/test_rgmii_io.py --vivado /path/to/Vivado/2025.2 \
  --out build/rgmii-io
```

Replace the installation path. This runs `xvlog`, `xelab` and `xsim`, retains
each log and writes `completion.json`; its status must be zero, with the positive
marker and all three rejected controls. It does not synthesize or program an FPGA.

## Software programs and native-stream loopback

<a id="linux-programs"></a>

### Linux programs

Build the Linux utilities on the host (no hardware is accessed by compilation):

```sh
mkdir -p build/apps
cc -O2 -Wall -Wextra software/linux/auto_phy.c -o build/apps/auto_phy
cc -O2 -Wall -Wextra software/linux/tcp_integrity.c -o build/apps/tcp_integrity
build/apps/auto_phy --self-test
```

For RV64 target binaries use the same commands with your target C compiler,
for example GCC 13.2.0 `riscv64-unknown-linux-gnu-gcc -static`, in place of `cc`.
The compiler's target ABI and C library must match the target Linux system.
Do not run the host executable on RV64. Compilation is not a target execution test.

`tcp_integrity` is a single-connection TCP server. On a correctly integrated
target, run `./tcp_integrity 5002`; on the peer computer run:

```sh
python3 tools/tcp_integrity_peer.py --host TARGET_IP --port 5002 \
  --bytes 268435456 --out build/integrity.json
```

Replace `TARGET_IP` with the reachable target address. Allow that TCP port only
on a trusted test network. The server receives and checks a deterministic pattern,
then sends a different pattern back; both directions must have zero mismatches.
The client exits zero and records matching received/expected SHA-256 values.
Restart the single-connection server for each run. A negative control uses
`--bytes 1048576 --corrupt` with a different output file: the client must report
exactly one target mismatch, and the server must exit **10**, not zero.
That negative result is expected rejection, not a successful normal transfer.

<a id="bare-metal-program"></a>

### Bare-metal program

The bare-metal program tests a **direct native-stream loopback**, without a MAC,
PHY or cable. Its example platform requires RV64 machine-mode execution,
coherent DMA memory starting at `0x80000000`, NIC CSRs at `0x10016000`, a
SiFive-compatible UART at `0x10020000`, and a PLIC at `0x0c000000` with NIC
interrupt IDs 3/4 in machine context 0. The linker places code, buffers and stack
in that example memory. These are test integration requirements, not a supplied
SoC implementation. Adapt the addresses and interrupt routing for another system.

```sh
make -C nic/software/baremetal CROSS_COMPILE=riscv64-unknown-elf-
```

Use a GCC 13.2.0 RV64 toolchain with `rv64imac_zicsr`/`lp64` support. The output
is `nic/software/baremetal/build/loopback.elf`, not a board boot image. In a
platform satisfying the stated requirements, success is
`OPENNIC_SOC_PASS frames=256 bytecheck=1 guards=1 irq=1` and a `tohost` value of
1; failure prints `OPENNIC_SOC_FAIL` and encodes the nonzero error in `tohost`.
The 1–2048-byte native test payloads are not all legal Ethernet wire frames.

## Linux driver build and selection

Use an externally prepared **RV64 Linux 6.6** kernel build with its matching
configuration, generated headers and symbol versions; replace KERNEL_BUILD
with that build directory, not a kernel tarball or this repository.

```sh
make -C "$KERNEL_BUILD" M="$PWD/software/linux/v11" ARCH=riscv \
  CROSS_COMPILE=riscv64-linux-gnu- modules
```

This builds a module; it does not install it. The driver copies packet bytes
between Linux socket buffers (`skb`) and coherent DMA buffers in SoC memory.
The reference configuration enables these four software-copy parameters:

| Parameter | Effect when enabled |
| --- | --- |
| `wide_copy` | Use aligned 64-bit accesses on the DMA-buffer side of the copy, with byte-safe handling on the packet-buffer side. |
| `fix_rx_align` | Do not reserve packet-buffer alignment space twice: `napi_alloc_skb` already reserves it. |
| `merge_copy` | On the wide-copy TX path for linear packet buffers, combine aligned cached source words into DMA-buffer words. |
| `merge_rx_copy` | On the wide-copy RX path, combine DMA-buffer words into aligned cached packet-buffer stores. |

The two merge parameters require `wide_copy` for their paths to be used; they
do not enable DMA zero-copy or hardware packet offload. On a correctly integrated
OpenNIC platform, load the compatible module with all four parameters enabled:

```sh
insmod opennic.ko wide_copy=1 fix_rx_align=1 merge_copy=1 merge_rx_copy=1
```

The four flags default off in source; omitting them does not reproduce the
published optimized configuration. Do not blindly unload a live network driver.
The repository supplies driver source, not a complete root filesystem or kernel
image. Ensure that the target system loads the module built for its kernel.

The reference application uses MTU 1500 and 1G full duplex, not 10/100 fallback.
Both `auto_phy --inspect` and `auto_phy --configure` write management registers
(including pin direction and PHY page selection) and are **not** generic smoke
tests; inspect the source, pinout, PHY identity, supply and timing for your board
before any access. Only `auto_phy --self-test` is a no-hardware policy test.

<!-- OpenRTL-source-header
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: OpenRTL-Lab/openeth
Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
File: docs/SOC_INTEGRATION.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# Chipyard SoC integration

[简体中文](SOC_INTEGRATION.zh-CN.md)

## What is generated

The digital system combines a Rocket CPU, coherent memory interconnect, Ethernet
DMA/register controller, and PHY management. Its `ChipTop` exposes an AXI4 memory
interface, debug, UART, SPI, management signals and two 64-bit packet streams.
The supplied connection generator attaches those streams to the public MAC,
frame buffers, clock-domain crossing and RGMII modules to form `OpenEthSoC`.

This is a **digital SoC generation recipe**, not a complete FPGA board project.
DDR4 controllers, clock generators, pad/pin assignments and board constraints
remain external. The [reference-system description](REFERENCE_SYSTEM.md) explains
the physical connections; it does not supply those board-specific designs.

## Public source baseline

Use [ucb-bar/chipyard at commit
69eba860a352343e4ac6b6df0f3638a79a86ec78](https://github.com/ucb-bar/chipyard/tree/69eba860a352343e4ac6b6df0f3638a79a86ec78),
not a moving branch or a newer tag. Its Git submodule entries pin the dependency
revisions. `tools/chipyard.py prepare` fetches that commit and the root submodules
needed by its Scala build, then copies this repository's allowlisted overlay:

| Source in OpenETH | Destination relative to Chipyard |
| --- | --- |
| `nic/core/src` | `opennic/core/src` |
| `nic/platform/src` | `opennic/platform/src` |
| `integration/chipyard/soc` | `opennic/soc/src/main/scala/opennic/soc` |
| `integration/chipyard/eth` | `generators/chipyard/src/main/scala/eth` |
| `integration/chipyard/opennic-platform.sbt` | `opennic-platform.sbt` |
| `integration/chipyard/opennic-soc.sbt` | `opennic-soc.sbt` |

The script does not import a private checkout or precompiled SoC classes.
Chipyard and its submodules retain their own licenses; see [Licensing](../THIRD_PARTY.md).
The boot ROM comes from the pinned public TestChipIP source, including its
source and rebuild rules. It is a small CPU boot trampoline, not a Linux image
or a board-specific boot package.

## Assembly and interfaces

`integration/chipyard/soc/OpenEthSystem.scala` supplies the system configuration
and the bindings. `OpenEthReferenceConfig` selects one RV64 Rocket core with
floating-point support, 4 GiB of external addressable memory, a 128-bit system
bus and a 512-bit memory-bus/AXI interface. All SoC bus clocks are configured for
50 MHz. The MAC/RGMII side requires separate 125 MHz clocks and the PHY RX clock.
The packet controller has 32 queue entries and a 2048-byte frame limit.

| Connection | Implementation |
| --- | --- |
| NIC registers | `OpenNicTL.csrNode` on the system bus at `0x10016000`; width conversion, fragmentation and buffering; no atomic-emulation adapter on destructive queue registers |
| NIC DMA | `OpenNicTL.dmaNode` is a system-bus master through a 64-bit width adapter and buffer, using the coherent memory hierarchy |
| Interrupts | `intNode` to `ibus.fromSync`; generated PLIC sources 3 and 4 for TX and RX |
| Management registers | `0x6000`, compatible `openhw,openeth-management-v1`; MDIO and PHY reset, with the management packet FIFO data plane disabled |
| External memory | AXI4, 512-bit data, starting at `0x80000000`; the memory controller is supplied by the integrator |
| Software console | SiFive-compatible UART at `0x10020000` |
| Optional SPI peripheral | SPI at `0x10031000`; a polling SPI memory-card resource description, not a card simulation model |

`WithOpenNicPorts` exposes the packet streams through `ChipTop`.
`OpenEthExportConfig` preserves its ports while generating the surrounding
`TestHarness`. `WithOpenNicLoopback` connects TX directly to RX **in that
simulation harness only**. `WithUnusedSPI` fixes unused simulated SPI inputs;
it neither loads software nor models a memory card. `OpenEthSimConfig` selects
DMI debug for users building a software-executing simulator; generation alone
does not run the [bare-metal test](REPRODUCE.md#bare-metal-program).

For physical Ethernet, use `OpenEthSoC`, not the harness loopback. The connection
generator verifies the 102-port `ChipTop` interface, wires every native-stream
field and instantiates `nic/integration/OpenEthBoardLink.sv`. It forwards the
external memory, management, UART, SPI, JTAG, boot and reset interfaces without
embedding a board pin map. Supply `clock_uncore` at 50 MHz, `gtx_clk` and
`gtx_clk90` at 125 MHz with a 90-degree offset, and `phy_rxc` from the PHY.
`reset_io` is active high; `gtx_reset` is the active-high Ethernet clock-domain
reset. Correct reset release, PHY delays and source-synchronous constraints
are the integrator's responsibility.

The management byte-stream interface is unused and tied inactive. Management
MAC event inputs are tied low; the speed field is fixed to 1-Gbit/s mode. Do not
interpret those event registers as measured MAC statistics. Packet integrity
and DMA tests use the separate controller interfaces.

## Tools and commands

The Scala build uses sbt **1.8.2** from the pinned Chipyard `project/build.properties`,
Scala **2.13.12**, Chisel **6.5.0**, chiseltest **6.0.0**, ScalaTest **3.2.19**
for the NIC platform tests, and **firtool 1.75.0**. The exercised JDK is OpenJDK
**20.0.2**. Select it on PATH for the sbt launcher;
JDK 21 is not compatible with this sbt launcher's Scala parser. Component Mill
builds have separate requirements in [Reproduction](REPRODUCE.md).
Install Python 3.10+, Git, Make, **dtc 1.6.1**, Verilator **5.050** and a C++ compiler. Provide the tools
on PATH and permit GitHub/Maven access, or supply a complete public-artifact
cache. Cached dependency JARs are not a substitute for compiling the sources.

Run from the OpenETH repository root, with new output directories:

```sh
export CHISEL_FIRTOOL_PATH="$(dirname "$(command -v firtool)")"
export JAVA_TOOL_OPTIONS="-Xmx22G -Xss8M -XX:ActiveProcessorCount=8"
python3 tools/chipyard.py prepare --work build/chipyard-soc
python3 tools/chipyard.py generate --work build/chipyard-soc
python3 tools/test_chipyard.py
python3 tools/test_soc_link.py
python3 tools/soc_link.py --soc build/chipyard-soc/generated/rtl \
  --out build/ethernet-soc --lint
```

Use `--sbt` and `--firtool` to select explicit executables if necessary. The
`--cross-compile` option selects the RISC-V compiler prefix for the public
boot-ROM Makefile; its default is `riscv64-unknown-elf-`. The pinned upstream
checkout already contains the boot ROM. A rebuild needs the corresponding
RV64 bare-metal GCC and objcopy.

Preparation and generation retain separate JSON receipts and logs. A nonzero
status is a failure even if an earlier step printed a success marker. Existing
receipts are not overwritten. If sources change after preparation, prepare a
new workspace; do not edit a receipt to bypass the input guard. Large builds
can require several GiB of disk and tens of GiB of RAM.

Successful generation requires all platform tests, `OPENETH_SOC_CONFIG_PASS`,
and `OPENETH_SOC_GENERATION_PASS`. The script checks required CPU/cache/NIC/MDIO
modules, the packet-stream ports and the generated address/interrupt contract;
it rejects unexpected accelerator/controller hierarchies. Output includes:

- `generated/rtl/ChipTop.sv` and its complete generated hierarchy;
- `generated/OpenEth.fir`, annotations and `OpenEth.dts` describing the digital system;
- `generate.json` with source and output hashes;
- `OpenEthSoC.sv`, `sources.f` and `link.json` from the connection step.

Select `OpenEthSoC` as the physical integration top. Do not add the standalone
`rtl/nic/OpenNic.sv` or another NIC copy to its source list: the generated SoC
already contains that hierarchy. `sources.f` includes simulation-only generated
modules as well; choose the intended top and supply the upstream black-box
resources if compiling `TestHarness`. Xilinx RGMII mode also needs the licensed
vendor primitives, as explained in [Verification](VERIFICATION.md).

`--lint` checks the complete `OpenEthSoC` hierarchy with Verilator and
`TARGET="GENERIC"` behavioral DDR I/O models; it does not check Xilinx pad
timing. The emitted wrapper defaults to `TARGET="XILINX"` for physical
integration. A nonzero lint exit is a failure; warnings remain in `lint.log`
and are not a clean-lint or timing-signoff claim. Omit `--lint` only if generating
sources without running that check. The two Python test commands exercise source
guards and wrapper generation, not circuit behavior.

Generated logs, FIRRTL source locators and receipts can contain the build
machine's absolute paths. They are local build products, not publication files;
do not upload them without a separate privacy review. Generated structure checks
are not formal equivalence, synthesis timing, a Linux boot test or new FPGA
performance measurements. See [Verification](VERIFICATION.md) for those separate
validation boundaries and [Performance](PERFORMANCE.md) for recorded board results.

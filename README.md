<!-- OpenRTL-source-header
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: OpenRTL-Lab/openeth
Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
File: README.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# OpenETH — Gigabit Ethernet RTL and Software

[简体中文](README.zh-CN.md)

Open hardware, from algorithms to RTL.

OpenETH provides reusable components for connecting a system-on-chip (SoC) to a Gigabit Ethernet PHY: a network controller and DMA, frame buffering and clock-domain crossing, MAC and CRC, RGMII I/O, and PHY management. The repository includes their Chisel and SystemVerilog sources, a Linux driver, bare-metal and Linux test programs, and simulation tests.

[Release v1.1.0](https://github.com/OpenRTL-Lab/openeth/releases/tag/v1.1.0) · [Interfaces](docs/INTERFACES.md) · [Build and test](docs/REPRODUCE.md)

## Repository contents

- **Network controller and DMA (OpenNIC):** [Chisel](nic/core/src/main/scala/opennic) and [generated RTL](rtl/nic).
- **Frame buffering and clock-domain crossing:** [sources and tests](buffer).
- **MAC and CRC:** [sources and tests](mac).
- **RGMII and FPGA I/O:** [sources and tests](rgmii).
- **PHY management:** [MDIO integration](integration/chipyard/eth) and [PHY utility](software/linux/auto_phy.c).
- **Datapath integration:** [RTL connections between components](nic/integration).
- **Digital SoC integration:** a pinned public Chipyard baseline, system configuration, bus/interrupt bindings and complete SV generation. See [Chipyard SoC integration](docs/SOC_INTEGRATION.md).
- **Software:** [bare-metal native-stream loopback](nic/software/baremetal), [Linux driver](software/linux/v11), [PHY utility](software/linux/auto_phy.c), and [TCP integrity tests](software/linux/tcp_integrity.c) with a [peer program](tools/tcp_integrity_peer.py).
- **Integration contracts:** packet streams, memory interfaces, register layout and reset behavior. See [Interfaces](docs/INTERFACES.md) for details.

## Verification

### RTL simulation

Simulation covers NIC registers/DMA, MAC framing and CRC, frame-buffer clock crossing, and RGMII functional behavior, including backpressure, reset and intentional-error controls. Host-only C tests are listed separately; they are not RTL simulation. See [Simulation verification](docs/VERIFICATION.md#simulation) for coverage and [Build and test](docs/REPRODUCE.md#chisel-and-rtl-tests) for commands and dependencies.

### FPGA board validation

The VU13P FPGA reference system has been exercised with bare-metal packet/DMA tests and Linux driver, integrity and throughput tests. Functional correctness and zero-loss performance are separate conclusions. See [Board validation](docs/VERIFICATION.md#board) for results and limits, and [Reference hardware and connections](docs/REFERENCE_SYSTEM.md) for the setup.

## Programs

**Bare-metal:** the supplied program checks the network controller through direct packet-stream loopback, without Linux or an external PHY path. See [Bare-metal program](docs/REPRODUCE.md#bare-metal-program) for its platform requirements and pass criteria.

**Linux:** the repository provides the platform driver, PHY configuration utility and bidirectional TCP byte-integrity test. See [Linux programs](docs/REPRODUCE.md#linux-programs) and [Driver build and selection](docs/REPRODUCE.md#linux-driver-build-and-selection) for details.

## Performance

On the 50 MHz Rocket reference system, measured Linux TCP throughput is **27.089 Mbit/s RX**, **25.235 Mbit/s TX**, and **24.748 Mbit/s simultaneous RX+TX**. RX/TX are relative to the FPGA; these are application measurements, not the PHY's 1-Gbit/s line rate, and UDP loss remains. See [Performance test method and results](docs/PERFORMANCE.md) for wiring, prerequisites, all 13 test cases, exact commands and statistics.

## Reproduction and delivery scope

Component and digital SoC SV generation, simulation and software source builds are documented. The reference board system is described, but complete board projects, schematics, pin constraints, vendor-IP configurations and board boot images are not supplied. See [Build and test](docs/REPRODUCE.md), [SoC integration](docs/SOC_INTEGRATION.md) and [Reference system scope](docs/REFERENCE_SYSTEM.md#scope) for details.

## License and integration

Original code uses the [MIT License](LICENSE), permitting commercial use without purchasing services; preserve the [third-party notices](THIRD_PARTY.md). For optional datapath customization or ASIC/FPGA integration, visit [OpenRTL Lab](https://openrtl.top/?lang=en).

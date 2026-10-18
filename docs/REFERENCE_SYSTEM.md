<!-- OpenRTL-source-header
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: OpenRTL-Lab/openeth
Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
File: docs/REFERENCE_SYSTEM.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# Reference hardware and connections

[简体中文](REFERENCE_SYSTEM.zh-CN.md)

This document describes the FPGA system used for the recorded tests. OpenETH supplies Ethernet components; OpenNIC is the DMA/register controller between SoC memory and the MAC. TX means FPGA-to-peer traffic and RX means peer-to-FPGA traffic. Component signals are specified in [Interfaces](INTERFACES.md); the complete board design is not part of this repository.

## Hardware and operating conditions

| Item | Reference configuration |
| --- | --- |
| FPGA device | Xilinx Virtex UltraScale+ VU13P |
| CPU and memory | Single Rocket RV64 CPU, 50 MHz CPU/NIC domain, external DDR4 memory |
| Ethernet PHY | Realtek RTL8211E-VL; PHY ID `001c:c915`; RGMII data path and Clause 22 MDIO management |
| Ethernet link | 1000 Mbit/s full duplex; Linux MTU 1500 |
| Ethernet clocks | 125 MHz MAC/RGMII domain; PHY-supplied RX clock; TX clock shifted by 90° (2 ns) in the reference integration |
| Board clock device | Si5338; its board-specific programming image is not distributed |
| FPGA configuration connection | Digilent USB-JTAG interface, USB ID `0403:6010`, used by Vivado |
| CPU debug connection | Separate FTDI FT232H USB-JTAG adapter, USB ID `0403:6014`, used by OpenOCD; 4 MHz JTAG clock |
| Serial console | CH340/CH341 USB-UART, USB ID `1a86:7523`; 115200 baud, 8 data bits, no parity, 1 stop bit, no flow control |
| Target software | RV64 Linux `6.6.0-opennic-r1`; OpenNIC platform driver and supplied PHY/integrity utilities |
| Throughput tools | iperf 3.19.1 on both target and Linux peer |

The table identifies the FPGA device and connected peripherals, not a board model or bill of materials. The board model/revision, full FPGA ordering suffix and DDR4 chip part number are not disclosed. Do not substitute a guessed part or infer that another board has the same clock/pin wiring.

## Data and management connections

```text
Linux Ethernet peer                         VU13P FPGA reference system
physical Ethernet NIC ── Ethernet test LAN ── RJ45 / RTL8211E-VL PHY
                                                    │ RGMII data + clocks
                                                    ▼
                                             MAC ↔ frame buffers/CDC
                                                    ↕ packet streams
                                             OpenNIC DMA ↔ DDR4
                                                    ↕
                                             Rocket RV64 / Linux

Management computer ── USB / Digilent JTAG ── FPGA configuration port
                    ├─ USB / FT232H JTAG ──── SoC CPU debug port
                    └─ USB / CH340/CH341 ──── SoC UART console
```

CDC means clock-domain crossing. MDIO is a separate FPGA-to-PHY management connection; it does not carry packet payload. The RX clock travels from the PHY to the FPGA. TX clock/data/control travel from the FPGA to the PHY; correct source-synchronous timing and the PHY-specific RX delay settings are required.

The Ethernet peer is the machine running the iperf client, not the serial-console host or a USB network adapter. USB debug/console connections can be forwarded to a management VM; they are not the measured packet path. The recorded network addresses are **FPGA `192.0.2.1/24`** and **peer `192.0.2.2/24`** on the same Layer-2 test network, with no routed hop required. These documentation addresses are for a local test network, not Internet access.

Connect the board's PHY RJ45 port and the peer's physical Ethernet port to the same suitable Gigabit Ethernet segment. The recorded setup does not establish an exclusively isolated, direct cable with no intervening switch or unrelated frames; do not describe the measurements as such. Record the actual switch/bridge path and competing traffic when repeating tests. On the reference Linux peer, the test address is assigned to a bridge containing the physical NIC, rather than a second network path.

The two JTAG connections serve different targets: programming the FPGA is not loading a CPU ELF, and CPU debug does not configure the FPGA fabric. Match adapters to those functions. This is a connection description, not a connector-pin wiring guide; use the board vendor's correct pinout, voltage levels and power instructions before attaching external signals. No private USB serial numbers or management-network addresses are needed.

## Software roles and test order

1. Integrate and configure the component in a compatible FPGA/SoC platform using your board's own materials. The public repository does not perform this whole-board step.
2. Use the serial console for target logs and the CPU debug connection where required for bare-metal loading. The supplied native-loopback ELF has the distinct requirements in [Bare-metal program](REPRODUCE.md#bare-metal-program).
3. Boot compatible Linux, load the matched driver and configure the actual RTL8211E-VL PHY safely. See [Linux programs](REPRODUCE.md#linux-programs) and [Driver selection](REPRODUCE.md#linux-driver-build-and-selection).
4. Check link, ping, lifecycle and payload integrity before measuring throughput. See [Verification](VERIFICATION.md#board) and [Performance method](PERFORMANCE.md).

<a id="scope"></a>

## Publication boundary

FPGA/peripheral device identities, functional connections, clocks, software requirements, test methods and measurements are described; the board model and revision are not disclosed. The [digital SoC configuration and generation recipe](SOC_INTEGRATION.md), including its generated device-tree description, are supplied. Board schematics/PCB originals, connector pin tables, complete board implementation project, pin constraints, vendor-IP settings, Si5338 image, board Linux device-tree patches and boot images are not supplied. Digital SoC/component SV generation and software builds are reproducible within their stated scope; possession of this repository and the board alone is not a complete board-rebuild recipe. FPGA I/O simulation is functional verification, not board-trace or full process/voltage/temperature timing signoff.

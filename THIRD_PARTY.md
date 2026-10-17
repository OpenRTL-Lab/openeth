<!-- OpenRTL-source-header
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: OpenRTL-Lab/openeth
Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
File: THIRD_PARTY.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# Licensing and provenance

[简体中文](THIRD_PARTY.zh-CN.md)

The root [MIT License](LICENSE) applies to original OpenETH code. Original
OpenNIC notices are additionally preserved in [licenses/OpenNIC-MIT.txt](licenses/OpenNIC-MIT.txt).
File headers identify the project and applicable license; they do not transfer
copyright. [SOURCE_MANIFEST.json](SOURCE_MANIFEST.json) records the indexed
implementation files' paths, licenses, body SHA-256 and delivery-byte SHA-256.
[PUBLIC_FILES.json](PUBLIC_FILES.json) covers the payload files, including
documentation and helper scripts; the coverage index and its license sidecar
are excluded from their own index to avoid a circular hash.

| Included dependency | Upstream / revision | Terms and treatment |
| --- | --- | --- |
| `third_party/verilog-ethernet/{ssio_ddr_in,iddr,oddr}.v` | [alexforencich/verilog-ethernet](https://github.com/alexforencich/verilog-ethernet), `77320a9471d19c7dd383914bc049e02d9f4f1ffb` | MIT; original full copyright/permission headers unchanged |
| `integration/chipyard/eth/{ETH,MdioController}.scala` | Chipyard management integration; external Chipyard base `69eba860a352343e4ac6b6df0f3638a79a86ec78` | Retain [BSD-3-Clause](licenses/Chipyard-BSD-3-Clause.txt) terms |

Chisel 6.5.0, CIRCT/firtool 1.75.0, Mill 1.1.7, Scala 2.13.12, chiseltest 6.0.0,
ScalaTest 3.2.19, Verilator, zlib, Chipyard/Rocket, Linux 6.6 and FPGA vendor
primitives are **external tools/dependencies**, not blanket-MIT project code.
Generated files retain CIRCT comments; original project implementation and
external tool licensing are distinct. Linux driver source is SPDX MIT and
retains its original `Dual MIT/GPL` module declaration; the Linux kernel and
its interfaces are not relicensed by this repository.

Chipyard/Rocket/Linux/OpenSBI source trees, proprietary DDR/MMCM IP and FPGA
vendor tools are not included. Obtain these dependencies under their respective
licenses. Optional commercial services do not change the permissions of MIT code.

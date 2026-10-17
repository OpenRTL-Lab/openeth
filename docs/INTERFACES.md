<!-- OpenRTL-source-header
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: OpenRTL-Lab/openeth
Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
File: docs/INTERFACES.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# Architecture and interfaces

[简体中文](INTERFACES.zh-CN.md)

OpenNIC is OpenETH's memory-facing controller: software submits buffer
descriptors through its control/status registers (CSRs), and its direct memory
access (DMA) engine transfers packet data. TX means FPGA-to-peer transmission;
RX means reception by the FPGA. The datapath connects OpenNIC → TX frame
buffer / clock-domain crossing (CDC) / 64-to-8 conversion → MAC → RGMII →
external PHY; RX follows the reverse path. DMA moves data between **SoC main
memory** and NIC frame storage, not between the peer computer's memory and the
FPGA. The Linux driver copies between packet buffers and its DMA buffers; it is
not a Linux zero-copy implementation. CPU, NIC and PHY clocks
must not be conflated: the measured CPU is 50 MHz; 1G GMII/RGMII uses 125 MHz.

Native NIC request/response and DMA streams use valid/ready decoupled
handshakes: a transfer occurs when both `valid` and `ready` are high. While
`valid` is high and `ready` is low, the sender must retain the transfer fields.
`FrameBeat` is the project's packet-stream record: 64-bit `data`, an 8-bit
byte-valid mask `keep`, end-of-frame flag `last`, and frame-error flag `error`.
Valid bytes occupy contiguous lanes starting at lane 0: non-final beats require
`keep=0xff`; final beats require a nonzero mask such as `0x01`, `0x03` or `0xff`,
not `0x05`. Descriptors carry a 64-bit memory address aligned to 8 bytes,
a 16-bit length and a 16-bit cookie; maximum frame length is 2048 bytes
in the released configuration. The cookie is a software-supplied request
identifier returned in the completion alongside length and status.
Memory requests are aligned 64-bit reads and masked writes with explicit
completion/error responses; the bridge uses separate read/write source IDs.

CSR offsets below are relative to the NIC base (reference platform 0x10016000),
not the separate PHY-management block (reference 0x6000). Consult the source
for strobe/illegal-access errors before porting. Reading a completion-pop
register removes that queue entry; reading its count register does not.

| Offset | Meaning |
| --- | --- |
| 0x00 / 0x04 | ID `0x4f4e4943` / ABI version `0x00010000` |
| 0x08 / 0x0c | Control/enable-flush / busy status |
| 0x10 / 0x14 | IRQ mask / pending status; bit 0 TX, bit 1 RX |
| 0x20 / 0x24 / 0x28 / 0x2c | TX address low/high, metadata, submit |
| 0x30 / 0x34 / 0x38 / 0x3c | RX address low/high, metadata, submit |
| 0x40 / 0x44 | Destructive TX completion pop / count |
| 0x48 / 0x4c | Destructive RX completion pop / count |
| 0x50 | RX head status: read **before** RX completion pop |
| 0x54 | Descriptor queue space |
| 0x58 / 0x5c | Drop / DMA-error counters |

Completion IRQs are level-sensitive pending-nonempty signals masked by the
software mask. This release has **no programmable hardware interrupt
coalescer**. DMA uses stop-and-wait memory transactions; do not assume burst
or multiple-outstanding throughput. The Linux driver owns coherent 2048-byte
buffers, copies payload to/from skbs, uses NAPI/GRO, and performs software
checksums rather than pretending to have checksum offload.

Frame CDC makes a slot readable in the destination clock domain only after the
whole frame and its metadata are stored. Reset discards buffered/in-flight
frames logically: resetting either side resets the FIFO ownership pointers;
RAM bytes are not cleared. Each side resumes through its local synchronized
reset-release chain, so stale RAM contents must never be treated as a new frame.
RX can discard bad/oversize/full-buffer frames according
to its policy. Backpressure support does not imply an external Ethernet PHY
can be stalled indefinitely. For CDC integration, read the
[constraint procedure](../buffer/constraints/openeth_frame_cdc.tcl) and validate
actual clock domains, reset structure, RAM mapping, setup/hold and recovery.
Do not apply unconstrained asynchronous exceptions to conceal paths.

RGMII TX requires `clk` and `clk90`: the latter supplies the intended 90-degree
TX clock phase shift (2 ns at 125 MHz). RX uses the PHY-supplied clock.
Reset can invalidate an internal frame without immediately changing an FPGA
output pin. If its clock stops, a double-data-rate output register can hold its
last value until another clock edge; frame invalidation is not an asynchronous
output-pin reset guarantee. The three included FPGA I/O wrappers require
appropriate FPGA primitives for TARGET=XILINX or a supported generic model.
MAC-to-PHY delay mode and board-trace/package delays across process, voltage and
temperature (PVT) are system responsibilities.

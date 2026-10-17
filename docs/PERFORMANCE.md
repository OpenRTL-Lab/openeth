<!-- OpenRTL-source-header
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: OpenRTL-Lab/openeth
Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
File: docs/PERFORMANCE.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# Linux Ethernet performance: method and results

[简体中文](PERFORMANCE.zh-CN.md)

This is an **end-to-end Linux application throughput test**, not an RTL-cycle benchmark or a measurement of the PHY's line rate. The OpenNIC driver runs on the Rocket CPU inside the FPGA; iperf's client runs on a separate Linux Ethernet peer. **FPGA RX** means peer → FPGA and **FPGA TX** means FPGA → peer. Hardware models, clocks and Ethernet/USB connections are in [Reference system](REFERENCE_SYSTEM.md); functional tests are described separately in [Verification](VERIFICATION.md).

## 1. Measured configuration

| Setting | Recorded value |
| --- | --- |
| Platform | VU13P FPGA reference system, single Rocket RV64; CPU/NIC 50 MHz, MAC/RGMII 125 MHz |
| Link | RTL8211E-VL, 1000 Mbit/s full duplex, MTU 1500 |
| Software | Linux `6.6.0-opennic-r1`; iperf 3.19.1 at both ends |
| Addresses | Target `192.0.2.1/24`, peer `192.0.2.2/24`, same Layer-2 test network |
| OpenNIC | Register interface version 1; 32 coherent 2048-byte DMA buffers per direction |
| Driver parameters | `wide_copy=Y`, `fix_rx_align=Y`, `merge_copy=Y`, `merge_rx_copy=Y` |
| Software IRQ deferral | `gro_flush_timeout=0`, `napi_defer_hard_irqs=0` |
| Linux receive budgets | `netdev_budget=300` packets, `netdev_budget_usecs=8000` microseconds |

The [four driver flags](REPRODUCE.md#linux-driver-build-and-selection) select alignment and word-copy paths; they do not enable zero-copy or hardware offload. The driver uses software checksums, NAPI receive polling and GRO receive aggregation. No hardware checksum/segmentation offload or programmable hardware interrupt coalescer is provided. The DMA engine waits for a memory response before issuing its next transaction. These CPU, copy, interrupt and memory-path costs are part of the measured throughput.

## 2. Preconditions and target setup

Use an already integrated, correctly booted system; this repository does not supply its complete FPGA project, device tree or boot images. Build the driver against the exact target kernel and load all four parameters as described in [Reproduction](REPRODUCE.md#linux-driver-build-and-selection). Confirm the actual PHY and clock/delay configuration before use: the PHY utility's inspect/configure modes write management registers and are not no-hardware probes.

Before starting traffic, check kernel/version, driver flags, 1000/full link, MTU/address, small and full-MTU ping, driver lifecycle and the [separate byte-integrity test](REPRODUCE.md#linux-programs). The integrity test sends 256 MiB in each direction and is not timed as this throughput benchmark. Keep a local console so connectivity loss cannot strand the test. Run one board workload at a time; do not combine throughput with module reload, payload-integrity traffic or FPGA programming. Record competing network traffic rather than assuming isolation.

The following are **target Linux** readbacks (not peer-PC driver settings):

```sh
uname -r
ip addr show dev eth0
ip link show dev eth0
for p in wide_copy fix_rx_align merge_copy merge_rx_copy; do
  printf '%s=' "$p"
  cat "/sys/module/opennic/parameters/$p"
done
cat /sys/class/net/eth0/gro_flush_timeout
cat /sys/class/net/eth0/napi_defer_hard_irqs
cat /proc/sys/net/core/netdev_budget
cat /proc/sys/net/core/netdev_budget_usecs
iperf3 --version
```

Verify 1000/full through the target's PHY/link diagnostics, not just the negotiated link on the peer. Use a suitable test segment; an isolated point-to-point path is not established by the recorded evidence. The reference peer uses a bridge containing its physical Ethernet NIC. Configure the appropriate interface/bridge for your own topology; do not blindly change a management interface.

On **target Linux**, start one iperf server on default port 5201, only if another is not already running:

```sh
iperf3 -s -D --logfile iperf-test.log
```

Use a trusted test network. TCP 5201 carries control/TCP tests; UDP 5201 must also be permitted for UDP cases. The server is not the TCP integrity server on port 5002. On the **peer**, confirm `iperf3 --version` is 3.19.1 and ping `192.0.2.1` before the suite. Stop only the server you started after testing; do not use an unscoped process kill.

## 3. Exact 13-case workload

Every case uses one stream per direction (`-P 1`), omits two warm-up seconds (`-O 2`) and saves JSON (`-J`). The reported measurement duration is `-t`; omitted warm-up time is additional. `-R` makes the FPGA server send; `--bidir` sends simultaneously in both directions. UDP rate is the **offered payload bitrate**, not a promise of delivery. `-l 1472` is the UDP payload length, fitting an IPv4 MTU of 1500 without IP options; it is not the Ethernet frame size. TCP uses iperf defaults for buffer/block sizing; no extra socket-buffer option is supplied.

| Case names, in execution order | Protocol/direction | Measured duration | Runs |
| --- | --- | ---: | ---: |
| `tcp_fpga_rx_1` … `_3` | TCP peer → FPGA | 30 s | 3 |
| `tcp_fpga_tx_1` … `_3` | TCP FPGA → peer, `-R` | 30 s | 3 |
| `udp_fpga_rx_18M_1` … `_3` | UDP peer → FPGA, `-b 18M -l 1472` | 20 s | 3 |
| `udp_fpga_rx_20M_1` … `_3` | UDP peer → FPGA, `-b 20M -l 1472` | 20 s | 3 |
| `tcp_full_duplex` | TCP simultaneously both ways, `--bidir` | 60 s | 1 |

Run this Bash example on the **peer**, not the FPGA. It preserves the measured order and two-second pause between cases. `mktemp` creates a fresh results directory and avoids overwriting a measurement. Record nonzero exits and JSON errors as failed cases; do not replace them with zeros or select only favorable runs.

```bash
TARGET_IP=192.0.2.1
RESULTS_DIR=$(mktemp -d ./iperf-results.XXXXXX)
run_case() {
  name=$1
  shift
  iperf3 -c "$TARGET_IP" -P 1 -O 2 -J "$@" \
    >"$RESULTS_DIR/$name.json" 2>"$RESULTS_DIR/$name.stderr"
  result=$?
  printf '%s\n' "$result" >"$RESULTS_DIR/$name.exit"
  sleep 2
}
for i in 1 2 3; do run_case "tcp_fpga_rx_$i" -t 30; done
for i in 1 2 3; do run_case "tcp_fpga_tx_$i" -t 30 -R; done
for rate in 18M 20M; do
  for i in 1 2 3; do
    run_case "udp_fpga_rx_${rate}_$i" -t 20 -u -b "$rate" -l 1472
  done
done
run_case tcp_full_duplex -t 60 --bidir
printf 'Results: %s\n' "$RESULTS_DIR"
```

This is the traffic-command sequence. To repeat **counter collection** as well, take the before/after snapshots below around each individual iperf command, before the two-second pause; the traffic-only example does not collect target counters automatically. The suite has no UDP TX or UDP bidirectional cases; do not infer those results from these measurements.

## 4. Counter collection and interpretation

Using the target console, capture the following immediately before and after each case and label both snapshots with that case name:

```sh
cat /proc/stat
cat /proc/interrupts
cat /proc/net/dev
ip link show dev eth0
```

Subtract target counter values only if they are monotonic, without reboot/module reload in the interval. For the first eight aggregate CPU fields (`user nice system idle iowait irq softirq steal`), CPU busy percentage is `100 × (sum(delta) − delta_idle − delta_iowait) / sum(delta)`. Driver IRQ deltas come from the OpenNIC lines in `/proc/interrupts`; network deltas from the target `eth0` row in `/proc/net/dev`. The snapshot interval includes command/console overhead and warm-up, so it is **not exactly the iperf measurement window**.

Post-load inspection also records UDP counters from `/proc/net/snmp`, including `RcvbufErrors` and `InCsumErrors`, softnet counters and NIC no-descriptor/DMA-error registers. Some published post-load values are cumulative, not per-case deltas; they cannot be assigned to a particular UDP run. The reference system's hardware/register inspection needs its platform integration; no generic board register-reader is claimed here. Preserve raw logs and failed cases locally when repeating the test.

## 5. Result calculation

Use receiver results, not sender offered rate. In iperf JSON, use `end.sum_received` (or UDP `end.sum` when that receiver section is supplied instead). Divide `bits_per_second` by **1000000** to report Mbit/s. For FPGA TX with `-R`, that receiver is the peer. For `--bidir`, `end.sum_received` gives FPGA RX and `end.sum_received_bidir_reverse` gives FPGA TX in the recorded schema; preserve both rather than dividing a total by two.

Take the median of the three unidirectional TCP receiver rates separately for RX and TX. The simultaneous total is the sum of the two receiver rates in the **single simultaneous case**, not the sum of the independent RX/TX medians. For UDP keep all three receiver `lost_percent` values at each offered rate; retain reported packets/lost packets and do not replace reported loss with a differently derived byte fraction. Reject a case with a nonzero exit, JSON `error`, missing receiver section or mismatched stream/duration/omit settings, even if some rate is printed.

The supplied [measurement extract](../evidence/performance.json) includes the 13 case settings, receiver statistics and numeric system counters, not every serial/server log or a complete board image. From the repository root, run:

```sh
python3 tools/check_performance.py
```

`PERFORMANCE_RECOMPUTED` verifies the supplied case contract and summary. It does not run iperf or measure your board; the script reads the fixed evidence file, not the new directory produced above.

## 6. Recorded results and limits

| Linux TCP receiver measurement | Mbit/s |
| --- | ---: |
| FPGA RX median, three runs | 27.089189 |
| FPGA TX median, three runs | 25.235198 |
| Simultaneous FPGA RX | 12.024217 |
| Simultaneous FPGA TX | 12.723299 |
| Simultaneous RX + TX | 24.747515 |

| UDP offered rate | Receiver loss, three runs |
| --- | --- |
| 18 Mbit/s | 0 / 0.0817768473 / 0 % |
| 20 Mbit/s | 5.7406971267 / 0 / 5.8411352529 % |

All 13 cases were collected successfully, but `performance_health_passed` is **false**: zero-loss UDP at these offered rates is not established. Byte-integrity success is a separate functional result; TCP can recover lost packets through retransmission. The 1G physical link does not imply 1G application throughput.

UDP snapshots show approximately 97–98% CPU busy and nearly one RX interrupt per packet. Socket receive-buffer overflows and NIC no-descriptor drops were observed; no DMA/checksum-error increase was observed within the recorded scope. These are observations, not proof of one cause for every lost packet. Cumulative counters, shared-network traffic and nonidentical windows limit causal attribution. Board timing qualifications are listed in [Verification limits](VERIFICATION.md#4-what-a-pass-does-not-establish).

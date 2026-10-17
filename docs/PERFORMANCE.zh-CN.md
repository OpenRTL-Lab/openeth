# Linux 以太网性能方法与结果

[English](PERFORMANCE.md)

这是 **Linux 端到端应用吞吐测试**，不是 RTL 周期基准或 PHY 线速测量。OpenNIC 驱动运行在 FPGA 内的 Rocket CPU 上，iperf 客户端运行在另一台 Linux 以太网对端。**FPGA RX** 指对端 → FPGA，**FPGA TX** 指 FPGA → 对端。硬件型号、时钟和以太网／USB 连接见[参考系统](REFERENCE_SYSTEM.zh-CN.md)，功能测试另见[验证说明](VERIFICATION.zh-CN.md)。

## 1. 实测配置

| 配置项 | 记录值 |
| --- | --- |
| 平台 | VU13P FPGA 参考系统，单 Rocket RV64；CPU／NIC50MHz，MAC／RGMII125MHz |
| 链路 | RTL8211E-VL，1000Mbit/s 全双工，MTU1500 |
| 软件 | Linux `6.6.0-opennic-r1`；两端 iperf3.19.1 |
| 地址 | 目标 `192.0.2.1/24`、对端 `192.0.2.2/24`，同一二层测试网络 |
| OpenNIC | 寄存器接口版本1；每方向32个2048字节一致性DMA缓冲 |
| 驱动参数 | `wide_copy=Y`、`fix_rx_align=Y`、`merge_copy=Y`、`merge_rx_copy=Y` |
| 软件中断延迟 | `gro_flush_timeout=0`、`napi_defer_hard_irqs=0` |
| Linux 接收预算 | `netdev_budget=300` 包、`netdev_budget_usecs=8000` 微秒 |

[四个驱动开关](REPRODUCE.zh-CN.md#linux驱动构建与配置)选择对齐与字拷贝路径，不启用零拷贝或硬件卸载。驱动使用软件校验和、NAPI 接收轮询、GRO 接收聚合，没有硬件校验和／分段卸载或可编程硬件中断聚合器。DMA 每次等待主存响应后再发下一事务。这些 CPU、拷贝、中断和主存路径开销都包含在实测吞吐中。

## 2. 前提与目标端准备

须使用已正确集成并启动的系统；仓库不提供其完整 FPGA 工程、设备树或启动镜像。按照[复现指南](REPRODUCE.zh-CN.md#linux驱动构建与配置)，针对准确目标内核编译驱动并开启四参数。访问前确认实际 PHY 及其时钟／延时配置：PHY 工具的 inspect／configure 会写管理寄存器，并非无硬件探测。

发流前核内核／版本、驱动参数、千兆全双工、MTU／地址、小包和满 MTU ping、驱动生命周期及[独立逐字节完整性测试](REPRODUCE.zh-CN.md#linux-programs-cn)。完整性每向传输256MiB，不计入本吞吐基准。保留本地控制台，避免断网后失去控制；板上同一时刻仅运行一项负载，不与模块重载、完整性流量或 FPGA 配置并行。记录网络竞争流量，不假定完全隔离。

以下在 **FPGA 目标 Linux** 读回，不是读取对端计算机的驱动设置：

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

用目标端 PHY／链路诊断确认千兆全双工，不能仅查看对端网卡协商结果。使用适当测试网段；记录不能证明是隔离点对点连接。参考对端使用包含物理网卡的网桥。按自己的拓扑配置接口／网桥，不盲改管理网口。

在**目标 Linux** 启动默认5201端口的单个 iperf 服务，仅在没有已有服务时执行：

```sh
iperf3 -s -D --logfile iperf-test.log
```

仅在可信测试网使用。TCP5201承载控制及TCP测试；UDP用例也须允许UDP5201。这不是5002端口的TCP完整性服务。在**对端**确认 `iperf3 --version` 为3.19.1，先ping通192.0.2.1。测试后仅停止自己启动的服务，不用无范围限制的进程终止命令。

## 3. 准确的13项负载

每项每方向单流（`-P 1`）、忽略前2秒预热（`-O 2`）、保存JSON（`-J`）。`-t`是报告的测量时长，忽略的预热另计。`-R`使FPGA服务器发送，`--bidir`同时双向发送。UDP速率是**提供的载荷比特率**，不承诺全部送达；`-l 1472`为UDP载荷长度，在无IP选项时适配IPv4 MTU1500，不是以太网帧长。TCP使用iperf默认缓冲／块长度，不额外指定套接字缓冲选项。

| 用例名，按执行顺序 | 协议／方向 | 测量时长 | 次数 |
| --- | --- | ---: | ---: |
| `tcp_fpga_rx_1` … `_3` | TCP 对端 → FPGA | 30秒 | 3 |
| `tcp_fpga_tx_1` … `_3` | TCP FPGA → 对端，`-R` | 30秒 | 3 |
| `udp_fpga_rx_18M_1` … `_3` | UDP 对端 → FPGA，`-b 18M -l 1472` | 20秒 | 3 |
| `udp_fpga_rx_20M_1` … `_3` | UDP 对端 → FPGA，`-b 20M -l 1472` | 20秒 | 3 |
| `tcp_full_duplex` | TCP 同时双向，`--bidir` | 60秒 | 1 |

以下 Bash 示例在**对端**运行，不在FPGA运行，保留实测顺序和用例间2秒间隔。`mktemp`创建新结果目录，避免覆盖测量。非零退出及JSON错误须记为失败，不改成0或只挑最好结果。

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
printf '结果：%s\n' "$RESULTS_DIR"
```

以上是发流命令序列。要同时复现**计数采集**，须在每个iperf命令前后、2秒间隔之前，采集下面的目标快照；上例不会自动采集目标计数。本套没有UDP TX或UDP双向项目，不能据此推断这些结果。

## 4. 计数采集与解释

通过目标控制台，在每项测试紧邻前后采集以下内容，两个快照都标明对应的用例名：

```sh
cat /proc/stat
cat /proc/interrupts
cat /proc/net/dev
ip link show dev eth0
```

仅在区间内无重启／驱动重载且计数单调时相减。取汇总CPU前8字段（`user nice system idle iowait irq softirq steal`），忙率为 `100 × (sum(delta) − delta_idle − delta_iowait) / sum(delta)`。驱动中断增量来自 `/proc/interrupts` 的OpenNIC行，网络增量来自目标 `/proc/net/dev` 的eth0行。快照区间包含命令／控制台开销及预热，**并非准确的iperf测量窗口**。

负载后还采集 `/proc/net/snmp` 的UDP计数，包括 `RcvbufErrors`、`InCsumErrors`，以及softnet、NIC无描述符／DMA错误寄存器。部分公开负载后数值是累计值而非逐用例差分，不能归到某一轮UDP。硬件／寄存器诊断需要参考系统的平台接入，这里不声称提供通用板级寄存器读取器。重测须在本地保留原始日志及失败用例。

## 5. 结果计算

取接收端结果，而非发送端提供速率。iperf JSON取 `end.sum_received`，若UDP提供的接收节是 `end.sum` 则取后者。`bits_per_second`除以 **1000000** 得Mbit/s。`-R`测FPGA TX时接收端为对端；记录的 `--bidir` 格式中，`end.sum_received` 为FPGA RX，`end.sum_received_bidir_reverse` 为FPGA TX。分别保留两值，不把总数平分。

单向TCP每方向3个接收速率独立取中位数。双向合计取**同一次同时双向测试**的两个接收速率之和，不把单向中位数相加。UDP每档速率保留全部3次接收端 `lost_percent` 及包数／丢包数，不以另一种字节比例改写原生丢包率。非零退出、JSON含 `error`、缺接收节或流数／时长／omit不符的用例，即便打印了速率也判为无效。

所附[测量摘录](../evidence/performance.json)包括13项设置、接收端统计及数值计数，不是全部串口／服务日志或完整板镜像。在仓库根目录执行：

```sh
python3 tools/check_performance.py
```

`PERFORMANCE_RECOMPUTED`只核所附用例合同及摘要，不运行iperf或测量你的板；脚本读取固定证据文件，不读取上例新产生的结果目录。

## 6. 实测结果与边界

| Linux TCP 接收端测量 | Mbit/s |
| --- | ---: |
| FPGA RX，3次中位数 | 27.089189 |
| FPGA TX，3次中位数 | 25.235198 |
| 同时双向中的 FPGA RX | 12.024217 |
| 同时双向中的 FPGA TX | 12.723299 |
| 同时 RX＋TX 合计 | 24.747515 |

| UDP 提供速率 | 3次接收端丢包率 |
| --- | --- |
| 18Mbit/s | 0 / 0.0817768473 / 0 % |
| 20Mbit/s | 5.7406971267 / 0 / 5.8411352529 % |

13项均成功采集，但 `performance_health_passed` 为 **false**：这些发送速率下UDP零丢包尚未成立。逐字节完整性是另一项功能结果，TCP可以重传恢复丢包；千兆物理链路不等于千兆应用吞吐。

UDP快照显示CPU约97～98%忙、RX中断近每包一次。观察到套接字接收缓冲溢出和NIC无描述符丢弃，记录范围内未见DMA／校验和错误增量。这些只是观察，不证明所有丢包来自唯一原因；累计计数、共享网络报文及不相同的窗口均限制因果归因。板级时序限定见[验证边界](VERIFICATION.zh-CN.md#4-通过不代表什么)。

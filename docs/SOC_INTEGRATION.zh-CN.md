# Chipyard SoC 集成

[English](SOC_INTEGRATION.md)

## 生成范围

数字系统包含 Rocket CPU、一致性主存互连、以太网 DMA／寄存器控制器及 PHY
管理。其 `ChipTop` 暴露 AXI4 主存、调试、UART、SPI、管理信号和两路 64 位
报文流。所附连接生成器把报文流接到公开的 MAC、整帧缓存、跨时钟域与 RGMII
模块，组成 `OpenEthSoC`。

这是**数字 SoC 生成配方**，不是完整 FPGA 开发板工程。DDR4 控制器、时钟发生器、
焊盘／引脚映射和板级约束仍由集成者提供。[参考系统](REFERENCE_SYSTEM.zh-CN.md)
描述物理连接，不提供这些板级原始设计。

## 公开源码基线

必须使用 [ucb-bar/chipyard 的提交
69eba860a352343e4ac6b6df0f3638a79a86ec78](https://github.com/ucb-bar/chipyard/tree/69eba860a352343e4ac6b6df0f3638a79a86ec78)，
不要用浮动分支或其他标签。该提交的 Git 子模块条目锁定依赖版本。
`tools/chipyard.py prepare` 获取该提交和 Scala 构建需要的根级子模块，然后按
允许列表复制本仓库的覆盖层：

| OpenETH 中的源码 | 相对 Chipyard 的目标路径 |
| --- | --- |
| `nic/core/src` | `opennic/core/src` |
| `nic/platform/src` | `opennic/platform/src` |
| `integration/chipyard/soc` | `opennic/soc/src/main/scala/opennic/soc` |
| `integration/chipyard/eth` | `generators/chipyard/src/main/scala/eth` |
| `integration/chipyard/opennic-platform.sbt` | `opennic-platform.sbt` |
| `integration/chipyard/opennic-soc.sbt` | `opennic-soc.sbt` |

脚本不导入私有工作副本或预编译 SoC 类。Chipyard 与各子模块保留自身许可，见
[许可说明](../THIRD_PARTY.zh-CN.md)。引导 ROM 来自锁定的公开 TestChipIP，含
对应源码和重建规则。它只是 CPU 引导跳板，不是 Linux 镜像或板级启动包。

## 拼装与接口

`integration/chipyard/soc/OpenEthSystem.scala` 提供系统配置和连接绑定。
`OpenEthReferenceConfig` 选择单个支持浮点的 RV64 Rocket、4 GiB 外部可寻址
主存、128 位系统总线及 512 位主存总线／AXI 接口。各 SoC 总线配置为 50 MHz。
MAC／RGMII 侧需要独立的 125 MHz 时钟及 PHY 接收时钟。报文控制器使用 32 项队列，
最大帧长为 2048 字节。

| 连接 | 实现 |
| --- | --- |
| NIC 寄存器 | 系统总线上的 `OpenNicTL.csrNode`，地址 `0x10016000`；经过位宽转换、分片和缓存；有破坏性读语义的队列寄存器前不接原子操作模拟适配器 |
| NIC DMA | `OpenNicTL.dmaNode` 经 64 位位宽适配器和缓存作为系统总线主设备，访问一致性主存层级 |
| 中断 | `intNode` 接 `ibus.fromSync`；生成的 TX、RX PLIC 源分别为 3、4 |
| 管理寄存器 | 地址 `0x6000`，兼容字符串 `openhw,openeth-management-v1`；提供 MDIO 与 PHY 复位，关闭管理模块的报文 FIFO 数据面 |
| 外部主存 | AXI4，512 位数据，从 `0x80000000` 开始；主存控制器由集成者提供 |
| 软件控制台 | 地址 `0x10020000` 的 SiFive 兼容 UART |
| 可选 SPI 外设 | 地址 `0x10031000`；附轮询式 SPI 存储卡资源描述，不是存储卡仿真模型 |

`WithOpenNicPorts` 将报文流引出 `ChipTop`。`OpenEthExportConfig` 在生成外层
`TestHarness` 时保留这些端口。`WithOpenNicLoopback` **只在仿真外壳内**将 TX
直接接 RX。`WithUnusedSPI` 固定未使用的仿真 SPI 输入，不加载软件，也不模拟
存储卡。`OpenEthSimConfig` 为自行构建软件执行仿真器的用户选择 DMI 调试接口；
生成本身不会执行[裸机测试](REPRODUCE.zh-CN.md#bare-metal-program-cn)。

物理以太网使用 `OpenEthSoC`，不是外壳回环。连接生成器核对 `ChipTop` 的 102 个
端口，连接每个原生报文流字段，并实例化 `nic/integration/OpenEthBoardLink.sv`。
它透传主存、管理、UART、SPI、JTAG、引导与复位接口，不包含板级引脚表。
`clock_uncore` 为 50 MHz，`gtx_clk` 与 `gtx_clk90` 为相差 90 度的 125 MHz
时钟，`phy_rxc` 来自 PHY。`reset_io` 高电平有效，`gtx_reset` 是高电平有效的
以太网时钟域复位。正确释放复位、配置 PHY 延时及建立源同步约束由集成者负责。

管理模块的字节流接口未使用，输入置为无活动状态；管理 MAC 事件输入置零，速度
字段固定为千兆模式。不能将这些事件寄存器当作实际 MAC 统计。报文完整性与 DMA
测试使用独立的报文控制器接口。

## 工具与命令

Scala 构建使用锁定 Chipyard 的 `project/build.properties` 指定的 sbt **1.8.2**、
Scala **2.13.12**、Chisel **6.5.0**、NIC 平台测试的 chiseltest **6.0.0** 和
ScalaTest **3.2.19**，以及 **firtool 1.75.0**。实际使用的 JDK 是 OpenJDK
**20.0.2**，须通过 PATH 为 sbt 启动器选择它；
JDK 21 与这个 sbt 启动器的 Scala 解析器不兼容。组件 Mill 构建的要求另见
[复现指南](REPRODUCE.zh-CN.md)。安装 Python 3.10+、Git、Make、**dtc 1.6.1**、Verilator **5.050**
和 C++ 编译器，将工具放入 PATH；允许访问 GitHub／Maven，或预先准备完整的
公共依赖缓存。复用依赖 JAR 不等于复用 SoC 编译结果，仍须编译源码。

在 OpenETH 仓库根目录执行，输出目录必须是新目录：

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

需要明确选择可执行文件时，使用 `--sbt` 和 `--firtool`。`--cross-compile`
指定公开引导 ROM 的 Makefile 使用的 RISC-V 编译器前缀，默认是
`riscv64-unknown-elf-`。锁定的上游副本已经包含引导 ROM；重建时需要对应的
RV64 裸机 GCC 和 objcopy。

准备与生成各自保存 JSON 收据和日志。状态非零就是失败，即使前面的阶段打印过
通过标记。脚本拒绝覆盖已有收据。准备后源码变化时应建立新工作目录，不能修改
收据绕过输入核验。完整构建可能需要数 GiB 磁盘和数十 GiB 内存。

生成成功必须同时满足所有平台测试、`OPENETH_SOC_CONFIG_PASS` 和
`OPENETH_SOC_GENERATION_PASS`。脚本检查 CPU／缓存／NIC／MDIO 层级、报文流
端口及生成的地址／中断约定，并拒绝非预期的加速器或报文控制器层级。输出包括：

- `generated/rtl/ChipTop.sv` 及其完整生成层级；
- `generated/OpenEth.fir`、注解和描述数字系统的 `OpenEth.dts`；
- 保存源码和输出哈希的 `generate.json`；
- 连接步骤生成的 `OpenEthSoC.sv`、`sources.f` 和 `link.json`。

物理集成顶层选择 `OpenEthSoC`。不要额外加入独立的 `rtl/nic/OpenNic.sv`
或另一份 NIC，因为生成的 SoC 已包含该层级。`sources.f` 也列出仅用于仿真的
生成模块；须选择正确顶层，编译 `TestHarness` 时还需上游黑盒资源。
Xilinx RGMII 模式另需许可允许的厂商原语，见[验证说明](VERIFICATION.zh-CN.md)。

`--lint` 使用 Verilator 和 `TARGET="GENERIC"` 双沿 I/O 行为模型检查完整的
`OpenEthSoC` 层级，不检查 Xilinx 焊盘时序。生成封装默认使用
`TARGET="XILINX"` 供物理集成。检查退出码非零即失败；警告保存在 `lint.log`，
不能因此宣称无警告或时序签核。只有不执行该检查、仅生成源码时才省略 `--lint`。
两个 Python 测试命令检查源码守卫和封装生成器，不执行电路行为仿真。

生成日志、FIRRTL 源位置和收据可能含构建机器的绝对路径。这些是本地构建产物，
不是发布文件，未经过独立隐私检查不能上传。生成结构检查不等于形式等价、综合
时序、Linux 启动测试或新的 FPGA 性能测量。各验证边界见
[验证说明](VERIFICATION.zh-CN.md)，已有上板数据见[性能说明](PERFORMANCE.zh-CN.md)。

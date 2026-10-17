# OpenETH — 千兆以太网 RTL 与软件

[English](README.md)

开放硬件，从算法到 RTL。

OpenETH 提供连接片上系统（SoC）与千兆以太网 PHY 的可复用组件，包括网络控制器与 DMA、整帧缓存与跨时钟域、MAC 与 CRC、RGMII I/O 及 PHY 管理。仓库提供这些组件的 Chisel 与 SystemVerilog 源码，以及 Linux 驱动、裸机与 Linux 测试程序和仿真测试。

[发布 v1.1.0](https://github.com/OpenRTL-Lab/openeth/releases/tag/v1.1.0) · [接口说明](docs/INTERFACES.zh-CN.md) · [构建与测试](docs/REPRODUCE.zh-CN.md)

## 仓库内容

- **网络控制器与 DMA（OpenNIC）：**[Chisel](nic/core/src/main/scala/opennic) 与[生成 RTL](rtl/nic)。
- **整帧缓存与跨时钟域：**[源码与测试](buffer)。
- **MAC 与 CRC：**[源码与测试](mac)。
- **RGMII 与 FPGA I/O：**[源码与测试](rgmii)。
- **PHY 管理：**[MDIO 接入](integration/chipyard/eth)与[PHY 工具](software/linux/auto_phy.c)。
- **数据通路集成：**[组件之间的 RTL 连接](nic/integration)。
- **软件：**[裸机原生流回环](nic/software/baremetal)、[Linux 驱动](software/linux/v11)、[PHY 工具](software/linux/auto_phy.c)、[TCP 完整性测试](software/linux/tcp_integrity.c)及其[对端程序](tools/tcp_integrity_peer.py)。
- **集成约定：**报文流、主存接口、寄存器布局和复位行为。详情请看[接口说明](docs/INTERFACES.zh-CN.md)。

## 验证

### RTL 仿真验证

仿真覆盖 NIC 寄存器／DMA、MAC 帧处理与 CRC、整帧缓存跨时钟域及 RGMII 功能，包括背压、复位和故意注错的负向检查。宿主 C 测试单独列出，不冒充 RTL 仿真。覆盖详情请看[仿真验证](docs/VERIFICATION.zh-CN.md#simulation-cn)，命令与依赖请看[构建与测试](docs/REPRODUCE.zh-CN.md#chisel与rtl验证)。

### FPGA 实际上板验证

VU13P FPGA 参考系统已进行裸机报文／DMA 测试，以及 Linux 驱动、数据完整性和吞吐测试。功能正确与性能零丢包是不同结论。结果及边界请看[上板验证](docs/VERIFICATION.zh-CN.md#board-cn)，硬件环境请看[参考硬件及连接](docs/REFERENCE_SYSTEM.zh-CN.md)。

## 程序

**裸机程序：**所附程序通过直接报文流回环检查网络控制器，不经过 Linux 或外部 PHY 链路。平台要求和通过判据请看[裸机程序](docs/REPRODUCE.zh-CN.md#bare-metal-program-cn)。

**Linux 程序：**包括平台驱动、PHY 配置工具和双向 TCP 逐字节完整性测试。详情请看[Linux 程序](docs/REPRODUCE.zh-CN.md#linux-programs-cn)及[驱动构建与配置](docs/REPRODUCE.zh-CN.md#linux驱动构建与配置)。

## 性能

在 50MHz Rocket 参考系统上，Linux TCP 实测 **RX 27.089Mbit/s**、**TX 25.235Mbit/s**、**同时双向合计 24.748Mbit/s**。RX／TX 以 FPGA 为参照；这是应用吞吐，不是 PHY 的千兆线速，UDP 仍有丢包。接线、前提、全部 13 个用例、准确命令及统计口径，详情请看[性能测试方法与结果](docs/PERFORMANCE.zh-CN.md)。

## 复现与交付范围

文档提供组件 SV 生成、仿真及软件源码构建方法。参考板系统仅作描述，不提供完整板级工程、原理图、引脚约束、厂商 IP 配置或启动镜像。详情请看[构建与测试](docs/REPRODUCE.zh-CN.md)及[参考系统范围](docs/REFERENCE_SYSTEM.zh-CN.md#scope-cn)。

## 许可与集成

原创代码采用 [MIT 许可](LICENSE)，允许免费商用，不以购买服务为前提；须保留[第三方声明](THIRD_PARTY.zh-CN.md)。可选数据通路定制或 ASIC／FPGA 集成服务，见 [OpenRTL Lab](https://openrtl.top/)。

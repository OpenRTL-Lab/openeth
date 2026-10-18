# 参考硬件与连接

[English](REFERENCE_SYSTEM.md)

本文描述已记录测试所用的 FPGA 系统。OpenETH 提供以太网组件；OpenNIC 是 SoC 主存与 MAC 之间的 DMA／寄存器控制器。TX 指 FPGA 发往对端，RX 指对端发往 FPGA。组件信号见[接口说明](INTERFACES.zh-CN.md)；完整板级设计不属于本仓库。

## 硬件与运行条件

| 项目 | 参考配置 |
| --- | --- |
| FPGA 芯片 | Xilinx Virtex UltraScale+ VU13P |
| CPU 与主存 | 单 Rocket RV64 CPU，CPU／NIC 时钟域 50MHz，外部 DDR4 主存 |
| 以太网 PHY | Realtek RTL8211E-VL；PHY ID `001c:c915`；RGMII 数据通路与 Clause22 MDIO 管理 |
| 以太网链路 | 1000Mbit/s 全双工；Linux MTU1500 |
| 以太网时钟 | MAC／RGMII 时钟域 125MHz；RX 时钟来自 PHY；参考集成的 TX 时钟相移 90°（2ns） |
| 板上时钟器件 | Si5338；不分发板级专用编程映像 |
| FPGA 配置连接 | Digilent USB-JTAG 接口，USB ID `0403:6010`，由 Vivado 使用 |
| CPU 调试连接 | 独立 FTDI FT232H USB-JTAG 适配器，USB ID `0403:6014`，由 OpenOCD 使用；JTAG 时钟 4MHz |
| 串口控制台 | CH340／CH341 USB-UART，USB ID `1a86:7523`；115200 波特，8 数据位、无校验、1 停止位、无流控 |
| 目标软件 | RV64 Linux `6.6.0-opennic-r1`；OpenNIC 平台驱动及所附 PHY／完整性工具 |
| 吞吐工具 | 目标与 Linux 对端均为 iperf3.19.1 |

此表标明 FPGA 芯片及连接外设，不是开发板型号或物料清单。不公开开发板型号／修订号、FPGA 完整订货后缀及 DDR4 芯片料号，不以猜测补齐，也不推断其他开发板具有相同引脚和时钟连接。

## 数据与管理连接

```text
Linux 以太网对端                           VU13P FPGA 参考系统
物理以太网卡 ── 以太网测试网段 ─────────── RJ45 / RTL8211E-VL PHY
                                                 │ RGMII 数据与时钟
                                                 ▼
                                          MAC ↔ 整帧缓存／CDC
                                                 ↕ 报文流
                                          OpenNIC DMA ↔ DDR4
                                                 ↕
                                          Rocket RV64 / Linux

管理计算机 ── USB / Digilent JTAG ──────── FPGA 配置端口
           ├─ USB / FT232H JTAG ────────── SoC CPU 调试端口
           └─ USB / CH340/CH341 ────────── SoC UART 控制台
```

CDC 指跨时钟域传输。MDIO 是 FPGA 到 PHY 的独立管理连接，不传输报文载荷。RX 时钟从 PHY 送往 FPGA；TX 时钟／数据／控制从 FPGA 送往 PHY，要求正确的源同步时序及 PHY 专用 RX 延时配置。

以太网对端是运行 iperf 客户端的计算机，不是串口宿主或 USB 网卡。USB 调试／控制台可透传至管理虚拟机，但不属于被测报文路径。记录中的测试地址是 **FPGA `192.0.2.1/24`** 和 **对端 `192.0.2.2/24`**，位于同一二层测试网络，无需经过路由。这些文档地址用于本地测试网，不用于访问互联网。

将板上 PHY 的 RJ45 与对端物理以太网口接入适当的同一千兆网段。已有记录不能证明使用了完全隔离、无交换机及无其他报文的直连线，不能这样描述实测条件。重测时应记录实际交换机／网桥路径及竞争流量。参考 Linux 对端将测试地址配置在包含物理网卡的网桥上，而不是另走一条网络路径。

两条 JTAG 面向不同目标：配置 FPGA 不等于加载 CPU ELF，CPU 调试也不能配置 FPGA 逻辑。适配器必须对应正确功能。这里是连接关系说明，不是连接器逐针接线指南；连接外部信号前，须使用板厂正确引脚、电平和供电说明。无需公开私有 USB 序列号或管理网地址。

## 软件角色与测试顺序

1. 使用自己的板级资料，将组件集成并配置到兼容 FPGA／SoC 平台。公开仓库不执行这个整板步骤。
2. 通过串口收集目标日志，需要时使用 CPU 调试接口加载裸机程序。所附原生流回环 ELF 的独立要求见[裸机程序](REPRODUCE.zh-CN.md#bare-metal-program-cn)。
3. 启动兼容 Linux、加载匹配驱动，并安全配置实际 RTL8211E-VL PHY。见[Linux 程序](REPRODUCE.zh-CN.md#linux-programs-cn)及[驱动配置](REPRODUCE.zh-CN.md#linux驱动构建与配置)。
4. 先检查链路、ping、生命周期及载荷完整性，再测吞吐。见[验证说明](VERIFICATION.zh-CN.md#board-cn)及[性能方法](PERFORMANCE.zh-CN.md)。

<a id="scope-cn"></a>

## 公开范围

公开 FPGA／外设芯片型号、功能连接、时钟、软件要求、测试方法及测量结果，不公开开发板型号及修订号。提供[数字 SoC 配置与生成配方](SOC_INTEGRATION.zh-CN.md)，包括生成的数字系统设备树描述。不提供板厂原理图／PCB 原件、连接器引脚表、完整板级实现工程、引脚约束、厂商 IP 配置、Si5338 映像、板级 Linux 设备树补丁及启动镜像。数字 SoC／组件 SV 生成及软件构建在说明范围内可复现，但仓库加开发板并不是完整整板重建配方。FPGA I/O 仿真是功能验证，不是板级走线或完整工艺／电压／温度时序签核。

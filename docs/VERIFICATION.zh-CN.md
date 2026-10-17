# 验证覆盖

[English](VERIFICATION.md)

OpenETH 的硬件验证分为两层：**组件 RTL 仿真**和**参考 FPGA 系统实际运行**。OpenNIC 是靠近主存的寄存器／DMA 控制器，MAC、整帧缓存与 RGMII 将它连接到 PHY。本文说明实际检查了什么，不仅列出有哪些测试。构建命令和工具版本见[复现指南](REPRODUCE.zh-CN.md)，实体连接见[参考系统](REFERENCE_SYSTEM.zh-CN.md)。

<a id="simulation-cn"></a>

## 1. RTL 仿真验证

这些测试执行电路模型，不使用 FPGA 或网线。负向检查会故意引入错误，测试必须拒绝该错误，以避免没有真正检查相关行为却显示通过。

| 组件 | 实际覆盖及预期证据 |
| --- | --- |
| OpenNIC 寄存器与 DMA | 1 项 CSR、6 项 DMA 测试：描述符／完成项、逐字节数据、主存背压、错误处理及复位。主 DMA 结果为 `OPENNIC_DMA_PASS`，82 帧、25016 周期、4826 读拍及 4826 写拍；也使用较小队列覆盖边界。 |
| MAC 与 CRC | 165 随机帧、201 CRC 向量、1024 RGMII 向量，最大帧长 2048 字节；`MAC_CORE_PASS`。两个故意损坏的检查必须被拒绝。 |
| MAC 独立时钟 | 产品封装在独立 TX／RX 时钟下运行；`MAC_PRODUCT_CLOCK_PASS`。不等于板级时序验证。 |
| 整帧缓存、CDC 与流位宽转换 | 4 种配置各 3 个随机种子，共 12 项、4 个损坏负控、字节原语检查、导出封装静态检查及 6 行理想接口性能结果。小型参数化夹具不等于全尺寸上板压力测试。 |
| RGMII FPGA I/O | Vivado2025.2 UNISIM 功能仿真：1025 对 TX、1025 对 RX、3 项复位，TX 时钟相移 2ns；`RGMII_1G_PASS`。`WRONG_BYTE`、`WRONG_CTL`、`UNSHIFTED_CLOCK` 均须出现 `RGMII_1G_FAIL` 且无 PASS。 |

原生 NIC 与独立 DMA 还分别生成完整 SV 编译单元，包含 8 和 3 个模块定义。生成或静态检查成功不等于仿真通过；两个单元含重名模块，不得同时编译。

独立流程使用 Mill1.1.7、Scala2.13.12、Chisel6.5.0、chiseltest6.0.0、ScalaTest3.2.19、firtool1.75.0，实际使用的 Verilator 版本为 5.050。Java／C++ 依赖、命令、新输出目录及标记见[RTL 测试方法](REPRODUCE.zh-CN.md#chisel与rtl验证)。

RGMII 数字逻辑生成不等于 FPGA I/O 测试；后者需要合法安装的厂商原语和[专用入口](../tools/test_rgmii_io.py)。入口同时检查标记与进程结果：xsim 负控进程可能打印失败却退出 0。UNISIM 功能结果不验证 PCB 延时、模拟信号质量或完整工艺／电压／温度时序。

## 2. 宿主软件检查

[宿主自检](../tools/smoke.py)执行 1573632 个拷贝用例与 4098 个保护页长度、524544 个 RX 合并用例与 67617 个受保护用例、损坏负控及不访问硬件的 PHY 策略自检。[TCP 程序测试](../tools/test_tcp_integrity.py)在 localhost 每向传输 1MiB，确认一字节损坏被拒绝。源码头检查及[测量重算](../tools/check_performance.py)也是宿主检查，不是电路仿真或新板测。命令及可信网络注意事项见[复现指南](REPRODUCE.zh-CN.md)。

<a id="board-cn"></a>

## 3. FPGA 实际上板验证

已测平台为 VU13P FPGA 参考系统，Rocket CPU／NIC 时钟域 50MHz，RTL8211E-VL PHY 千兆全双工。不分发完整板级镜像，见[硬件与范围](REFERENCE_SYSTEM.zh-CN.md)。下述 RX／TX 均以 FPGA 为参照。

### 裸机验证与所附程序

参考系统的**裸机线上测试**通过实体以太网链路检查 64 帧，包括载荷／填充边界、8192 字节 DDR 主存模式、PHY 身份与链路、中断、缓冲区保护及 DMA 状态。记录的成功标记为 `OPENETH_BOARD_PASS code=0000000000000000`。它验证所测报文／DMA 通路，不是 Linux 测试、吞吐基准或穷尽以太网一致性验证。

公开[裸机程序](../nic/software/baremetal)是另一项**原生流回环**测试：256 个载荷经 OpenNIC 和直接流回环，检查逐字节数据、保护区及中断，不经过 MAC、PHY 或网线。标记为 `OPENNIC_SOC_PASS frames=256 bytecheck=1 guards=1 irq=1`；1～2048 字节载荷中有些不是合法线上以太网帧。所附源码已实际构建 ELF，但这不证明这个准确程序已在参考板运行。构建及要求见[裸机说明](REPRODUCE.zh-CN.md#bare-metal-program-cn)。

参考线上测试夹具与板级加载器未打包。不得将公开 256 载荷程序当作 64 线上帧结果的来源。其他平台需要自己的地址映射、中断连接及物理链路测试夹具。

### Linux 验证

驱动运行在 FPGA CPU 上，对端运行客户端／测试程序。实测配置启用 `wide_copy`、`fix_rx_align`、`merge_copy`、`merge_rx_copy`；[驱动配置](REPRODUCE.zh-CN.md#linux驱动构建与配置)定义其作用及内核匹配要求。

| 检查 | 实际结果及边界 |
| --- | --- |
| PHY 与连通性 | RTL8211E-VL 身份、千兆全双工、小包与满 MTU ping 通过。仅 ping 不证明任意载荷完整性。 |
| 驱动生命周期 | 网卡停用／启用、模块卸载重载、参数读回及切换后 ping 通过。仅在有本地控制台且无网络负载时执行。 |
| 双向载荷完整性 | 每向 268435456 字节（256MiB），零错且接收／预期 SHA-256 一致；使用所附 TCP 协议，不是 iperf 生成流量。 |
| 完整性负控 | 在 1048576 字节（1MiB）中故改一字节，准确检出目标 1 字节不匹配，目标服务退出 10，符合预期。 |
| 吞吐 | 13 个 iperf 用例均取得可用接收端结果；UDP 仍丢包，采集成功不等于零丢包验收。 |
| 负载后检查 | 未见 DMA 错误或内核失败标记，检查了满 MTU 连通性；保留套接字溢出及无描述符丢弃，不描述为所有错误全零。 |

公开[Linux 工具与对端方法](REPRODUCE.zh-CN.md#linux-programs-cn)说明在兼容集成平台编译、执行同一完整性协议的步骤。吞吐参数、顺序、接收端统计与计数见[性能说明](PERFORMANCE.zh-CN.md)。

## 4. 通过不代表什么

所测长度和配置下，逐字节完整性测试通过。不等于无限时长稳定、所有错误模式正确、Linux 千兆吞吐或 UDP 零丢包。组件仿真、软件编译、板上功能、吞吐与时序签核分别判断。

参考布线的建立时间裕量 +0.189ns、保持时间裕量 +0.010ns、脉宽裕量 +0.039ns，TNS／THS 为 0，467239 个必需网络布通、路由错误为 0。14 输入和 22 输出缺外部延时约束。非专用 JTAG 时钟条件、板级／封装在工艺／电压／温度范围内的延时及全设计 CDC 未签核，不能提供可移植板约束或量产认证。

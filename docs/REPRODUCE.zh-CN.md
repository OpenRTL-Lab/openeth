# 复现指南

[English](REPRODUCE.md)

测试覆盖及通过边界见[验证说明](VERIFICATION.zh-CN.md)，硬件型号与连接见
[参考系统](REFERENCE_SYSTEM.zh-CN.md)，准确 Linux 吞吐步骤见
[性能方法](PERFORMANCE.zh-CN.md)。

本指南说明组件与软件。基于锁定公开 Chipyard 的完整数字 SoC 生成见
[SoC 集成](SOC_INTEGRATION.zh-CN.md)。两份指南都不重建完整参考板系统。仓库不提供原理图、PCB、
引脚表、板级约束、厂商IP配置或完整系统镜像。在仓库根目录执行：

```sh
python3 tools/source_headers.py check
python3 tools/test_source_headers.py
python3 tools/smoke.py
python3 tools/test_tcp_integrity.py
python3 tools/check_performance.py
```

预期标记包括 `PUBLIC_SOURCE_CHECK_PASS`、`COPY_PASS`、`RX_MERGE_PASS`、
`AUTO_PHY_POLICY_TEST_PASS hardware_access=0` 和 `PERFORMANCE_RECOMPUTED`。

需要 Python3.10+、C/C++ 编译器；工具不会自动安装依赖。宿主检查验证字节、
对齐、保护页和损坏负控，不等于 RV64 性能或 FPGA 板测。
TCP宿主测试编译C服务器，与Python对端检查双向各1MiB及一字节损坏拒绝，正常输出
`TCP_INTEGRITY_HOST_PASS`。客户端连接localhost，但服务器监听所有本机接口，须在
可信或网络隔离环境执行。

## Chisel与RTL验证

依赖JDK21、Mill1.1.7、Scala2.13.12、Chisel6.5.0、firtool1.75.0、Verilator、
g++和zlib开发头／库，NIC测试另用chiseltest6.0.0、ScalaTest3.2.19。
将mill／verilator／java／g++加入PATH，CHISEL_FIRTOOL_PATH设为准确firtool所在目录。
MAC脚本接受MILL命令字符串及可选ZLIB_PREFIX（含include和lib）；系统zlib不必设置。
首构建需访问Maven；复用依赖缓存不等于独立冷构建。
已验证的Linux x86-64工具组合为Mill1.1.7使用Zulu JDK21.0.10，命令行Java为
Temurin21.0.11，Verilator5.050、GNU g++16.2.0、zlib1.2.13；Scala／Chisel／firtool
使用前述准确版本。

```sh
(cd nic && mill -i core.test)
(cd nic && mill -i core.runMain opennic.EmitOpenNic build/generated-nic)
(cd nic && mill -i core.runMain opennic.EmitOpenNicDma build/generated-dma)
(cd mac && python3 tools/verify.py --out build/core-test --jobs 4)
(cd buffer && python3 tools/verify.py --out build/regression --jobs 4)
(cd rgmii && mill -i mac.runMain openeth.mac.EmitRgmiiDigital build/generated)
python3 tools/source_headers.py stamp-generated nic/build/generated-nic
python3 tools/source_headers.py stamp-generated nic/build/generated-dma
python3 tools/source_headers.py stamp-generated rgmii/build/generated
```

MAC 与 RGMII 使用相同 Scala 包名/类名，须在各自模块目录单独构建。
输出目录必须新建。生成后 stamp-generated 保留工具注释、幂等加头，不改随附
rtl 文件，也不保证重新生成逐字节相同。缺依赖应报失败/未执行。

NIC完整生成入口为`opennic.EmitOpenNic`，独立DMA入口为`opennic.EmitOpenNicDma`，
分别输出到新建的`build/generated-nic`和`build/generated-dma`目录。
完整NIC输出包含描述符／完成队列、DMA和推断存储；作为完整编译单元使用，不能
再编入另一份同名模块。默认NIC为32项队列／2048字节帧，单元测试也使用较小队列。
随附rtl/nic/OpenNic.sv与OpenNicDma.sv分别是完整NIC与独立DMA编译单元，不能一起
编译以免同名模块重复；生成命令及输入摘要见rtl/nic/GENERATED.json。
MAC须有`MAC_CORE_PASS`、`MAC_PRODUCT_CLOCK_PASS`且两个错误负控被拒绝；
缓存须12个种子用例、4个错误负控和导出封装静态检查均成功，结果见`completion.json`。

集成时每个模块只能选一份实现，不能把仓库所有.sv一起编译，rtl目录与组件源码目录
存在同名模块的不同副本。

| 编译目标 | 所需组件源 |
| --- | --- |
| 原生NIC | 单独完整OpenNic.sv；外部接口为CSR、内存及报文流 |
| 64位帧流MAC | mac/rtl/OpenEthMac.sv、生成的MacGmiiCore.sv、buffer/rtl全部四文件 |
| RGMII I/O | 生成的RgmiiDigital.sv、rgmii/rtl两文件、third_party/verilog-ethernet三封装；Xilinx模式需厂商原语 |

nic/integration/OpenEthBoardLink.sv连接MAC／帧缓存与RGMII，暴露64位流，虽名含Board，
却不含板引脚表、CPU、内存控制器或时钟发生器。NIC 的 CSR／主存连接由
[SoC 集成](SOC_INTEGRATION.zh-CN.md)提供。
随附rtl副本用于所述固定配置，重新生成组件不等于生成完整SoC。

`nic/platform` 和相应测试依赖 CDE 与 Rocket Diplomacy／TileLink，不属于独立
core Mill 入口。[SoC 集成配方](SOC_INTEGRATION.zh-CN.md)锁定公开 Chipyard
提交，并提供获取／覆盖层、Config、总线和中断绑定、生成及输出检查。TileLink
适配器从接入总线获得合法地址范围。独立核心测试不需要这些适配器；开发板仍需
合法厂商 IP、时钟／复位及实际约束，**数字 SoC 配方不能重建完整板镜像**。

RGMII数字生成不等于FPGA I/O原语仿真。`rgmii/sim/rgmii_1g_test.sv`需合法
Vivado2025.2 UNISIM库、third_party中的三个封装、RgmiiDigital及rgmii/rtl两模块。
正常标记为`RGMII_1G_PASS tx_pairs=1025 rx_pairs=1025 pair_reset_cases=3`，
WRONG_BYTE／WRONG_CTL／UNSHIFTED_CLOCK三个负控各须FAIL而非PASS。
这是功能仿真，不是引脚、PCB 或 PVT 时序证明，仓库不随附或仿冒厂商库。

```sh
python3 tools/test_rgmii_io.py --vivado /path/to/Vivado/2025.2 \
  --out build/rgmii-io
```

替换为合法Vivado2025.2安装目录；入口运行xvlog／xelab／xsim，保留日志并写
completion.json。须status为0、正常标记存在且三个负控全部正确拒绝，不综合或烧录板卡。

## 软件程序与原生流回环

<a id="linux-programs-cn"></a>

### Linux 程序

在仓库根目录执行以下命令：

```sh
mkdir -p build/apps
cc -O2 -Wall -Wextra software/linux/auto_phy.c -o build/apps/auto_phy
cc -O2 -Wall -Wextra software/linux/tcp_integrity.c -o build/apps/tcp_integrity
build/apps/auto_phy --self-test
```

上述命令分别编译
`software/linux/auto_phy.c`和`software/linux/tcp_integrity.c`，输出到
`build/apps/auto_phy`及`build/apps/tcp_integrity`。只有`auto_phy --self-test`
不访问硬件。RV64版本将cc替换为GCC13.2.0的
`riscv64-unknown-linux-gnu-gcc -static`；目标ABI／C库须匹配，宿主程序不可在RV64运行。

在已正确集成的目标Linux运行`./tcp_integrity 5002`，对端执行：

```sh
python3 tools/tcp_integrity_peer.py --host TARGET_IP --port 5002 \
  --bytes 268435456 --out build/integrity.json
```

TARGET_IP替换为可达目标地址，仅在可信测试网络开放该端口。服务器先收并检查
确定性模式，再发另一模式；两方向须零错，客户端退出0并记录一致的接收／预期SHA。
每次重启这个单连接服务器；负控另用新输出文件，加`--bytes 1048576 --corrupt`，
客户端须报告目标恰好一字节不匹配、服务器须退出10，这是正确拒绝损坏而非正常传输通过。

<a id="bare-metal-program-cn"></a>

### 裸机程序

裸机`loopback.c`测试的是不经MAC／PHY／网线的**原生流直接回环**。示例要求RV64
机器模式、0x80000000起的一致性DMA内存、NIC CSR0x10016000、SiFive兼容UART
0x10020000、PLIC0x0c000000且NIC中断为机器模式上下文0的3／4号。链接文件把程序、
缓冲和栈放入该示例内存。[SoC 配置](SOC_INTEGRATION.zh-CN.md)提供这套数字地址／
中断布局；其他布局仍须适配。编译程序或生成 SoC 不等于执行程序。
执行：

```sh
make -C nic/software/baremetal CROSS_COMPILE=riscv64-unknown-elf-
```


使用支持rv64imac_zicsr／lp64的GCC13.2.0，得到build/loopback.elf而非板启动镜像。
目标上成功标记`OPENNIC_SOC_PASS frames=256 bytecheck=1 guards=1 irq=1`且tohost=1；
失败为OPENNIC_SOC_FAIL及tohost非零错误编码。1–2048字节的原生流并非全部合法线上帧。

## Linux驱动构建与配置

Linux 模块必须针对匹配配置、生成头和符号版本的 RV64 Linux6.6 构建，KERNEL_BUILD
指向外部内核构建目录。make 仅编译，不安装；insmod 示例须在正确接入 OpenNIC 的
平台上使用，不能盲卸载正在工作的网卡。四个拷贝参数默认关闭，复现实测配置须全部
开启：

```sh
make -C "$KERNEL_BUILD" M="$PWD/software/linux/v11" ARCH=riscv \
  CROSS_COMPILE=riscv64-linux-gnu- modules
# 下面命令仅在相应目标Linux执行，而非宿主构建机
insmod opennic.ko wide_copy=1 fix_rx_align=1 merge_copy=1 merge_rx_copy=1
```

驱动在 Linux 套接字报文缓冲（`skb`）与 SoC 主存中的一致性 DMA 缓冲之间拷贝。

| 参数 | 开启时的行为 |
| --- | --- |
| wide_copy | DMA侧对齐64位访问，报文缓冲侧按字节安全处理 |
| fix_rx_align | 避免重复预留napi_alloc_skb已保留的报文缓冲对齐空间 |
| merge_copy | TX线性报文宽拷贝时，把对齐的缓存源字合并为DMA缓冲字 |
| merge_rx_copy | RX宽拷贝时，把DMA字合并为对齐的缓存报文缓冲写入 |

两个合并参数须同时启用 `wide_copy` 才会使用相应路径，
不代表零拷贝或硬件报文卸载。本仓提供驱动源码，不提供完整根文件系统或内核镜像，目标系统须加载与其
内核匹配构建的模块。

参考 MTU1500/千兆全双工。auto_phy --inspect与--configure均会写管理寄存器
（包括引脚方向／PHY页选择），不能当通用
自检；先核板卡连线、PHY 身份、电压和时序。只有 --self-test 不访问硬件。

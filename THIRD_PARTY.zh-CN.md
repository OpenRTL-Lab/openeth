# 许可与来源

[English](THIRD_PARTY.md)

根 MIT 许可覆盖原创 OpenETH 文件；OpenNIC 原声明另存于 licenses/OpenNIC-MIT.txt。
文件头不表示版权转让。[来源清单](SOURCE_MANIFEST.json)记录纳入索引的实现文件
路径、许可、正文与交付文件SHA-256。[交付覆盖清单](PUBLIC_FILES.json)还覆盖
文档和辅助脚本；为避免循环摘要，该覆盖清单及其许可伴随文件不索引自身。

| 随附依赖 | 上游／准确版本 | 许可与处理 |
| --- | --- | --- |
| third_party/verilog-ethernet中的ssio_ddr_in.v、iddr.v、oddr.v | [alexforencich/verilog-ethernet](https://github.com/alexforencich/verilog-ethernet)，77320a9471d19c7dd383914bc049e02d9f4f1ffb | MIT，完整原版权与授权头保持，它们不是DDR内存控制器 |
| integration/chipyard/eth中的ETH.scala、MdioController.scala | Chipyard管理接入，外部Chipyard基线69eba860a352343e4ac6b6df0f3638a79a86ec78 | 保留[BSD-3-Clause](licenses/Chipyard-BSD-3-Clause.txt)，不改标MIT |

Chisel6.5.0、CIRCT/firtool1.75.0、Mill1.1.7、Scala2.13.12、chiseltest6.0.0、
ScalaTest3.2.19、Verilator/zlib、Chipyard/Rocket、Linux6.6和
FPGA 厂商原语是外部工具或依赖，不因本项目 MIT 政策变成原创 MIT 文件。
生成文件保留CIRCT注释，项目实现与工具许可分别对待。
驱动保留 SPDX MIT 与 Dual MIT/GPL 模块声明，不替内核重新许可。

完整Chipyard/Rocket/Linux/OpenSBI源码、厂商DDR/MMCM IP及工具不随附，
依赖须按各自许可获取。MIT 商用权利
与可选商业定制服务无关。每个文件的来源与许可分类可用来源检查器机械核验。

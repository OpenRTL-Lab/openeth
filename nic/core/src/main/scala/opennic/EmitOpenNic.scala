// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: nic/core/src/main/scala/opennic/EmitOpenNic.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
package opennic

import circt.stage.ChiselStage

object EmitOpenNic extends App {
  require(args.length == 1, "需要指定新生成目录")
  ChiselStage.emitSystemVerilogFile(new OpenNic(), Array("--target-dir", args(0)),
    Array("--disable-all-randomization", "--strip-debug-info"))
}

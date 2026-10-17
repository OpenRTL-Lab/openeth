// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: rgmii/rtl/OpenEthRgmiiPairs.sv
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
// 千兆采样对边界；不含器件原语、时钟切换或帧存储。
module OpenEthRgmiiPairs (
    input wire tx_clk, rx_clk, reset_n,
    input wire [7:0] tx_data,
    input wire tx_enable, tx_error,
    output wire [3:0] tx_rise, tx_fall,
    output wire tx_ctl_rise, tx_ctl_fall,
    input wire [3:0] rx_rise, rx_fall,
    input wire rx_ctl_rise, rx_ctl_fall,
    output wire [7:0] rx_data,
    output wire rx_enable, rx_error,
    output wire tx_reset_n, rx_reset_n
);
    (* ASYNC_REG="TRUE" *) reg [1:0] tx_up, rx_up;
    always @(posedge tx_clk or negedge reset_n)
        if (!reset_n) tx_up <= 2'b00;
        else tx_up <= {tx_up[0], 1'b1};
    always @(posedge rx_clk or negedge reset_n)
        if (!reset_n) rx_up <= 2'b00;
        else rx_up <= {rx_up[0], 1'b1};
    assign tx_reset_n = tx_up[1];
    assign rx_reset_n = rx_up[1];

    // 普通同步业务状态；停钟旧值由异步清零的本域资格屏蔽。
    reg rx_armed;
    always @(posedge rx_clk)
        if (!rx_up[1]) rx_armed <= 1'b0;
        else if (!rx_ctl_rise) rx_armed <= 1'b1;
    wire rx_live = rx_up[1] && rx_armed;
    wire [7:0] decoded_data;
    wire decoded_enable, decoded_error;
    assign rx_data = rx_live ? decoded_data : 8'b0;
    assign rx_enable = rx_live && decoded_enable;
    assign rx_error = rx_live && decoded_error;

    // 原创组合编解码分别实例化；clock/reset为生成器冗余端，无内部时序状态。
    RgmiiDigital tx_codec (
        .clock(tx_clk), .reset(1'b0),
        .io_txData(tx_up[1] ? tx_data : 8'b0),
        .io_txEnable(tx_up[1] && tx_enable), .io_txError(tx_up[1] && tx_error),
        .io_txRise(tx_rise), .io_txFall(tx_fall),
        .io_txCtlRise(tx_ctl_rise), .io_txCtlFall(tx_ctl_fall),
        .io_rxRise(4'b0), .io_rxFall(4'b0), .io_rxCtlRise(1'b0), .io_rxCtlFall(1'b0),
        .io_rxData(), .io_rxEnable(), .io_rxError()
    );
    RgmiiDigital rx_codec (
        .clock(rx_clk), .reset(1'b0),
        .io_txData(8'b0), .io_txEnable(1'b0), .io_txError(1'b0),
        .io_txRise(), .io_txFall(), .io_txCtlRise(), .io_txCtlFall(),
        .io_rxRise(rx_rise), .io_rxFall(rx_fall),
        .io_rxCtlRise(rx_ctl_rise), .io_rxCtlFall(rx_ctl_fall),
        .io_rxData(decoded_data), .io_rxEnable(decoded_enable), .io_rxError(decoded_error)
    );
endmodule

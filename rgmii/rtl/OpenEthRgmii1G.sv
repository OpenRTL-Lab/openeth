// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: rgmii/rtl/OpenEthRgmii1G.sv
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
// 固定千兆平台连接；三份原MIT器件封装为外部索引依赖，不包含rgmii_phy_if。
module OpenEthRgmii1G #(
    parameter TARGET="XILINX"
) (
    input wire clk, clk90, rst,
    output wire mac_gmii_tx_clk, mac_gmii_tx_rst, mac_gmii_tx_clk_en,
    input wire [7:0] mac_gmii_txd,
    input wire mac_gmii_tx_en, mac_gmii_tx_er,
    output wire mac_gmii_rx_clk, mac_gmii_rx_rst,
    output wire [7:0] mac_gmii_rxd,
    output wire mac_gmii_rx_dv, mac_gmii_rx_er,
    input wire phy_rgmii_rx_clk,
    input wire [3:0] phy_rgmii_rxd,
    input wire phy_rgmii_rx_ctl,
    output wire phy_rgmii_tx_clk,
    output wire [3:0] phy_rgmii_txd,
    output wire phy_rgmii_tx_ctl
);
    wire [4:0] sampled_rise, sampled_fall;
    wire [3:0] tx_rise, tx_fall;
    wire tx_ctl_rise, tx_ctl_fall;
    wire tx_up, rx_up;
    assign mac_gmii_tx_clk = clk;
    assign mac_gmii_tx_clk_en = 1'b1;
    assign mac_gmii_tx_rst = !tx_up;
    assign mac_gmii_rx_rst = !rx_up;
    ssio_ddr_in #(.TARGET(TARGET), .IODDR_STYLE("IODDR"),
        .CLOCK_INPUT_STYLE("BUFG"), .WIDTH(5)) rx_io (
        .input_clk(phy_rgmii_rx_clk), .input_d({phy_rgmii_rx_ctl, phy_rgmii_rxd}),
        .output_clk(mac_gmii_rx_clk), .output_q1(sampled_rise), .output_q2(sampled_fall)
    );
    OpenEthRgmiiPairs digital (
        .tx_clk(clk), .rx_clk(mac_gmii_rx_clk), .reset_n(!rst),
        .tx_data(mac_gmii_txd), .tx_enable(mac_gmii_tx_en), .tx_error(mac_gmii_tx_er),
        .tx_rise(tx_rise), .tx_fall(tx_fall), .tx_ctl_rise(tx_ctl_rise), .tx_ctl_fall(tx_ctl_fall),
        .rx_rise(sampled_rise[3:0]), .rx_fall(sampled_fall[3:0]),
        .rx_ctl_rise(sampled_rise[4]), .rx_ctl_fall(sampled_fall[4]),
        .rx_data(mac_gmii_rxd), .rx_enable(mac_gmii_rx_dv), .rx_error(mac_gmii_rx_er),
        .tx_reset_n(tx_up), .rx_reset_n(rx_up)
    );
    // TXC已有+90度源，数据使用未移相源。DDR端保持平台原语的原始时序。
    oddr #(.TARGET(TARGET), .IODDR_STYLE("IODDR"), .WIDTH(5)) tx_io (
        .clk(clk), .d1({tx_ctl_rise, tx_rise}), .d2({tx_ctl_fall, tx_fall}),
        .q({phy_rgmii_tx_ctl, phy_rgmii_txd})
    );
    oddr #(.TARGET(TARGET), .IODDR_STYLE("IODDR"), .WIDTH(1)) tx_clock_io (
        .clk(clk90), .d1(1'b1), .d2(1'b0), .q(phy_rgmii_tx_clk)
    );
endmodule

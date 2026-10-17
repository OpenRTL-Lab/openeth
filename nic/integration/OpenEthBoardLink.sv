// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: nic/integration/OpenEthBoardLink.sv
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
// 原创千兆通用适配；三份MIT平台DDR封装是独立索引依赖。
module OpenEthBoardLink #(
    parameter TARGET = "XILINX"
) (
    input wire nic_clk, gtx_clk, gtx_clk90, sys_reset, gtx_reset,
    input wire tx_valid, output wire tx_ready,
    input wire [63:0] tx_data, input wire [7:0] tx_keep,
    input wire tx_last, tx_error,
    output wire rx_valid, input wire rx_ready,
    output wire [63:0] rx_data, output wire [7:0] rx_keep,
    output wire rx_last, rx_error,
    input wire phy_rxc, input wire [3:0] phy_rxd, input wire phy_rx_ctrl,
    output wire phy_txc, output wire [3:0] phy_txd, output wire phy_tx_ctrl,
    output wire [1:0] speed,
    output wire tx_activity_nic, rx_activity_nic,
    output wire tx_overflow_nic, rx_overflow_nic
);
    wire tx_clk, rx_clk, tx_rst, rx_rst;
    wire [7:0] gmii_txd, gmii_rxd;
    wire gmii_tx_en, gmii_tx_er, gmii_rx_dv, gmii_rx_er;
    wire common_reset = sys_reset | gtx_reset;
    wire rx_overflow;
    assign speed = 2'b10; // 本产品只支持千兆；不是自动测速结果。
    OpenEthRgmii1G #(.TARGET(TARGET)) physical_io (
        .clk(gtx_clk), .clk90(gtx_clk90), .rst(common_reset),
        .mac_gmii_rx_clk(rx_clk), .mac_gmii_rx_rst(rx_rst),
        .mac_gmii_rxd(gmii_rxd), .mac_gmii_rx_dv(gmii_rx_dv), .mac_gmii_rx_er(gmii_rx_er),
        .mac_gmii_tx_clk(tx_clk), .mac_gmii_tx_rst(tx_rst), .mac_gmii_tx_clk_en(),
        .mac_gmii_txd(gmii_txd), .mac_gmii_tx_en(gmii_tx_en), .mac_gmii_tx_er(gmii_tx_er),
        .phy_rgmii_rx_clk(phy_rxc), .phy_rgmii_rxd(phy_rxd), .phy_rgmii_rx_ctl(phy_rx_ctrl),
        .phy_rgmii_tx_clk(phy_txc), .phy_rgmii_txd(phy_txd), .phy_rgmii_tx_ctl(phy_tx_ctrl)
    );
    OpenEthMac product (
        .nic_clk(nic_clk), .tx_clk(tx_clk), .rx_clk(rx_clk),
        .nic_reset_n(!common_reset), .tx_reset_n(!(common_reset | tx_rst)),
        .rx_reset_n(!(common_reset | rx_rst)),
        .tx_valid(tx_valid), .tx_ready(tx_ready), .tx_data(tx_data),
        .tx_keep(tx_keep), .tx_last(tx_last), .tx_error(tx_error),
        .rx_valid(rx_valid), .rx_ready(rx_ready), .rx_data(rx_data),
        .rx_keep(rx_keep), .rx_last(rx_last), .rx_error(rx_error),
        .gmii_txd(gmii_txd), .gmii_tx_en(gmii_tx_en), .gmii_tx_er(gmii_tx_er),
        .gmii_rxd(gmii_rxd), .gmii_rx_dv(gmii_rx_dv), .gmii_rx_er(gmii_rx_er),
        .tx_done(), .tx_abort(), .tx_underrun(), .rx_good(), .rx_bad(),
        .tx_commit(), .tx_drop(), .tx_overflow(tx_overflow_nic), .tx_bad(), .tx_long(),
        .rx_commit(), .rx_drop(), .rx_overflow(rx_overflow), .rx_bad_frame(), .rx_long()
    );
    // LED活动取NIC侧整帧交付；不是线端完成，也不是精确统计计数器。
    assign tx_activity_nic = tx_valid & tx_ready & tx_last;
    assign rx_activity_nic = rx_valid & rx_ready & rx_last;
    OpenEthBoardOverflowStatus overflow_status (
        .nic_clk(nic_clk), .rx_clk(rx_clk), .reset_n(!common_reset),
        .rx_overflow(rx_overflow), .overflow_nic(rx_overflow_nic)
    );
endmodule

// Only LED status crosses here; never use this cancellation as a DMA flush.
module OpenEthBoardOverflowStatus (
    input wire nic_clk, rx_clk, reset_n, rx_overflow,
    output wire overflow_nic
);
    (* ASYNC_REG = "TRUE" *) reg [1:0] rx_up, nic_up;
    always @(posedge rx_clk or negedge reset_n) begin
        if (!reset_n) rx_up <= 2'b00;
        else rx_up <= {rx_up[0], 1'b1};
    end
    always @(posedge nic_clk or negedge reset_n) begin
        if (!reset_n) nic_up <= 2'b00;
        else nic_up <= {nic_up[0], 1'b1};
    end
    reg sticky;
    (* ASYNC_REG = "TRUE" *) reg meta, sync_status;
    always @(posedge rx_clk or negedge rx_up[1]) begin
        if (!rx_up[1]) sticky <= 1'b0;
        else if (rx_overflow) sticky <= 1'b1;
    end
    always @(posedge nic_clk or negedge nic_up[1]) begin
        if (!nic_up[1]) begin
            meta <= 1'b0;
            sync_status <= 1'b0;
        end else begin
            meta <= sticky;
            sync_status <= meta;
        end
    end
    assign overflow_nic = nic_up[1] & sync_status;
endmodule

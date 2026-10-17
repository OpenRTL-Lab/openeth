// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: mac/rtl/OpenEthMac.sv
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
// 与指定MAX_FRAME_BYTES生成的MacGmiiCore共同使用；不复制ETH-01实现。
module OpenEthMac #(
    parameter int SLOTS=4, MAX_FRAME_BYTES=2048
) (
    input logic nic_clk, tx_clk, rx_clk,
    input logic nic_reset_n, tx_reset_n, rx_reset_n,
    input logic tx_valid, output logic tx_ready,
    input logic [63:0] tx_data, input logic [7:0] tx_keep,
    input logic tx_last, tx_error,
    output logic rx_valid, input logic rx_ready,
    output logic [63:0] rx_data, output logic [7:0] rx_keep,
    output logic rx_last, rx_error,
    output logic [7:0] gmii_txd, output logic gmii_tx_en, gmii_tx_er,
    input logic [7:0] gmii_rxd, input logic gmii_rx_dv, gmii_rx_er,
    output logic tx_done, tx_abort, tx_underrun, rx_good, rx_bad,
    output logic tx_commit, tx_drop, tx_overflow, tx_bad, tx_long,
    output logic rx_commit, rx_drop, rx_overflow, rx_bad_frame, rx_long
);
    wire cancel_n=nic_reset_n && tx_reset_n && rx_reset_n;
    (* ASYNC_REG="TRUE" *) logic [1:0] tx_up, rx_up;
    always_ff @(posedge tx_clk or negedge cancel_n)
        if (!cancel_n) tx_up<=0; else tx_up<={tx_up[0],1'b1};
    always_ff @(posedge rx_clk or negedge cancel_n)
        if (!cancel_n) rx_up<=0; else rx_up<={rx_up[0],1'b1};
    logic rx_armed;
    // live承担停钟时立即关门；armed仅为本域同步状态，释放前两沿清零。
    always_ff @(posedge rx_clk)
        if (!rx_up[1]) rx_armed<=0;
        else if (!gmii_rx_dv) rx_armed<=1;

    logic tx_byte_valid, tx_byte_ready, tx_byte_last, tx_byte_error;
    logic [7:0] tx_byte_data;
    logic rx_byte_valid, rx_byte_ready, rx_byte_last, rx_byte_error;
    logic [7:0] rx_byte_data;
    logic raw_tx_ready, raw_tx_en, raw_tx_er, raw_tx_done, raw_tx_abort, raw_tx_underrun;
    logic [7:0] raw_txd;
    logic raw_rx_valid, raw_rx_good, raw_rx_bad;
    wire tx_live=tx_up[1];
    wire rx_live=rx_up[1] && rx_armed;
    assign gmii_txd=tx_live?raw_txd:8'b0;
    assign gmii_tx_en=tx_live && raw_tx_en;
    assign gmii_tx_er=tx_live && raw_tx_er;
    assign tx_done=tx_live && raw_tx_done;
    assign tx_abort=tx_live && raw_tx_abort;
    assign tx_underrun=tx_live && raw_tx_underrun;
    assign tx_byte_ready=tx_live && raw_tx_ready;
    assign rx_byte_valid=rx_live && raw_rx_valid;
    assign rx_good=rx_live && raw_rx_good;
    assign rx_bad=rx_live && raw_rx_bad;
    openeth_tx_cdc #(.SLOTS(SLOTS),.MAX_FRAME_BYTES(MAX_FRAME_BYTES)) tx_frames (
        .nic_clk,.mac_clk(tx_clk),.nic_reset_n(cancel_n),.mac_reset_n(cancel_n),
        .s_valid(tx_valid),.s_ready(tx_ready),.s_data(tx_data),.s_keep(tx_keep),.s_last(tx_last),.s_error(tx_error),
        .m_valid(tx_byte_valid),.m_ready(tx_byte_ready),.m_data(tx_byte_data),.m_last(tx_byte_last),.m_error(tx_byte_error),
        .frame_committed(tx_commit),.frame_dropped(tx_drop),.overflow(tx_overflow),.bad_frame(tx_bad),.oversize(tx_long));
    openeth_rx_cdc #(.SLOTS(SLOTS),.MAX_FRAME_BYTES(MAX_FRAME_BYTES),.DROP_BAD(1)) rx_frames (
        .nic_clk,.mac_clk(rx_clk),.nic_reset_n(cancel_n),.mac_reset_n(cancel_n),
        .s_valid(rx_byte_valid),.s_ready(rx_byte_ready),.s_data(rx_byte_data),.s_last(rx_byte_last),.s_error(rx_byte_error),
        .m_valid(rx_valid),.m_ready(rx_ready),.m_data(rx_data),.m_keep(rx_keep),.m_last(rx_last),.m_error(rx_error),
        .frame_committed(rx_commit),.frame_dropped(rx_drop),.overflow(rx_overflow),.bad_frame(rx_bad_frame),.oversize(rx_long));
    MacGmiiCore mac (
        .io_txClock(tx_clk),.io_txReset(!tx_up[1]),.io_rxClock(rx_clk),.io_rxReset(!rx_up[1]),
        .io_txIn_valid(tx_byte_valid && tx_live),.io_txIn_ready(raw_tx_ready),
        .io_txIn_bits_data(tx_byte_data),.io_txIn_bits_last(tx_byte_last),.io_txIn_bits_error(tx_byte_error),
        .io_txData(raw_txd),.io_txEnable(raw_tx_en),.io_txError(raw_tx_er),
        .io_txDone(raw_tx_done),.io_txAbort(raw_tx_abort),.io_txUnderrun(raw_tx_underrun),
        .io_rxData(gmii_rxd),.io_rxEnable(gmii_rx_dv && rx_live),.io_rxError(gmii_rx_er),
        .io_rxOut_valid(raw_rx_valid),.io_rxOut_bits_data(rx_byte_data),
        .io_rxOut_bits_last(rx_byte_last),.io_rxOut_bits_error(rx_byte_error),
        .io_rxGood(raw_rx_good),.io_rxBad(raw_rx_bad));

    // RX为不可背压线路流；合法运行时真实缓存必须能接纳或整帧排空。
    always @(posedge rx_clk) begin
        if (rx_up[1] && rx_byte_valid)
            assert (rx_byte_ready) else $error("RX cache attempted to backpressure live MAC");
    end
endmodule

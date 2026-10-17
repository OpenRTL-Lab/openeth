// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: buffer/sim/byte_test_top.sv
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
// Exercise the reusable byte-width primitive separately from the wide product.
module byte_test_top #(
    parameter int SLOTS=4, MAX_FRAME_BYTES=64,
    parameter bit RX_DROP_BAD=1
) (
    input logic nic_clk, mac_clk, nic_reset_n, mac_reset_n,
    input logic tx_s_valid, output logic tx_s_ready,
    input logic [63:0] tx_s_data, input logic [7:0] tx_s_keep, input logic tx_s_last, tx_s_error,
    output logic tx_m_valid, input logic tx_m_ready,
    output logic [7:0] tx_m_data, output logic tx_m_last, tx_m_error,
    output logic tx_commit, tx_drop, tx_overflow, tx_bad, tx_long,
    // In this DROP_WHEN_FULL primitive, ready aliases the local release bit.
    /* verilator lint_off SYNCASYNCNET */
    input logic rx_s_valid, output logic rx_s_ready,
    /* verilator lint_on SYNCASYNCNET */
    input logic [7:0] rx_s_data, input logic rx_s_last, rx_s_error,
    output logic rx_m_valid, input logic rx_m_ready,
    output logic [63:0] rx_m_data, output logic [7:0] rx_m_keep, output logic rx_m_last, rx_m_error,
    output logic rx_commit, rx_drop, rx_overflow, rx_bad, rx_long
);
    logic tv,tr,tl,te,rv,rr,rl,re;
    logic [7:0] td,rd;
    logic unused_twa, unused_rra, unused_tra, unused_rwa, unused_tkeep, unused_rkeep;
    openeth_stream_down down (.clk(nic_clk),.reset_n(nic_reset_n&&mac_reset_n),
        .s_valid(tx_s_valid),.s_ready(tx_s_ready),.s_data(tx_s_data),.s_keep(tx_s_keep),.s_last(tx_s_last),.s_error(tx_s_error),
        .m_valid(tv),.m_ready(tr),.m_data(td),.m_last(tl),.m_error(te));
    openeth_frame_fifo #(.SLOTS(SLOTS),.MAX_FRAME_BYTES(MAX_FRAME_BYTES),.DATA_BYTES(1),.DROP_WHEN_FULL(0),.DROP_BAD(1)) tx (
        .wr_clk(nic_clk),.rd_clk(mac_clk),.wr_reset_n(nic_reset_n),.rd_reset_n(mac_reset_n),
        .s_valid(tv),.s_ready(tr),.s_data(td),.s_keep(1'b1),.s_last(tl),.s_error(te),
        .m_valid(tx_m_valid),.m_ready(tx_m_ready),.m_data(tx_m_data),.m_keep(unused_tkeep),.m_last(tx_m_last),.m_error(tx_m_error),
        .frame_committed(tx_commit),.frame_dropped(tx_drop),.overflow(tx_overflow),.bad_frame(tx_bad),.oversize(tx_long),.wr_active(unused_twa),.rd_active(unused_tra));
    openeth_frame_fifo #(.SLOTS(SLOTS),.MAX_FRAME_BYTES(MAX_FRAME_BYTES),.DATA_BYTES(1),.DROP_WHEN_FULL(1),.DROP_BAD(RX_DROP_BAD)) rx (
        .wr_clk(mac_clk),.rd_clk(nic_clk),.wr_reset_n(mac_reset_n),.rd_reset_n(nic_reset_n),
        .s_valid(rx_s_valid),.s_ready(rx_s_ready),.s_data(rx_s_data),.s_keep(1'b1),.s_last(rx_s_last),.s_error(rx_s_error),
        .m_valid(rv),.m_ready(rr),.m_data(rd),.m_keep(unused_rkeep),.m_last(rl),.m_error(re),
        .frame_committed(rx_commit),.frame_dropped(rx_drop),.overflow(rx_overflow),.bad_frame(rx_bad),.oversize(rx_long),.wr_active(unused_rwa),.rd_active(unused_rra));
    openeth_stream_up up (.clk(nic_clk),.reset_n(nic_reset_n&&mac_reset_n),
        .s_valid(rv),.s_ready(rr),.s_data(rd),.s_last(rl),.s_error(re),
        .m_valid(rx_m_valid),.m_ready(rx_m_ready),.m_data(rx_m_data),.m_keep(rx_m_keep),.m_last(rx_m_last),.m_error(rx_m_error));
endmodule

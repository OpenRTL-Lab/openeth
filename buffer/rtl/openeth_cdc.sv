// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: buffer/rtl/openeth_cdc.sv
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
module openeth_tx_cdc #(
    parameter int SLOTS=4, MAX_FRAME_BYTES=2048
) (
    input logic nic_clk, mac_clk, nic_reset_n, mac_reset_n,
    input logic s_valid, output logic s_ready,
    input logic [63:0] s_data, input logic [7:0] s_keep, input logic s_last, s_error,
    output logic m_valid, input logic m_ready,
    output logic [7:0] m_data, output logic m_last, m_error,
    output logic frame_committed, frame_dropped, overflow, bad_frame, oversize
);
    logic byte_valid, byte_ready, byte_last, byte_error;
    logic [63:0] byte_data;
    logic [7:0] byte_keep;
    logic unused_wr_active, unused_rd_active;
    wire converter_reset_n=nic_reset_n && mac_reset_n;
    openeth_stream_down down (.clk(mac_clk),.reset_n(converter_reset_n),
        .s_valid(byte_valid),.s_ready(byte_ready),.s_data(byte_data),.s_keep(byte_keep),.s_last(byte_last),.s_error(byte_error),
        .m_valid,.m_ready,.m_data,.m_last,.m_error);
    openeth_frame_fifo #(.SLOTS(SLOTS),.MAX_FRAME_BYTES(MAX_FRAME_BYTES),.DROP_WHEN_FULL(0),.DROP_BAD(1)) fifo (
        .wr_clk(nic_clk),.rd_clk(mac_clk),.wr_reset_n(nic_reset_n),.rd_reset_n(mac_reset_n),
        .s_valid,.s_ready,.s_data,.s_keep,.s_last,.s_error,
        .m_valid(byte_valid),.m_ready(byte_ready),.m_data(byte_data),.m_keep(byte_keep),.m_last(byte_last),.m_error(byte_error),
        .frame_committed,.frame_dropped,.overflow,.bad_frame,.oversize,.wr_active(unused_wr_active),.rd_active(unused_rd_active));
endmodule

module openeth_rx_cdc #(
    parameter int SLOTS=4, MAX_FRAME_BYTES=2048,
    parameter bit DROP_BAD=1
) (
    input logic mac_clk, nic_clk, mac_reset_n, nic_reset_n,
    input logic s_valid, output logic s_ready,
    input logic [7:0] s_data, input logic s_last, s_error,
    output logic m_valid, input logic m_ready,
    output logic [63:0] m_data, output logic [7:0] m_keep, output logic m_last, m_error,
    output logic frame_committed, frame_dropped, overflow, bad_frame, oversize
);
    logic byte_valid, byte_ready, byte_last, byte_error;
    logic [63:0] byte_data;
    logic [7:0] byte_keep;
    logic unused_wr_active, unused_rd_active;
    wire converter_reset_n=nic_reset_n && mac_reset_n;
    openeth_frame_fifo #(.SLOTS(SLOTS),.MAX_FRAME_BYTES(MAX_FRAME_BYTES),.DROP_WHEN_FULL(1),.DROP_BAD(DROP_BAD)) fifo (
        .wr_clk(mac_clk),.rd_clk(nic_clk),.wr_reset_n(mac_reset_n),.rd_reset_n(nic_reset_n),
        .s_valid(byte_valid),.s_ready(byte_ready),.s_data(byte_data),.s_keep(byte_keep),.s_last(byte_last),.s_error(byte_error),
        .m_valid,.m_ready,.m_data,.m_keep,.m_last,.m_error,
        .frame_committed,.frame_dropped,.overflow,.bad_frame,.oversize,.wr_active(unused_wr_active),.rd_active(unused_rd_active));
    openeth_stream_up up (.clk(mac_clk),.reset_n(converter_reset_n),
        .s_valid,.s_ready,.s_data,.s_last,.s_error,
        .m_valid(byte_valid),.m_ready(byte_ready),.m_data(byte_data),.m_keep(byte_keep),.m_last(byte_last),.m_error(byte_error));
endmodule

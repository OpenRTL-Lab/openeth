// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: nic/integration/OpenNicMac.sv
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
// 仅连接实际NIC和外部固定版本产品；不实现第二套MAC或帧缓存。
module OpenNicMac (
    input logic nic_clk, tx_clk, rx_clk, reset,
    input logic csr_valid, output logic csr_ready,
    input logic [11:0] csr_address, input logic csr_write,
    input logic [31:0] csr_data, input logic [3:0] csr_strobe,
    output logic result_valid, input logic result_ready,
    output logic [31:0] result_data, output logic result_error,
    output logic rd_valid, input logic rd_ready, output logic [63:0] rd_address,
    input logic rd_result_valid, output logic rd_result_ready,
    input logic [63:0] rd_data, input logic rd_error,
    output logic wr_valid, input logic wr_ready,
    output logic [63:0] wr_address, wr_data, output logic [7:0] wr_mask,
    input logic wr_result_valid, output logic wr_result_ready, input logic wr_error,
    output logic [1:0] irq,
    output logic [7:0] gmii_txd, output logic gmii_tx_en, gmii_tx_er,
    input logic [7:0] gmii_rxd, input logic gmii_rx_dv, gmii_rx_er,
    output logic tx_done, tx_abort, tx_underrun, rx_good, rx_bad,
    output logic tx_commit, tx_drop, tx_overflow, tx_bad, tx_long,
    output logic rx_commit, rx_drop, rx_overflow, rx_bad_frame, rx_long
);
    logic tv, tr, tl, te, rv, rr, rl, re;
    logic [63:0] td, rd;
    logic [7:0] tk, rk;
    OpenNic nic (
        .clock(nic_clk), .reset,
        .io_request_valid(csr_valid), .io_request_ready(csr_ready),
        .io_request_bits_address(csr_address), .io_request_bits_write(csr_write),
        .io_request_bits_data(csr_data), .io_request_bits_strobe(csr_strobe),
        .io_response_valid(result_valid), .io_response_ready(result_ready),
        .io_response_bits_data(result_data), .io_response_bits_error(result_error),
        .io_memory_read_valid(rd_valid), .io_memory_read_ready(rd_ready),
        .io_memory_read_bits(rd_address),
        .io_memory_readResult_valid(rd_result_valid), .io_memory_readResult_ready(rd_result_ready),
        .io_memory_readResult_bits_data(rd_data), .io_memory_readResult_bits_error(rd_error),
        .io_memory_write_valid(wr_valid), .io_memory_write_ready(wr_ready),
        .io_memory_write_bits_address(wr_address), .io_memory_write_bits_data(wr_data),
        .io_memory_write_bits_mask(wr_mask),
        .io_memory_writeResult_valid(wr_result_valid), .io_memory_writeResult_ready(wr_result_ready),
        .io_memory_writeResult_bits(wr_error), .io_irq(irq),
        .io_tx_valid(tv), .io_tx_ready(tr), .io_tx_bits_data(td), .io_tx_bits_keep(tk),
        .io_tx_bits_last(tl), .io_tx_bits_error(te),
        .io_rx_valid(rv), .io_rx_ready(rr), .io_rx_bits_data(rd), .io_rx_bits_keep(rk),
        .io_rx_bits_last(rl), .io_rx_bits_error(re)
    );
    OpenEthMac mac (
        .nic_clk, .tx_clk, .rx_clk,
        .nic_reset_n(!reset), .tx_reset_n(!reset), .rx_reset_n(!reset),
        .tx_valid(tv), .tx_ready(tr), .tx_data(td), .tx_keep(tk), .tx_last(tl), .tx_error(te),
        .rx_valid(rv), .rx_ready(rr), .rx_data(rd), .rx_keep(rk), .rx_last(rl), .rx_error(re),
        .gmii_txd, .gmii_tx_en, .gmii_tx_er, .gmii_rxd, .gmii_rx_dv, .gmii_rx_er,
        .tx_done, .tx_abort, .tx_underrun, .rx_good, .rx_bad,
        .tx_commit, .tx_drop, .tx_overflow, .tx_bad, .tx_long,
        .rx_commit, .rx_drop, .rx_overflow, .rx_bad_frame, .rx_long
    );
endmodule

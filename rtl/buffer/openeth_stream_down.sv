// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: rtl/buffer/openeth_stream_down.sv
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
module openeth_stream_down (
    input logic clk, input logic reset_n,
    input logic s_valid, output logic s_ready,
    input logic [63:0] s_data, input logic [7:0] s_keep,
    input logic s_last, s_error,
    output logic m_valid, input logic m_ready,
    output logic [7:0] m_data, output logic m_last, m_error
);
    // Local synchronized release intentionally also qualifies the ready/valid interface.
    /* verilator lint_off SYNCASYNCNET */
    (* ASYNC_REG="TRUE" *) logic [1:0] reset_release;
    /* verilator lint_on SYNCASYNCNET */
    wire local_reset_n=reset_release[1];
    always_ff @(posedge clk or negedge reset_n) begin
        if (!reset_n) reset_release<=0;
        else reset_release<={reset_release[0],1'b1};
    end
    logic occupied;
    logic [63:0] data_q;
    logic [3:0] count_q;
    logic [2:0] index_q;
    logic last_q, error_q;
    logic [3:0] count_in;
    logic keep_ok;
    always_comb begin
        count_in = 1;
        keep_ok = 1'b1;
        case (s_keep)
            8'h01: count_in=1;
            8'h03: count_in=2;
            8'h07: count_in=3;
            8'h0f: count_in=4;
            8'h1f: count_in=5;
            8'h3f: count_in=6;
            8'h7f: count_in=7;
            8'hff: count_in=8;
            default: keep_ok=1'b0;
        endcase
        if (!s_last && s_keep != 8'hff) keep_ok=1'b0;
    end
    assign m_valid=occupied && local_reset_n;
    assign m_data=data_q[8*index_q +: 8];
    assign m_last=last_q && ({1'b0,index_q}+1'b1 == count_q);
    assign m_error=error_q;
    assign s_ready=local_reset_n && (!occupied || (m_ready && ({1'b0,index_q}+1'b1 == count_q)));
    always_ff @(posedge clk or negedge local_reset_n) begin
        if (!local_reset_n) begin
            occupied<=0; data_q<=0; count_q<=0; index_q<=0; last_q<=0; error_q<=0;
        end else begin
            if (m_valid && m_ready) begin
                if ({1'b0,index_q}+1'b1 == count_q) occupied<=0;
                else index_q<=index_q+1'b1;
            end
            if (s_valid && s_ready) begin
                occupied<=1; data_q<=s_data; count_q<=keep_ok?count_in:4'd1;
                index_q<=0; last_q<=s_last; error_q<=s_error || !keep_ok;
            end
        end
    end
endmodule

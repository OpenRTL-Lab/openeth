// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: rtl/buffer/openeth_stream_up.sv
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
module openeth_stream_up (
    input logic clk, input logic reset_n,
    input logic s_valid, output logic s_ready,
    input logic [7:0] s_data, input logic s_last, s_error,
    output logic m_valid, input logic m_ready,
    output logic [63:0] m_data, output logic [7:0] m_keep,
    output logic m_last, m_error
);
    // Raw reset reaches only the two-stage asynchronous-assert release chain.
    // Local synchronized release intentionally also qualifies the ready/valid interface.
    /* verilator lint_off SYNCASYNCNET */
    (* ASYNC_REG="TRUE" *) logic [1:0] reset_release;
    /* verilator lint_on SYNCASYNCNET */
    wire local_reset_n=reset_release[1];
    always_ff @(posedge clk or negedge reset_n) begin
        if (!reset_n) reset_release<=0;
        else reset_release<={reset_release[0],1'b1};
    end
    logic [63:0] collect;
    logic [2:0] count;
    logic collect_error, out_valid;
    logic [63:0] with_byte;
    always_comb begin
        with_byte=collect;
        with_byte[8*count +: 8]=s_data;
    end
    assign m_valid=out_valid && local_reset_n;
    assign s_ready=local_reset_n && (!out_valid || m_ready);
    always_ff @(posedge clk or negedge local_reset_n) begin
        if (!local_reset_n) begin
            collect<=0; count<=0; collect_error<=0;
            out_valid<=0; m_data<=0; m_keep<=0; m_last<=0; m_error<=0;
        end else begin
            if (m_valid && m_ready) out_valid<=0;
            if (s_valid && s_ready) begin
                if (s_last || count == 3'd7) begin
                    out_valid<=1; m_data<=with_byte;
                    m_keep<=8'hff >> (3'd7-count);
                    m_last<=s_last; m_error<=collect_error || s_error;
                    collect<=0; count<=0; collect_error<=0;
                end else begin
                    collect<=with_byte; count<=count+1'b1;
                    collect_error<=collect_error || s_error;
                end
            end
        end
    end
endmodule

// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: rtl/buffer/openeth_frame_fifo.sv
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
// Frame ownership crosses only after data and metadata are fully stored.
module openeth_frame_fifo #(
    parameter int SLOTS=4, MAX_FRAME_BYTES=2048, DATA_BYTES=8,
    parameter bit DROP_WHEN_FULL=0, DROP_BAD=1
) (
    input logic wr_clk, rd_clk, wr_reset_n, rd_reset_n,
    input logic s_valid, output logic s_ready,
    input logic [8*DATA_BYTES-1:0] s_data,
    input logic [DATA_BYTES-1:0] s_keep, input logic s_last, s_error,
    output logic m_valid, input logic m_ready,
    output logic [8*DATA_BYTES-1:0] m_data,
    output logic [DATA_BYTES-1:0] m_keep, output logic m_last, m_error,
    output logic frame_committed, frame_dropped, overflow, bad_frame, oversize,
    output logic wr_active, rd_active
);
    localparam int A=$clog2(SLOTS), P=A+1;
    localparam int L=$clog2(MAX_FRAME_BYTES+DATA_BYTES+1);
    localparam int WORDS=(MAX_FRAME_BYTES+DATA_BYTES-1)/DATA_BYTES;
    localparam int W=$clog2(WORDS+1);
    localparam logic [P-1:0] FULL_XOR=P'((1 << A) | (1 << (A-1)));
    wire reset_n=wr_reset_n && rd_reset_n;
    // Stage 1 intentionally drives both local async clear and same-domain enables.
    // Physical reset-fanout and recovery/removal are checked separately after synthesis.
    /* verilator lint_off SYNCASYNCNET */
    (* async_reg="true" *) logic [1:0] wr_release, rd_release;
    /* verilator lint_on SYNCASYNCNET */
    wire wr_local_reset_n=wr_release[1];
    wire rd_local_reset_n=rd_release[1];
    (* async_reg="true" *) logic [P-1:0] rgray_w1, rgray_w2, wgray_r1, wgray_r2;
    logic [P-1:0] wbin, wgray, rbin, rgray;
    logic [8*DATA_BYTES-1:0] memory [0:SLOTS*WORDS-1];
    // Keep the small ownership metadata bank as registers for explicit per-bit
    // stable-data CDC constraints. This optional synthesis hint adds no primitive.
    (* ram_style="registers" *) logic [W-1:0] word_lengths [0:SLOTS-1];
    (* ram_style="registers" *) logic [DATA_BYTES-1:0] tail_keeps [0:SLOTS-1];
    (* ram_style="registers" *) logic errors [0:SLOTS-1];
    logic [L-1:0] byte_count;
    logic [W-1:0] wposition, rposition, rlength;
    logic [DATA_BYTES-1:0] rkeep;
    logic in_frame, drop_latched, bad_latched, format_latched, long_latched;
    logic reading, read_error, out_valid;
    logic keep_ok;
    integer byte_number;
    always_comb begin
        byte_number=0;
        for (int i=0;i<DATA_BYTES;i++) byte_number+=int'(s_keep[i]);
        keep_ok=(s_keep != 0) && ((s_keep & (s_keep+1'b1)) == 0) && (s_last || (&s_keep));
        if (!keep_ok) byte_number=1;
    end
    wire full=(wgray == (rgray_w2 ^ FULL_XOR));
    wire empty=(rgray == wgray_r2);
    wire full_this=in_frame ? drop_latched : full;
    wire format_this=format_latched || !keep_ok;
    wire bad_this=bad_latched || s_error || !keep_ok;
    wire long_this=long_latched || ((int'(byte_count)+byte_number)>MAX_FRAME_BYTES);
    wire accept=s_valid && s_ready;
    wire write_word=wr_active && accept && !full_this && !long_this && !format_this && int'(wposition)<WORDS;
    wire commit_frame=wr_active && accept && s_last &&
        !(full_this || long_this || format_this || (DROP_BAD && bad_this));
    initial begin
        if (SLOTS<2 || (SLOTS & (SLOTS-1)) != 0) $fatal(1,"SLOTS must be a power of two >=2");
        if (MAX_FRAME_BYTES<1 || (DATA_BYTES!=1 && DATA_BYTES!=8)) $fatal(1,"invalid width/size");
    end
    function automatic [P-1:0] gray(input logic [P-1:0] value);
        return value ^ (value >> 1);
    endfunction
    assign wr_active=wr_release[1];
    assign rd_active=rd_release[1];
    assign s_ready=wr_active && (DROP_WHEN_FULL || in_frame || !full);
    assign m_valid=rd_active && out_valid;
    always_ff @(posedge wr_clk or negedge reset_n) begin
        if (!reset_n) wr_release<=0;
        else wr_release<={wr_release[0],1'b1};
    end
    always_ff @(posedge rd_clk or negedge reset_n) begin
        if (!reset_n) rd_release<=0;
        else rd_release<={rd_release[0],1'b1};
    end
    always_ff @(posedge wr_clk or negedge wr_local_reset_n) begin
        if (!wr_local_reset_n) begin rgray_w1<=0; rgray_w2<=0; end
        else begin rgray_w1<=rgray; rgray_w2<=rgray_w1; end
    end
    always_ff @(posedge rd_clk or negedge rd_local_reset_n) begin
        if (!rd_local_reset_n) begin wgray_r1<=0; wgray_r2<=0; end
        else begin wgray_r1<=wgray; wgray_r2<=wgray_r1; end
    end
    // One synchronous write port, no storage reset or technology primitives.
    always_ff @(posedge wr_clk) begin
        if (write_word) memory[int'(wbin[A-1:0])*WORDS+int'(wposition)]<=s_data;
        // Metadata has no contents reset. Ownership, not reset contents, validates it.
        if (commit_frame) begin
            word_lengths[wbin[A-1:0]]<=wposition+1'b1;
            tail_keeps[wbin[A-1:0]]<=s_keep;
            errors[wbin[A-1:0]]<=bad_this;
        end
    end
    always_ff @(posedge wr_clk or negedge wr_local_reset_n) begin
        if (!wr_local_reset_n) begin
            wbin<=0; wgray<=0; byte_count<=0; wposition<=0; in_frame<=0;
            drop_latched<=0; bad_latched<=0; format_latched<=0; long_latched<=0;
            frame_committed<=0; frame_dropped<=0; overflow<=0; bad_frame<=0; oversize<=0;
        end else begin
            frame_committed<=0; frame_dropped<=0; overflow<=0; bad_frame<=0; oversize<=0;
            if (wr_active && accept) begin
                if (s_last) begin
                    if (full_this || long_this || format_this || (DROP_BAD && bad_this)) frame_dropped<=1;
                    else begin
                        wbin<=wbin+1'b1; wgray<=gray(wbin+1'b1); frame_committed<=1;
                    end
                    overflow<=full_this; bad_frame<=bad_this; oversize<=long_this;
                    byte_count<=0; wposition<=0; in_frame<=0;
                    drop_latched<=0; bad_latched<=0; format_latched<=0; long_latched<=0;
                end else begin
                    in_frame<=1; drop_latched<=full_this;
                    bad_latched<=bad_this; format_latched<=format_this; long_latched<=long_this;
                    if (!long_this) byte_count<=byte_count+L'(byte_number);
                    if (int'(wposition)<WORDS) wposition<=wposition+1'b1;
                end
            end
        end
    end
    // One synchronous read port, stalled output holds its word and metadata.
    always_ff @(posedge rd_clk or negedge rd_local_reset_n) begin
        if (!rd_local_reset_n) begin
            rbin<=0; rgray<=0; rposition<=0; rlength<=0; rkeep<=0;
            reading<=0; read_error<=0; out_valid<=0; m_data<=0; m_keep<=0; m_last<=0; m_error<=0;
        end else if (rd_active) begin
            if (!reading) begin
                if (!empty) begin
                    rlength<=word_lengths[rbin[A-1:0]]; rkeep<=tail_keeps[rbin[A-1:0]];
                    read_error<=errors[rbin[A-1:0]]; rposition<=0; reading<=1;
                end
            end else if (!out_valid || m_ready) begin
                if (out_valid && m_last) begin
                    out_valid<=0; reading<=0; rbin<=rbin+1'b1; rgray<=gray(rbin+1'b1);
                end else begin
                    m_data<=memory[int'(rbin[A-1:0])*WORDS+int'(rposition)];
                    m_keep<=(rposition+1'b1 == rlength)?rkeep:{DATA_BYTES{1'b1}};
                    m_last<=(rposition+1'b1 == rlength); m_error<=read_error && (rposition+1'b1 == rlength);
                    out_valid<=1; rposition<=rposition+1'b1;
                end
            end
        end
    end
`ifndef SYNTHESIS
    logic [P-1:0] prev_wgray, prev_rgray;
    always_ff @(posedge wr_clk or negedge wr_local_reset_n) begin
        if (!wr_local_reset_n) prev_wgray<=0;
        else begin assert ($onehot0(wgray ^ prev_wgray)); prev_wgray<=wgray; end
    end
    always_ff @(posedge rd_clk or negedge rd_local_reset_n) begin
        if (!rd_local_reset_n) prev_rgray<=0;
        else begin assert ($onehot0(rgray ^ prev_rgray)); prev_rgray<=rgray; end
    end
`endif
endmodule

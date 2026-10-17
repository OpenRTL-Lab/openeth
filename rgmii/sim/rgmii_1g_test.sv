// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: rgmii/sim/rgmii_1g_test.sv
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
`timescale 1ns/1ps
module rgmii_1g_test;
    reg clk=0, clk90=0, rxclk=0, tx_run=1, rx_run=1, rst=1;
    always #4 if(tx_run) clk=~clk;
    initial begin #2; forever begin #4; if(tx_run) clk90=~clk90; end end
    initial begin #1; forever begin #4; if(rx_run) rxclk=~rxclk; end end
    reg [7:0] txd=0;
    reg txen=0, txer=0;
    reg [3:0] rxd=0;
    reg rxctl=0;
    wire [3:0] outd;
    wire outclk,outctl,mrxclk,mrxrst,mtxclk,mtxrst,mtick;
    wire [7:0] mrxdata;
    wire mrxdv,mrxer;
    integer tx_cases=0, rx_cases=0, resets=0;
    OpenEthRgmii1G dut (
        .clk(clk), .clk90($test$plusargs("UNSHIFTED_CLOCK") ? clk : clk90), .rst(rst),
        .mac_gmii_tx_clk(mtxclk), .mac_gmii_tx_rst(mtxrst), .mac_gmii_tx_clk_en(mtick),
        .mac_gmii_txd(txd), .mac_gmii_tx_en(txen), .mac_gmii_tx_er(txer),
        .mac_gmii_rx_clk(mrxclk), .mac_gmii_rx_rst(mrxrst),
        .mac_gmii_rxd(mrxdata), .mac_gmii_rx_dv(mrxdv), .mac_gmii_rx_er(mrxer),
        .phy_rgmii_rx_clk(rxclk), .phy_rgmii_rxd(rxd), .phy_rgmii_rx_ctl(rxctl),
        .phy_rgmii_tx_clk(outclk), .phy_rgmii_txd(outd), .phy_rgmii_tx_ctl(outctl)
    );
    task automatic tx_case(input [7:0] d,input bit en,input bit er);
        reg [3:0] low;
        begin
            @(negedge clk); #1; txd=d; txen=en; txer=er;
            low=d[3:0] ^ ($test$plusargs("WRONG_BYTE") ? 4'h1 : 4'h0);
            @(posedge clk); #0.2;
            if(outd !== low || outctl !== en || outclk !== 0 || mtick !== 1)
                $fatal(1,"RGMII_1G_FAIL TX rise data/control/phase");
            @(posedge clk90); #0.2;
            if(outclk !== 1 || outd !== low || outctl !== en)
                $fatal(1,"RGMII_1G_FAIL TX forwarded rising clock");
            @(negedge clk); #0.2;
            if(outd !== d[7:4] || outctl !== ((en^er)^$test$plusargs("WRONG_CTL")) || outclk !== 1)
                $fatal(1,"RGMII_1G_FAIL TX falling data/control");
            @(negedge clk90); #0.2;
            if(outclk !== 0 || outd !== d[7:4]) $fatal(1,"RGMII_1G_FAIL TX falling clock");
            tx_cases++;
        end
    endtask
    task automatic rx_case(input [7:0] d,input bit dv,input bit er);
        begin
            @(negedge rxclk); #1; rxd=d[3:0]; rxctl=dv;
            @(posedge rxclk); #1; rxd=d[7:4]; rxctl=dv^er;
            @(negedge rxclk); #1; rxd=0; rxctl=0;
            @(posedge rxclk); #0.2;
            if(mrxdata !== d || mrxdv !== dv || mrxer !== er || mrxclk !== rxclk)
                $fatal(1,"RGMII_1G_FAIL RX data=%h expected=%h dv=%b er=%b",mrxdata,d,mrxdv,mrxer);
            rx_cases++;
        end
    endtask

    // 独立可控时钟数字层：不依赖厂商原语的流水线作为参考。
    reg pt=0, pr=0, pn=0;
    reg [3:0] pl=4'h5, ph=4'ha;
    reg pcr=1, pcf=1;
    wire ptu, pru, pen, per, ptcr, ptcf;
    wire [3:0] ptr, ptf;
    wire [7:0] pd;
    OpenEthRgmiiPairs pairs (
        .tx_clk(pt),.rx_clk(pr),.reset_n(pn),
        .tx_data(8'hb6),.tx_enable(1'b1),.tx_error(1'b1),
        .tx_rise(ptr),.tx_fall(ptf),.tx_ctl_rise(ptcr),.tx_ctl_fall(ptcf),
        .rx_rise(pl),.rx_fall(ph),.rx_ctl_rise(pcr),.rx_ctl_fall(pcf),
        .rx_data(pd),.rx_enable(pen),.rx_error(per),.tx_reset_n(ptu),.rx_reset_n(pru)
    );
    task automatic pulse_tx;
        begin #1;pt=1;#0.2;pt=0;end
    endtask
    task automatic pulse_rx;
        begin #1;pr=1;#0.2;pr=0;end
    endtask
    task automatic pair_reset_case(input integer pause_domain);
        begin
            pn=0;#0.2;
            if(ptu !== 0 || pru !== 0 || ptcr !== 0 || ptcf !== 0 || pen !== 0 || per !== 0)
                $fatal(1,"RGMII_1G_FAIL pairs no-edge cancel");
            #1;pn=1;
            if(pause_domain != 0) begin
                pulse_tx(); if(ptu !== 0) $fatal(1,"RGMII_1G_FAIL TX first release");
                pulse_tx(); if(ptu !== 1) $fatal(1,"RGMII_1G_FAIL TX second release");
            end
            if(pause_domain != 1) begin
                pcr=1;pcf=1;
                pulse_rx(); if(pru !== 0 || pen !== 0) $fatal(1,"RGMII_1G_FAIL RX first release");
                pulse_rx(); if(pru !== 1 || pen !== 0) $fatal(1,"RGMII_1G_FAIL RX second release");
                repeat(4) begin pulse_rx(); if(pen !== 0) $fatal(1,"RGMII_1G_FAIL RX old tail"); end
                pcr=0;pcf=0;pulse_rx(); pcr=1;pcf=0;#0.2;
                if(pen !== 1 || per !== 1 || pd !== 8'ha5) $fatal(1,"RGMII_1G_FAIL RX new frame/error");
            end
            if(pause_domain==0 && ptu !== 0) $fatal(1,"RGMII_1G_FAIL stopped TX release");
            if(pause_domain==1 && pru !== 0) $fatal(1,"RGMII_1G_FAIL stopped RX release");
            resets++;
        end
    endtask
    initial begin
        // 真正UNISIM/glbl正常启动，不绕过GSR。
        #240;rst=0;
        repeat(8) @(posedge clk);
        repeat(8) @(posedge rxclk);#0.2;
        if(mtxrst !== 0 || mrxrst !== 0) $fatal(1,"RGMII_1G_FAIL initial release");
        fork
            for(integer i=0;i<1024;i++) tx_case(i[7:0],i[8],i[9]);
            for(integer j=0;j<1024;j++) rx_case(j[7:0],j[8],j[9]);
        join
        for(integer k=0;k<3;k++) pair_reset_case(k);
        // 真正RX域停钟：复位立即取消，解除但无沿时不能恢复。
        @(negedge rxclk);#0.2;rx_run=0;
        rst=1;#0.2;
        if(mtxrst !== 1 || mrxrst !== 1 || mrxdv !== 0 || mrxer !== 0)
            $fatal(1,"RGMII_1G_FAIL physical async logical cancel");
        rst=0;repeat(6) @(posedge clk);#0.2;
        if(mtxrst !== 0 || mrxrst !== 1) $fatal(1,"RGMII_1G_FAIL physical stopped release");
        rxd=4'h5;rxctl=1;rx_run=1;
        repeat(8) begin @(posedge rxclk);#0.2;
            if(mrxdv !== 0) $fatal(1,"RGMII_1G_FAIL physical old-tail rejection");
        end
        @(negedge rxclk);#1;rxd=0;rxctl=0;
        repeat(5) @(posedge rxclk);
        rx_case(8'h39,1,1);tx_case(8'ha6,1,0);
        $display("RGMII_1G_PASS tx_pairs=%0d rx_pairs=%0d pair_reset_cases=%0d phase_ns=2 old_tail=1 primitive_library=UNISIM pad_async_cancel=0",tx_cases,rx_cases,resets);
        $finish;
    end
    initial begin #100000; $fatal(1,"RGMII_1G_FAIL watchdog");end
endmodule

// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: mac/sim/product_clock_test.cpp
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
#include "VMacGmiiCore.h"
#include "verilated.h"
#include <zlib.h>
#include <iostream>
#include <stdexcept>
#include <vector>
using Bytes=std::vector<unsigned char>;
int main(int argc,char**argv) {
  Verilated::commandArgs(argc,argv);
  try {
    VMacGmiiCore d;
    const int maxBytes=argc>1?std::stoi(argv[1]):2048;
    Bytes body(std::min(128,maxBytes));for(unsigned i=0;i<body.size();i++)body[i]=i;
    auto c=crc32(0,body.data(),body.size());
    Bytes w(7,0x55);w.push_back(0xd5);w.insert(w.end(),body.begin(),body.end());
    for(int i=0;i<4;i++)w.push_back(c>>(8*i));
    d.io_txClock=0;d.io_rxClock=0;d.io_txReset=1;d.io_rxReset=1;
    d.io_txIn_valid=0;d.io_txIn_bits_data=0;d.io_txIn_bits_last=0;d.io_txIn_bits_error=0;
    d.io_rxData=0;d.io_rxEnable=0;d.io_rxError=0;
    size_t tp=0,rp=0;Bytes tx,rx;int td=0,rg=0,rl=0;bool error=false;
    for(unsigned t=0;t<20000;t++) {
      bool te=t%8==0, re=t%11==0;
      if(t==100){d.io_txReset=0;d.io_rxReset=0;}
      if(te) {
        d.io_txClock=0;
        d.io_txIn_valid=t>100&&tp<body.size();
        if(tp<body.size()){d.io_txIn_bits_data=body[tp];d.io_txIn_bits_last=tp+1==body.size();}
      }
      if(re) {
        d.io_rxClock=0;
        d.io_rxEnable=t>100&&rp<w.size();
        if(d.io_rxEnable)d.io_rxData=w[rp];
      }
      d.eval();
      if(te) {
        if(!d.io_txReset) {
          if(d.io_txEnable)tx.push_back(d.io_txData);
          error |= d.io_txError||d.io_txAbort||d.io_txUnderrun;
          td+=d.io_txDone;
          if(d.io_txIn_valid&&d.io_txIn_ready)tp++;
        }
        d.io_txClock=1;
      }
      if(re) {
        if(!d.io_rxReset) {
          if(d.io_rxEnable)rp++;
          if(d.io_rxOut_valid) {rx.push_back(d.io_rxOut_bits_data);rl+=d.io_rxOut_bits_last;error|=d.io_rxOut_bits_error;}
          rg+=d.io_rxGood;error|=d.io_rxBad;
        }
        d.io_rxClock=1;
      }
      d.eval();
    }
    if(tx!=w||rx!=body||td!=1||rg!=1||rl!=1||error)throw std::runtime_error("independent clock product mismatch");
    std::cout<<"MAC_PRODUCT_CLOCK_PASS tx_period=8 rx_period=11 bytes="<<body.size()<<" separate_clocks=1\n";
    return 0;
  }catch(const std::exception&e){std::cerr<<"MAC_PRODUCT_CLOCK_FAIL "<<e.what()<<"\n";return 1;}
}

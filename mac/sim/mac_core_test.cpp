// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: mac/sim/mac_core_test.cpp
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
#include "VMacCoreHarness.h"
#include "verilated.h"
#include <zlib.h>
#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

using Bytes = std::vector<uint8_t>;
static bool negativeWire=false;
static void check(bool ok, const std::string& why) { if(!ok) throw std::runtime_error(why); }
static uint32_t hostCrc(const Bytes& b) { return ::crc32(0, b.data(), b.size()); }
static Bytes wire(const Bytes& body) {
  Bytes padded = body;
  padded.resize(std::max<size_t>(60, padded.size()), 0);
  uint32_t crc = hostCrc(padded);
  Bytes r(7, 0x55); r.push_back(0xd5);
  r.insert(r.end(), padded.begin(), padded.end());
  for(int i=0;i<4;i++) r.push_back(uint8_t(crc>>(8*i)));
  if(negativeWire)r.back()^=1;
  return r;
}
struct RxFrame { Bytes bytes; bool error=false; uint64_t first=0,last=0; };
struct TxFrame { Bytes bytes; bool error=false; uint64_t first=0,last=0; };
struct Sim {
  VMacCoreHarness dut;
  uint64_t cycle=0;
  std::vector<TxFrame> tx;
  std::vector<RxFrame> rx;
  TxFrame currentTx;
  RxFrame currentRx;
  std::vector<uint64_t> done, abort, underrun, rxGood, rxBad;
  void eval() { dut.clock=0; dut.eval(); }
  void tick() {
    eval();
    if(!dut.reset) {
      if(dut.io_txEnable) {
        if(currentTx.bytes.empty()) currentTx.first=cycle;
        currentTx.bytes.push_back(dut.io_txData);
        currentTx.error |= dut.io_txError;
        currentTx.last=cycle;
      } else if(!currentTx.bytes.empty()) { tx.push_back(currentTx); currentTx={}; }
      if(dut.io_txDone) done.push_back(cycle);
      if(dut.io_txAbort) abort.push_back(cycle);
      if(dut.io_txUnderrun) underrun.push_back(cycle);
      if(dut.io_rxGood) rxGood.push_back(cycle);
      if(dut.io_rxBad) rxBad.push_back(cycle);
      if(dut.io_rxOut_valid) {
        if(currentRx.bytes.empty()) currentRx.first=cycle;
        currentRx.bytes.push_back(dut.io_rxOut_bits_data);
        currentRx.error |= dut.io_rxOut_bits_error;
        if(dut.io_rxOut_bits_last) {
          currentRx.last=cycle; rx.push_back(currentRx); currentRx={};
        }
      }
    }
    dut.clock=1; dut.eval(); cycle++;
  }
  void idle(unsigned n=20) {
    dut.io_txIn_valid=0; dut.io_rxEnable=0; dut.io_rxError=0;
    while(n--) tick();
  }
  void reset() {
    dut.io_txIn_valid=0; dut.io_txIn_bits_data=0; dut.io_txIn_bits_last=0;
    dut.io_txIn_bits_error=0; dut.io_rxEnable=0; dut.io_rxData=0; dut.io_rxError=0;
    dut.io_crcIn=0; dut.io_crcByte=0;
    dut.io_rgTxData=0; dut.io_rgTxEn=0; dut.io_rgTxEr=0;
    dut.io_rgRxRise=0; dut.io_rgRxFall=0; dut.io_rgRxCtlRise=0; dut.io_rgRxCtlFall=0;
    dut.reset=1; for(int i=0;i<4;i++) tick();
    dut.reset=0;
    tx.clear(); rx.clear(); currentTx={}; currentRx={};
    done.clear(); abort.clear(); underrun.clear(); rxGood.clear(); rxBad.clear();
    idle(2);
  }
  std::vector<uint64_t> transmit(const std::vector<Bytes>& frames, int errorAt=-1) {
    size_t f=0,pos=0; std::vector<uint64_t> accept;
    for(unsigned timeout=0;timeout<1000000;timeout++) {
      dut.io_txIn_valid=f<frames.size();
      if(f<frames.size()) {
        dut.io_txIn_bits_data=frames[f][pos];
        dut.io_txIn_bits_last=pos+1==frames[f].size();
        dut.io_txIn_bits_error=int(pos)==errorAt;
      }
      eval(); bool fire=dut.io_txIn_valid && dut.io_txIn_ready;
      if(fire && pos==0) accept.push_back(cycle);
      tick();
      if(fire && ++pos==frames[f].size()) {pos=0;f++;}
      if(f==frames.size() && !dut.io_txActive) { idle(2); return accept; }
    }
    throw std::runtime_error("TX timeout");
  }
  void receive(const Bytes& frame, int errorAt=-1, unsigned gap=12) {
    dut.io_txIn_valid=0;
    for(size_t i=0;i<frame.size();i++) {
      dut.io_rxEnable=1; dut.io_rxData=frame[i]; dut.io_rxError=int(i)==errorAt; tick();
    }
    dut.io_rxEnable=0; dut.io_rxError=0;
    for(unsigned i=0;i<gap;i++) tick();
  }
};

int main(int argc,char** argv) {
  Verilated::commandArgs(argc,argv);
  try {
    const int maxBytes=argc>1?std::stoi(argv[1]):2048;
    negativeWire=argc>2 && std::string(argv[2])=="--negative-wire";
    Sim s; s.reset(); std::mt19937 rng(0x3192026);
    Bytes v{'1','2','3','4','5','6','7','8','9'};
    uint32_t c=0xffffffff;
    for(auto b:v) {s.dut.io_crcIn=c;s.dut.io_crcByte=b;s.eval();c=s.dut.io_crcOut;}
    const bool negative=argc>2 && std::string(argv[2])=="--negative-reference";
    check(~c==0xcbf43926 && ~c==(hostCrc(v)^(negative?1:0)),"CRC known vector");
    for(int n=0;n<200;n++) {
      Bytes b(1+rng()%2048); for(auto& a:b) a=rng(); c=0xffffffff;
      for(auto a:b) {s.dut.io_crcIn=c;s.dut.io_crcByte=a;s.eval();c=s.dut.io_crcOut;}
      check(~c==hostCrc(b),"CRC random vector");
    }
    for(int d=0;d<256;d++) for(int en=0;en<2;en++) for(int er=0;er<2;er++) {
      s.dut.io_rgTxData=d;s.dut.io_rgTxEn=en;s.dut.io_rgTxEr=er;
      s.dut.io_rgRxRise=d&15;s.dut.io_rgRxFall=d>>4;
      s.dut.io_rgRxCtlRise=en;s.dut.io_rgRxCtlFall=en^er;s.eval();
      check(s.dut.io_rgTxRise==(d&15) && s.dut.io_rgTxFall==(d>>4) &&
            s.dut.io_rgTxCtlRise==en && s.dut.io_rgTxCtlFall==(en^er) &&
            s.dut.io_rgRxData==d && s.dut.io_rgRxEn==en && s.dut.io_rgRxEr==er,
            "RGMII exhaustive digital pair");
    }
    std::vector<Bytes> frames;
    std::vector<int> lengths{1,2,7,8,14,59,60,61,63,64,65,127,128,1514,maxBytes};
    for(int n:lengths) if(n<=maxBytes) { Bytes b(n);for(auto& a:b)a=rng();frames.push_back(b); }
    for(int n=0;n<150;n++) {Bytes b(1+rng()%maxBytes);for(auto& a:b)a=rng();frames.push_back(b);}
    auto accept=s.transmit(frames);
    check(s.tx.size()==frames.size() && s.done.size()==frames.size(),"TX frame count");
    for(size_t i=0;i<frames.size();i++) {
      check(s.tx[i].bytes==wire(frames[i]) && !s.tx[i].error,"TX wire bytes "+std::to_string(i));
      if(i) check(s.tx[i].first-s.tx[i-1].last-1==12,"TX ideal IFG");
    }
    check(s.abort.empty() && s.underrun.empty(),"normal TX error");
    s.reset();
    for(const auto& b:frames) s.receive(wire(b));
    check(s.rx.size()==frames.size() && s.rxGood.size()==frames.size() && s.rxBad.empty(),"RX count");
    for(size_t i=0;i<frames.size();i++) {
      Bytes b=frames[i]; b.resize(std::max<size_t>(60,b.size()),0);
      check(s.rx[i].bytes==b && !s.rx[i].error,"RX body "+std::to_string(i));
    }

    // 相互独立的线路注入：FCS/数据/PHY错误、短帧、前导和过长。
    Bytes normal(std::min(128,maxBytes));for(auto& a:normal)a=rng(); auto w=wire(normal);
    for(size_t flip:{size_t(8),size_t(40),w.size()-4,w.size()-1}) {
      s.reset(); auto x=w;x[flip]^=1;s.receive(x);
      check(s.rx.size()==1 && s.rx[0].error && s.rxBad.size()==1 && s.rxGood.empty(),"RX bit fault");
    }
    for(int at:{0,3,7,8,30,int(w.size()-1)}) {
      s.reset();s.receive(w,at);
      check(s.rxGood.empty() && s.rxBad.size()==1,"RX PHY fault");
      if(at>=8) check(s.rx.size()==1 && s.rx[0].error,"RX PHY error tail");
    }
    for(int cut:{1,4,7,8,9,11,12,13,30,60}) {
      s.reset();Bytes x(w.begin(),w.begin()+cut);s.receive(x);
      check(s.rxGood.empty() && s.rxBad.size()==1,"RX truncated frame "+std::to_string(cut));
      if(!s.rx.empty())check(s.rx.back().error,"RX truncated must fail");
      check(s.currentRx.bytes.empty(),"RX dangling truncated frame");
    }
    s.reset(); Bytes over(maxBytes+9,0x5a);s.receive(wire(over));
    check(s.rx.size()==1 && s.rx[0].error && s.rxBad.size()==1 && s.rxGood.empty(),"RX oversize");
    check(s.rx[0].bytes.size()<=size_t(maxBytes),"RX bounded output");
    s.receive(w);check(s.rxGood.size()==1 && !s.rx.back().error,"RX recover after oversize");
    s.reset(); auto malformed=w;malformed[7]=0x55;s.receive(malformed);s.receive(w);
    check(s.rxBad.size()==1 && s.rxGood.size()==1,"RX preamble recovery");
    s.reset();s.transmit({normal},11);
    check(s.done.empty() && s.abort.size()==1 && s.tx.size()==1 && s.tx[0].error,"TX explicit error");
    s.transmit({normal});check(s.done.size()==1 && s.tx.back().bytes==w,"TX recover after error");
    s.reset();s.transmit({over});
    check(s.done.empty() && s.abort.size()==1 && s.tx[0].error,"TX oversize");
    s.transmit({normal});check(s.done.size()==1,"TX oversize recovery");
    s.reset();
    s.dut.io_txIn_valid=1;s.dut.io_txIn_bits_data=0xab;s.dut.io_txIn_bits_last=0;
    s.tick();s.dut.io_txIn_valid=0;for(int i=0;i<30;i++)s.tick();
    check(s.underrun.size()==1 && s.abort.size()==1 && s.done.empty(),"TX underrun");
    s.dut.io_txIn_valid=1;s.dut.io_txIn_bits_last=1;s.tick();s.idle(20);
    s.transmit({normal});check(s.done.size()==1,"TX underrun drain recovery");

    // 流水线在每个潜在活动阶段复位，不从旧帧恢复/虚报完成。
    for(int resetAt:{0,1,4,8,10,40,100,139}) {
      s.reset();size_t pos=0;
      for(int i=0;i<resetAt;i++) {
        s.dut.io_txIn_valid=pos<normal.size();
        if(pos<normal.size()) {s.dut.io_txIn_bits_data=normal[pos];s.dut.io_txIn_bits_last=pos+1==normal.size();}
        s.dut.io_rxEnable=i<int(w.size());s.dut.io_rxData=i<int(w.size())?w[i]:0;
        s.eval();bool fire=s.dut.io_txIn_valid&&s.dut.io_txIn_ready;s.tick();if(fire)pos++;
      }
      s.reset();s.transmit({normal});s.receive(w);
      check(s.tx.size()==1 && s.done.size()==1 && s.tx[0].bytes==w &&
            s.rxGood.size()==1 && s.rx.size()==1 && !s.rx[0].error,"reset recovery");
    }
    // 两方向同周期推进，数据各由独立线路模型核对。
    s.reset();size_t tp=0,rp=0;int sent=0;unsigned rxGap=0;
    for(unsigned guard=0;guard<100000;guard++) {
      s.dut.io_txIn_valid=sent<10;
      if(sent<10){s.dut.io_txIn_bits_data=normal[tp];s.dut.io_txIn_bits_last=tp+1==normal.size();}
      if(rp<w.size()) {s.dut.io_rxEnable=1;s.dut.io_rxData=w[rp++];}
      else {s.dut.io_rxEnable=0;if(++rxGap==12 && s.rx.size()<10){rp=0;rxGap=0;}}
      s.eval();bool fire=s.dut.io_txIn_valid&&s.dut.io_txIn_ready;s.tick();
      if(fire && ++tp==normal.size()){tp=0;sent++;}
      if(s.done.size()==10 && s.rxGood.size()==10){s.idle(20);break;}
    }
    check(s.done.size()==10 && s.rxGood.size()==10,"full duplex count");
    for(const auto& f:s.tx)check(f.bytes==w&&!f.error,"full duplex TX bytes");
    for(const auto& f:s.rx)check(f.bytes==normal&&!f.error,"full duplex RX bytes");

    std::cout << "MAC_CORE_PASS random_frames="<<frames.size()
              <<" crc_vectors=201 rgmii_vectors=1024 max_bytes="<<maxBytes<<"\n";
    std::cout << "PERF_HEADER length,frames,tx_accept_to_first,tx_accept_to_done,tx_span,tx_ii,rx_start_to_first,rx_start_to_last,rx_span,rx_ii\n";
    for(int length:{60,64,128,512,1514,maxBytes}) if(length<=maxBytes) {
      Bytes b(length,0xa7);s.reset();auto a=s.transmit(std::vector<Bytes>(64,b));
      uint64_t lat=s.done.front()-a.front(),first=s.tx.front().first-a.front();
      uint64_t txspan=s.done.back()-s.done.front();s.reset();
      uint64_t start=s.cycle;
      for(int i=0;i<64;i++)s.receive(wire(b));
      uint64_t rxspan=s.rx.back().last-s.rx.front().last;
      std::cout<<"PERF,"<<length<<",64,"<<first<<","<<lat<<","<<txspan<<","<<double(txspan)/63
               <<","<<s.rx.front().first-start<<","<<s.rx.front().last-start<<","<<rxspan<<","<<double(rxspan)/63<<"\n";
    }
    return 0;
  } catch(const std::exception& e) {std::cerr<<"MAC_CORE_FAIL "<<e.what()<<"\n";return 1;}
}

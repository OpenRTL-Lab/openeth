// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: buffer/sim/check.cpp
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
#include "Vtest_top.h"
#include "verilated.h"
#include <algorithm>
#include <cstdint>
#include <deque>
#include <iostream>
#include <random>
#include <stdexcept>
#include <string>
#include <tuple>
#include <vector>
#ifndef TEST_MAX
#define TEST_MAX 64
#endif
#ifndef TEST_BAD
#define TEST_BAD 1
#endif

struct Frame { std::vector<uint8_t> data; int error=-1; int illegal=-1; };
static void need(bool b,const std::string &s){ if(!b) throw std::runtime_error(s); }
struct RefFrame { std::vector<uint8_t> data; bool bad=false; };
struct Checker {
    std::deque<RefFrame> pending, expected;
    std::vector<uint8_t> capture;
    size_t offset=0;
    uint64_t committed=0,dropped=0,full=0,bad=0,longs=0,delivered=0,bytes=0;
    bool capture_bad=false;
    bool inject=false;
    void clear(){pending.clear();expected.clear();capture.clear();offset=0;capture_bad=false;}
    void input(uint64_t data,int keep,bool last,bool error){
        for(int i=0;i<8;i++) if(keep&(1<<i))capture.push_back(uint8_t(data>>(8*i)));
        capture_bad|=error;
        if(last){ pending.push_back({capture,capture_bad}); capture.clear();capture_bad=false; }
    }
    void status(bool commit,bool drop,bool ov,bool err,bool len,bool drop_bad){
        need(!(commit&&drop),"commit and drop together");
        if(!commit&&!drop){need(!ov&&!err&&!len,"unaccounted status");return;}
        need(!pending.empty(),"status without complete input frame");
        auto f=pending.front();pending.pop_front();
        bool too_long=f.data.size()>TEST_MAX;
        need(err==f.bad,"bad status disagreement");
        need(len==too_long,"oversize disagreement");
        need(drop==(ov||too_long||(drop_bad&&f.bad)),"drop reason disagreement");
        full+=ov;bad+=err;longs+=len;
        if(commit){ committed++;if(inject&&!f.data.empty()){f.data[0]^=1;inject=false;}expected.push_back(f); }
        else dropped++;
    }
    void output(uint64_t data,int keep,bool last,bool error,bool wide,bool errors_at_end){
        need(!expected.empty(),"uncommitted or duplicated output");auto &f=expected.front();
        int n=0;for(int i=0;i<8;i++) if(keep&(1<<i))n++;
        need(keep>0 && keep==((1<<n)-1),"illegal output keep");
        need(!wide || last || keep==255,"short non-tail wide beat");
        for(int i=0;i<n;i++){
            need(offset<f.data.size(),"extra bytes");
            need(uint8_t(data>>(i*8))==f.data[offset],"byte mismatch");offset++;bytes++;
        }
        need(last==(offset==f.data.size()),"last/frame length mismatch");
        need(error==(errors_at_end&&last&&f.bad),"output error mismatch");
        if(last){delivered++;expected.pop_front();offset=0;}
    }
};

struct Driver {
    std::deque<Frame> frames;
    size_t pos=0;
    bool holding=false;
    void clear(){frames.clear();pos=0;holding=false;}
};

class Bench {
public:
    Vtest_top d;
    std::mt19937 rng;
    Checker tx,rx;
    Driver td,rd;
    uint64_t time=0,ncycles=0,mcycles=0;
    int nhalf=5,mhalf=4,mphase=1;
    bool stop_mac=false,stop_nic=false,ideal=false,block=false;
    uint64_t tx_frame_start=0,rx_frame_start=0;
    uint64_t tx_first_in=0,tx_last_in=0,tx_first_out=0,tx_last_out=0;
    uint64_t rx_first_in=0,rx_last_in=0,rx_first_out=0,rx_last_out=0;
    std::vector<uint64_t> tx_ends,rx_ends;
    bool tx_seen=false,rx_seen=false;
    bool tx_stall=false,rx_stall=false;
    unsigned mac_warm_edges=0,nic_warm_edges=0,reset_epochs=0;
    std::tuple<uint64_t,int,bool,bool> tx_prev,rx_prev;
    explicit Bench(unsigned seed):rng(seed){d.nic_clk=0;d.mac_clk=0;d.nic_reset_n=0;d.mac_reset_n=0;d.eval();}
    void tick(){
        d.nic_clk=0;d.mac_clk=0;
        bool ne=!stop_nic && time%nhalf==0;
        bool me=!stop_mac && time>=unsigned(mphase) && (time-mphase)%mhalf==0;
        // All clocks use a one-step high pulse separated by low evaluations.
        if(ne){
            if(!td.holding&&!td.frames.empty()&&(ideal||rng()%4!=0))td.holding=true;
            d.tx_s_valid=td.holding && d.nic_reset_n && d.mac_reset_n;
            if(td.holding){
                auto &f=td.frames.front();int n=std::min<size_t>(8,f.data.size()-td.pos);
                d.tx_s_data=0;for(int i=0;i<n;i++)d.tx_s_data|=uint64_t(f.data[td.pos+i])<<(8*i);
                d.tx_s_keep=(1<<n)-1;
                if(f.illegal>=0&&int(td.pos/8)==f.illegal)d.tx_s_keep=0x55;
                d.tx_s_last=td.pos+n==f.data.size();
                d.tx_s_error=f.error>=int(td.pos)&&f.error<int(td.pos+n);
            }
            d.rx_m_ready=!block&&(ideal||rng()%3!=0);
        }
        if(me){
            if(!rd.holding&&!rd.frames.empty()&&(ideal||rng()%5!=0))rd.holding=true;
            d.rx_s_valid=rd.holding&&d.nic_reset_n&&d.mac_reset_n;
            if(rd.holding){auto &f=rd.frames.front();d.rx_s_data=f.data[rd.pos];d.rx_s_last=rd.pos+1==f.data.size();d.rx_s_error=int(rd.pos)==f.error;}
            d.tx_m_ready=!block&&(ideal||rng()%3!=0);
        }
        d.eval();
        bool txin=ne&&d.tx_s_valid&&d.tx_s_ready,rxin=me&&d.rx_s_valid&&d.rx_s_ready;
        bool txout=me&&d.tx_m_valid&&d.tx_m_ready,rxout=ne&&d.rx_m_valid&&d.rx_m_ready;
        bool enabled=d.nic_reset_n&&d.mac_reset_n;
        if(enabled&&nic_warm_edges<2)need(!d.tx_s_ready&&!d.rx_m_valid,"NIC released before two local edges");
        if(enabled&&mac_warm_edges<2)need(!d.rx_s_ready&&!d.tx_m_valid,"MAC released before two local edges");
        if(ne&&enabled)nic_warm_edges++;
        if(me&&enabled){
            if(mac_warm_edges>=3)need(d.rx_s_ready,"non-stallable RX backpressured");
            mac_warm_edges++;
        }
        if(ne&&enabled){
            auto now=std::make_tuple(uint64_t(d.rx_m_data),int(d.rx_m_keep),bool(d.rx_m_last),bool(d.rx_m_error));
            if(rx_stall)need(d.rx_m_valid&&now==rx_prev,"wide payload changed under backpressure");
            rx_stall=d.rx_m_valid&&!d.rx_m_ready;rx_prev=now;
        }
        if(me&&enabled){
            auto now=std::make_tuple(uint64_t(d.tx_m_data),1,bool(d.tx_m_last),bool(d.tx_m_error));
            if(tx_stall)need(d.tx_m_valid&&now==tx_prev,"byte payload changed under backpressure");
            tx_stall=d.tx_m_valid&&!d.tx_m_ready;tx_prev=now;
        }
        if(txin){
            int keep=d.tx_s_keep;bool legal=keep&&((keep&(keep+1))==0)&&(d.tx_s_last||keep==255);
            tx.input(d.tx_s_data,legal?keep:1,d.tx_s_last,d.tx_s_error||!legal);
            if(!tx_seen){tx_first_in=time;tx_seen=true;}tx_last_in=time;
            auto &f=td.frames.front();td.pos+=std::min<size_t>(8,f.data.size()-td.pos);td.holding=false;
            if(td.pos==f.data.size()){td.frames.pop_front();td.pos=0;}
        }
        if(rxin){
            rx.input(d.rx_s_data,1,d.rx_s_last,d.rx_s_error);
            if(!rx_seen){rx_first_in=time;rx_seen=true;}rx_last_in=time;
            rd.pos++;rd.holding=false;if(rd.pos==rd.frames.front().data.size()){rd.frames.pop_front();rd.pos=0;}
        }
        if(txout){if(!tx.delivered&&tx.offset==0)tx_first_out=time;tx_last_out=time;if(d.tx_m_last)tx_ends.push_back(time);tx.output(d.tx_m_data,1,d.tx_m_last,d.tx_m_error,false,false);}
        if(rxout){if(!rx.delivered&&rx.offset==0)rx_first_out=time;rx_last_out=time;if(d.rx_m_last)rx_ends.push_back(time);rx.output(d.rx_m_data,d.rx_m_keep,d.rx_m_last,d.rx_m_error,true,!TEST_BAD);}
        d.nic_clk=ne;d.mac_clk=me;d.eval();
        if(ne){ncycles++;tx.status(d.tx_commit,d.tx_drop,d.tx_overflow,d.tx_bad,d.tx_long,true);}
        if(me){mcycles++;rx.status(d.rx_commit,d.rx_drop,d.rx_overflow,d.rx_bad,d.rx_long,TEST_BAD);}
        time++;
    }
    void steps(uint64_t n){while(n--)tick();}
    bool done(){return td.frames.empty()&&rd.frames.empty()&&tx.capture.empty()&&rx.capture.empty()&&tx.pending.empty()&&rx.pending.empty()&&tx.expected.empty()&&rx.expected.empty()&&!d.tx_m_valid&&!d.rx_m_valid;}
    void drain(uint64_t limit=10000000){for(uint64_t i=0;i<limit;i++){tick();if(done()){steps(100);return;}}throw std::runtime_error("drain timeout");}
    void cancel_models(){
        // The source is explicitly restarted at a new frame after the epoch cancellation.
        td.clear();rd.clear();tx.clear();rx.clear();tx_stall=rx_stall=false;mac_warm_edges=nic_warm_edges=0;
        d.tx_s_valid=0;d.rx_s_valid=0;
        reset_epochs++;
    }
    void reset(int side){
        cancel_models();
        if(side==0)d.nic_reset_n=0;else d.mac_reset_n=0;
        d.eval();need(!d.tx_m_valid&&!d.rx_m_valid,"reset did not cancel outputs asynchronously");
        steps(40);d.nic_reset_n=1;d.mac_reset_n=1;d.eval();steps(100);
    }
    void pulse_reset(int side){
        cancel_models();
        // No rising clock edge occurs during this digital reset pulse.
        // It checks cancellation, not physical minimum-pulse-width or metastability.
        d.nic_clk=0;d.mac_clk=0;d.eval();
        if(side==0)d.nic_reset_n=0;else d.mac_reset_n=0;
        d.eval();
        need(!d.tx_m_valid&&!d.rx_m_valid&&!d.tx_s_ready&&!d.rx_s_ready,
             "short asynchronous pulse did not cancel visible interfaces");
        need(!d.tx_commit&&!d.rx_commit&&!d.tx_drop&&!d.rx_drop,"reset left stale status");
        d.nic_reset_n=1;d.mac_reset_n=1;d.eval();
        need(!d.tx_s_ready&&!d.rx_s_ready,"reset release skipped local synchronizers");
    }
    Frame frame(int n,int err=-1,int illegal=-1){Frame f;f.error=err;f.illegal=illegal;while(n--)f.data.push_back(uint8_t(rng()));return f;}
};

int main(int argc,char **argv){
    Verilated::commandArgs(argc,argv);
    try {
        unsigned seed=argc>1?std::stoul(argv[1]):12345;
        Bench b(seed);if(argc>2)b.tx.inject=true;
        b.reset(0);
        // Directed lengths cover every final keep mask and a full maximum frame.
        for(int n: {1,2,3,4,5,6,7,8,9,15,16,17,TEST_MAX-1,TEST_MAX}){
            b.td.frames.push_back(b.frame(n));b.rd.frames.push_back(b.frame(n));b.drain();
        }
        for(int n:{1,8,17,TEST_MAX}){
            b.td.frames.push_back(b.frame(n,n-1));b.rd.frames.push_back(b.frame(n,n/2));b.drain();
        }
        b.td.frames.push_back(b.frame(17,-1,0));b.drain();
        b.td.frames.push_back(b.frame(TEST_MAX+7));b.rd.frames.push_back(b.frame(TEST_MAX+7));b.drain();
        uint64_t full_before=b.rx.full;
        // Non-stallable RX saturates; TX stalls without corrupting or overwriting slots.
        b.ideal=true;b.block=true;
        for(int i=0;i<24;i++){b.td.frames.push_back(b.frame(11));b.rd.frames.push_back(b.frame(11));}
        b.steps(20000);b.block=false;b.drain();
        need(b.rx.full>full_before,"full-drop coverage missing");need(b.tx.full==0,"stallable TX overflowed");
        b.ideal=false;
        for(int batch=0;batch<3;batch++){
            b.nhalf=batch==1?3:7;b.mhalf=batch==2?11:4;b.mphase=batch;
            for(int i=0;i<120;i++){
                int n=1+b.rng()%(TEST_MAX+8);int err=i%13==0?n/2:-1;
                b.td.frames.push_back(b.frame(n,err));b.rd.frames.push_back(b.frame(n,err));
            }b.drain();
        }
        // Both reset directions, mid-frame and with a stopped destination clock.
        for(int side=0;side<2;side++){
            b.block=true;for(int i=0;i<8;i++){b.td.frames.push_back(b.frame(TEST_MAX));b.rd.frames.push_back(b.frame(TEST_MAX));}
            b.steps(130);b.stop_mac=true;b.reset(side);b.stop_mac=false;b.block=false;
            b.steps(100);b.td.frames.push_back(b.frame(19));b.rd.frames.push_back(b.frame(23));b.drain();
        }
        // Release at four relative phases, with either domain stopped; cancel
        // partial input and stalled output ownership, then restart at a new frame.
        for(int side=0;side<2;side++)for(int stopped=0;stopped<2;stopped++)for(int phase=0;phase<4;phase++){
            b.nhalf=5;b.mhalf=4;b.mphase=phase;b.ideal=true;b.block=true;
            for(int i=0;i<8;i++){b.td.frames.push_back(b.frame(TEST_MAX));b.rd.frames.push_back(b.frame(TEST_MAX));}
            b.steps(37+phase*13);
            b.stop_nic=stopped==0;b.stop_mac=stopped==1;
            b.pulse_reset(side);b.steps(37+phase);
            need(!b.d.tx_m_valid&&!b.d.rx_m_valid,"cancelled frame escaped while clock stopped");
            need(stopped==0?!b.d.tx_s_ready:!b.d.rx_s_ready,"stopped domain released without clock");
            b.stop_nic=b.stop_mac=false;b.block=false;b.steps(100);
            b.td.frames.push_back(b.frame(17));b.rd.frames.push_back(b.frame(31));b.drain();
        }
        need(b.tx.committed>100&&b.rx.committed>30,"insufficient real traffic");
        need(b.tx.bad&&b.rx.bad&&b.tx.longs&&b.rx.longs,"error coverage missing");
        std::cout<<"OPENETH_ETH01_PASS seed="<<seed<<" max="<<TEST_MAX<<" rx_drop_bad="<<TEST_BAD
            <<" tx_frames="<<b.tx.delivered<<" rx_frames="<<b.rx.delivered<<" tx_drop="<<b.tx.dropped<<" rx_drop="<<b.rx.dropped
            <<" rx_full="<<b.rx.full<<" reset_epochs="<<b.reset_epochs<<" stability=1 bytecheck=1"<<std::endl;
        return 0;
    }catch(const std::exception &e){std::cerr<<"OPENETH_ETH01_FAIL "<<e.what()<<std::endl;return 1;}
}

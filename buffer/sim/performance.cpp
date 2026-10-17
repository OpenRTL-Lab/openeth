// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: buffer/sim/performance.cpp
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
#define main regression_entry
#include "check.cpp"
#undef main
#include <iomanip>

int main(int argc,char **argv){
    Verilated::commandArgs(argc,argv);
    try {
        std::cout<<"{\"scope\":\"ideal native interfaces, no MAC/line overhead\",\"time_unit_ns\":2,\"nic_period_ns\":20,\"mac_period_ns\":8,\"slots\":4,\"max_frame_bytes\":2048,\"rows\":[";
        bool comma=false;
        for(int n:{64,1514,2048})for(int direction=0;direction<2;direction++){
            Bench one(101);one.nhalf=10;one.mhalf=4;one.mphase=1;one.ideal=true;one.reset(0);
            if(direction==0)one.td.frames.push_back(one.frame(n));else one.rd.frames.push_back(one.frame(n));
            one.drain();
            uint64_t first=direction==0?one.tx_first_in:one.rx_first_in;
            uint64_t last=direction==0?one.tx_last_in:one.rx_last_in;
            uint64_t firstout=direction==0?one.tx_first_out:one.rx_first_out;
            uint64_t lastout=direction==0?one.tx_last_out:one.rx_last_out;
            Bench many(102);many.nhalf=10;many.mhalf=4;many.mphase=1;many.ideal=true;many.reset(0);
            for(int i=0;i<256;i++){if(direction==0)many.td.frames.push_back(many.frame(n));else many.rd.frames.push_back(many.frame(n));}
            many.drain();
            auto ends=direction==0?many.tx_ends:many.rx_ends;
            need(ends.size()==256,"missing performance frames");
            need(many.tx.dropped==0 && many.rx.dropped==0,"unexpected ideal traffic drop");
            // Tail-to-tail steady-state window excludes first 16 warm-up frames.
            uint64_t span=ends.back()-ends[15];
            double ii_ns=2.0*span/240.0;
            double fps=1e9/ii_ns;
            if(comma)std::cout<<',';comma=true;
            std::cout<<std::setprecision(12)<<"{\"direction\":\""<<(direction==0?"TX":"RX")<<"\",\"frame_bytes\":"<<n
                <<",\"first_to_first_ns\":"<<2*(firstout-first)<<",\"first_to_complete_ns\":"<<2*(lastout-first)
                <<",\"last_to_complete_ns\":"<<2*(lastout-last)<<",\"frames\":256,\"intervals\":240,\"steady_span_ns\":"<<2*span
                <<",\"ii_ns\":"<<ii_ns<<",\"frames_per_second\":"<<fps<<",\"native_mbit_s\":"<<fps*n*8/1e6<<",\"drops\":0}";
        }
        std::cout<<"],\"status\":0}"<<std::endl;
        return 0;
    }catch(const std::exception &e){std::cerr<<"PERFORMANCE_FAIL "<<e.what()<<std::endl;return 1;}
}

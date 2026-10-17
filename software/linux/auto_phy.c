// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: software/linux/auto_phy.c
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
// Narrow Linux client of the production Clause22 management controller.
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>
static volatile uint32_t *m;
static uint64_t now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return (uint64_t)t.tv_sec*1000000000+t.tv_nsec; }
static uint32_t rd(unsigned off) { __sync_synchronize();return m[off/4]; }
static void wr(unsigned off,uint32_t v) { m[off/4]=v;__sync_synchronize(); }
static void fail(const char *s) { perror(s);exit(1); }
static uint16_t transaction(unsigned reg,int read,uint16_t data) {
    if(rd(0x44)&1) { errno=EBUSY;fail("MDIO already busy"); }
    wr(0x40,0x80000000u|(read?0x40000000u:0)|(reg<<20)|data);
    uint64_t end=now()+1000000000;uint32_t status;
    do { status=rd(0x44); if(now()>end) { errno=ETIMEDOUT;fail("MDIO completion"); } } while((status&1)||!(status&2));
    if((status&4)||(read&&!(status&8))) { fprintf(stderr,"MDIO error reg=%u status=%08x\n",reg,status);exit(1); }
    return rd(0x48);
}
static uint16_t phy(unsigned reg) { return transaction(reg,1,0); }
static void put(unsigned reg,uint16_t data) { (void)transaction(reg,0,data); }
static uint16_t rxid(uint16_t x) { return (x&~0x3800u)|0x2800u; }
static int linkok(uint16_t s,uint16_t l,uint16_t p) { return s!=0xffff&&l!=0xffff&&p!=0xffff&&(s&0x24)==0x24&&(l&0x200)&&(p&0x800)&&!(p&0x8000); }
int main(int argc,char **argv) {
    if(argc==2&&!strcmp(argv[1],"--self-test")) {
        for(unsigned x=0;x<65536;x++) if((rxid(x)&0x3800)!=0x2800||(rxid(x)&~0x3800)!=(x&~0x3800)) return 2;
        if(!linkok(0x24,0x200,0x800)||linkok(0xffff,0xffff,0xffff)||linkok(0x24,0x200,0x8800)||linkok(0x20,0x200,0x800))return 3;
        puts("AUTO_PHY_POLICY_TEST_PASS hardware_access=0");return 0;
    }
    int configure=argc==2&&!strcmp(argv[1],"--configure");
    if(!configure&&(argc!=2||strcmp(argv[1],"--inspect"))) { fprintf(stderr,"usage: auto_phy --inspect|--configure|--self-test\n");return 2; }
    int fd=open("/dev/mem",O_RDWR|O_SYNC);if(fd<0)fail("/dev/mem");
    m=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0x6000);if(m==MAP_FAILED)fail("mmap");
    wr(0x10,4);wr(0x4c,19);
    // Production reset defaults low: a standalone Linux configure must release
    // the PHY before any identity transaction, not depend on a prior test.
    if(configure) { wr(0x14,1);usleep(200000); }
    put(31,0);uint16_t id1=phy(2),id2=phy(3);printf("AUTO_PHY id=%04x:%04x\n",id1,id2);
    if(id1!=0x001c||id2!=0xc915)return 4;
    put(31,7);put(30,0xa4);uint16_t before=phy(28);if(before==0xffff)return 5;
    if(configure)put(28,rxid(before));
    uint16_t after=phy(28);put(31,0);printf("AUTO_PHY delay_before=%04x after=%04x\n",before,after);
    if((after&0x3800)!=0x2800||(configure&&after!=rxid(before)))return 6;
    if(configure) { uint16_t adv=phy(4),g=phy(9),c=phy(0);if(adv==0xffff||g==0xffff||c==0xffff)return 7;put(4,adv&~0x3e0u);put(9,(g&~0x300u)|0x200u);put(0,c|0x1200u); }
    uint64_t end=now()+20000000000ULL;uint16_t s,l,p;
    do { usleep(100000);(void)phy(1);s=phy(1);l=phy(9);p=phy(10);if(now()>end) {fprintf(stderr,"link timeout %04x %04x %04x\n",s,l,p);return 8;} }while(!linkok(s,l,p));
    printf("AUTO_MDIO_PHY_PASS speed=1000 full_duplex=1 bmsr=%04x local=%04x partner=%04x\n",s,l,p);
    munmap((void *)m,4096);close(fd);return 0;
}

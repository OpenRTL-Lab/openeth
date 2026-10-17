// OpenRTL-source-header
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: OpenRTL-Lab/openeth
// Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
// File: software/linux/tcp_integrity.c
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// OpenRTL-source-header-end
// SPDX-License-Identifier: MIT
#include <arpa/inet.h>
#include <errno.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

static uint8_t buf[65536], expected[65536];
static void pattern(uint8_t *out, uint64_t block, unsigned seed) {
    for (unsigned i=0;i<65536;i++) out[i]=(uint8_t)((seed+block*29+i*17)^(i>>3));
    for (unsigned i=0;i<8;i++) out[i]=(uint8_t)(block>>(8*i));
}
static uint64_t decode(const uint8_t *p) { uint64_t n=0; for(unsigned i=0;i<8;i++) n|=(uint64_t)p[i]<<(i*8); return n; }
static int transfer(int fd,void *data,size_t len,int writing) {
    uint8_t *p=data;
    while(len) { ssize_t n=writing?send(fd,p,len,MSG_NOSIGNAL):recv(fd,p,len,0);
        if(n<0&&errno==EINTR) continue;
        if(n<=0) return -1;
        p+=n;len-=(size_t)n;
    } return 0;
}
int main(int argc,char **argv) {
    unsigned port=argc==2?(unsigned)strtoul(argv[1],0,10):5002;
    if(!port||port>65535) return 2;
    int listener=socket(AF_INET,SOCK_STREAM,0); if(listener<0) return 3;
    int one=1; setsockopt(listener,SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one));
    struct sockaddr_in a={.sin_family=AF_INET,.sin_port=htons((uint16_t)port),.sin_addr.s_addr=INADDR_ANY};
    struct timeval timeout={.tv_sec=120};
    setsockopt(listener,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    if(bind(listener,(void *)&a,sizeof(a))||listen(listener,1)) { perror("bind/listen"); return 4; }
    printf("INTEGRITY_SERVER_READY port=%u\n",port); fflush(stdout);
    int fd=accept(listener,0,0);close(listener);if(fd<0) return 5;
    setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    uint8_t header[8]; if(transfer(fd,header,8,0)) return 6;
    uint64_t total=decode(header),bad=0;
    if(!total||total>512ULL*1024*1024||total%65536) return 7;
    for(uint64_t offset=0;offset<total;offset+=65536) {
        if(transfer(fd,buf,65536,0)) return 8;
        pattern(expected,offset/65536,0x37);
        for(unsigned i=0;i<65536;i++) if(buf[i]!=expected[i]) bad++;
    }
    for(unsigned i=0;i<8;i++) header[i]=(uint8_t)(bad>>(i*8));
    if(transfer(fd,header,8,1)) return 9;
    printf("INTEGRITY_RX bytes=%"PRIu64" mismatches=%"PRIu64"\n",total,bad);fflush(stdout);
    if(bad) {close(fd);return 10;}
    for(uint64_t offset=0;offset<total;offset+=65536) {
        pattern(buf,offset/65536,0xa9); if(transfer(fd,buf,65536,1)) return 11;
    }
    shutdown(fd,SHUT_WR);close(fd);
    printf("INTEGRITY_SERVER_PASS rx_bytes=%"PRIu64" tx_bytes=%"PRIu64"\n",total,total);
    return 0;
}

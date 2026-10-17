#!/usr/bin/env python3
# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: mac/tools/verify.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
"""生成真实RTL并运行独立GMII/CRC参考回归；不触碰硬件。"""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import time

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--max-bytes',type=int,default=2048)
    p.add_argument('--jobs',type=int,default=4)
    a=p.parse_args()
    root=Path(__file__).resolve().parents[1]
    out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
    report={'status':1,'scope':'字节MAC/GMII与RGMII数字采样对；不含真实帧缓存、NIC或物理I/O','max_bytes':a.max_bytes,'start':time.time()}
    def run(argv,label):
        with (out/(label+'.log')).open('w') as log:
            r=subprocess.run(argv,cwd=root,stdout=log,stderr=subprocess.STDOUT)
        if r.returncode: raise RuntimeError(f'{label} failed: {out/(label+".log")}')
    try:
        mill=shlex.split(os.environ.get('MILL','mill'))
        report['tools']={exe:subprocess.check_output([exe,*opts],stderr=subprocess.STDOUT,text=True).strip()
            for exe,opts in [('java',['-version']),('verilator',['--version']),('g++',['--version'])]}
        report['source_commit']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
        run([*mill,'-i','mac.runMain','openeth.mac.EmitMac',str(out/'rtl'),str(a.max_bytes)],'emit')
        prefix=os.environ.get('ZLIB_PREFIX')
        cflags='-std=c++17'+(' -I'+shlex.quote(str(Path(prefix)/'include')) if prefix else '')
        ldflags=('-L'+shlex.quote(str(Path(prefix)/'lib'))+' -Wl,-rpath,'+shlex.quote(str(Path(prefix)/'lib'))+' ' if prefix else '')+'-lz'
        # firtool单文件可含多个模块，只豁免文件名与子模块名不同，其余警告仍失败。
        run(['verilator','--cc','--exe','--build','--assert','-Wall','-Wno-DECLFILENAME','--top-module','MacCoreHarness',
             '--Mdir',str(out/'obj'),'-j',str(a.jobs),'-CFLAGS',cflags,'-LDFLAGS',ldflags,
             str(out/'rtl/MacCoreHarness.sv'),str(root/'sim/mac_core_test.cpp')],'verilator')
        run([str(out/'obj/VMacCoreHarness'),str(a.max_bytes)],'test')
        log=(out/'test.log').read_text()
        if 'MAC_CORE_PASS ' not in log: raise RuntimeError('missing PASS marker')
        negative=subprocess.run([str(out/'obj/VMacCoreHarness'),str(a.max_bytes),'--negative-reference'],
            cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        (out/'negative-reference.log').write_text(negative.stdout)
        if negative.returncode==0 or 'MAC_CORE_FAIL CRC known vector' not in negative.stdout:
            raise RuntimeError('reference negative control did not fail as expected')
        report['negative_reference_pass']=True
        negative_wire=subprocess.run([str(out/'obj/VMacCoreHarness'),str(a.max_bytes),'--negative-wire'],
            cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        (out/'negative-wire.log').write_text(negative_wire.stdout)
        if negative_wire.returncode==0 or 'MAC_CORE_FAIL TX wire bytes' not in negative_wire.stdout:
            raise RuntimeError('wire negative control did not fail as expected')
        report['negative_wire_pass']=True
        run([sys.executable,str(root/'tools/emit_sv.py'),'--out',str(out/'product'),'--max-bytes',str(a.max_bytes)],'product-emit')
        run(['verilator','--cc','--exe','--build','--assert','-Wall','-Wno-DECLFILENAME','--top-module','MacGmiiCore',
             '--Mdir',str(out/'product-obj'),'-j',str(a.jobs),'-CFLAGS',cflags,'-LDFLAGS',ldflags,
             str(out/'product/gmii/MacGmiiCore.sv'),str(root/'sim/product_clock_test.cpp')],'product-verilator')
        run([str(out/'product-obj/VMacGmiiCore'),str(a.max_bytes)],'product-test')
        if 'MAC_PRODUCT_CLOCK_PASS' not in (out/'product-test.log').read_text():
            raise RuntimeError('missing product PASS')
        run(['verilator','--lint-only','--top-module','RgmiiDigital',str(out/'product/rgmii-digital/RgmiiDigital.sv')],'rgmii-lint')
        report['product_clock_pass']=True
        report['test_output']=log
        report['status']=0
    except Exception as e:
        report['error']=str(e)
    report['end']=time.time()
    target=out/'completion.json';tmp=out/'completion.tmp'
    tmp.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');tmp.replace(target)
    print(json.dumps({'status':report['status'],'output':str(out)},ensure_ascii=False))
    return report['status']
if __name__=='__main__':sys.exit(main())

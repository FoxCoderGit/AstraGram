"""Build a small MK1-only core from the unmodified public MAME CPU/audio/video engines.

The only driver adaptation is clone metadata: a full non-merged mkla4 set does not
need the unrelated 1993 parent driver. Original program, graphics and sound bytes
are verified against the upstream 1992 rev4 driver before packaging.
"""
from pathlib import Path
import re,zipfile,hashlib,json,subprocess,os,shlex
ROOT=Path(__file__).resolve().parent
VENDOR=ROOT/'vendor/mame2003-plus'
BUILD=ROOT/'arcade-build'
def prepare():
    BUILD.mkdir(exist_ok=True)
    driver=(VENDOR/'src/drivers/midyunit.c').read_text()
    section=driver.split('ROM_START( mkla4 )',1)[1].split('ROM_END',1)[0]
    entries=re.findall(r'ROM_LOAD\w*\s*\(\s*"([^"]+)".*?CRC\(([a-f0-9]+)\)\s*SHA1\(([a-f0-9]+)\)',section)
    records=[]
    target=ROOT/'original/mkla4.zip'
    with zipfile.ZipFile(ROOT/'original/mk.zip') as source,zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as output:
        by_crc={f.CRC:f for f in source.infolist()}
        for name,crc,sha in entries:
            record=by_crc.get(int(crc,16))
            if record is None:raise ValueError('Missing original ROM '+name)
            data=source.read(record)
            if hashlib.sha1(data).hexdigest()!=sha:raise ValueError('ROM digest mismatch '+name)
            entry=zipfile.ZipInfo(name,(1992,9,28,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED
            output.writestr(entry,data);records.append(dict(name=name,crc=crc,sha1=sha,size=len(data)))
    (ROOT/'original/verified-mkla4.json').write_text(json.dumps(dict(game='Mortal Kombat (rev 4.0 09-28-92)',files=records,sha256=hashlib.sha256(target.read_bytes()).hexdigest()),indent=2))
    print('Verified original MK1 rev4 1992:',len(records),'ROM files,',target.stat().st_size,'bytes')
    # Keep the emulator source intact; generate a narrowly scoped driver compilation copy.
    driver=re.sub(r'(GAME\s*\(\s*1992,\s*mkla4,\s*)mk,',r'\g<1>0,',driver)
    (BUILD/'midyunit_selected.c').write_text(driver)
    # Upstream Y-unit predates complete save-state support. Register its device
    # state; this changes neither the original ROM nor combat/video algorithms.
    video=(VENDOR/'src/vidhrdw/midyunit_vidhrdw.c').read_text()
    registration='''
    state_save_register_UINT16("ghost_yunit",0,"vram",local_videoram,0x40000);
    state_save_register_UINT16("ghost_yunit",0,"cmos",midyunit_cmos_ram,0x4000);
    state_save_register_UINT32("ghost_yunit",0,"cmos_page",&midyunit_cmos_page,1);
    state_save_register_UINT8("ghost_yunit",0,"autoerase",&autoerase_enable,1);
    state_save_register_UINT8("ghost_yunit",0,"video_bank",&videobank_select,1);
    state_save_register_int("ghost_yunit",0,"scanline",&last_update_scanline);
    state_save_register_UINT16("ghost_yunit",0,"dma_regs",dma_register,16);
    state_save_register_UINT32("ghost_yunit",0,"dma_offset",&dma_state.offset,1);
    state_save_register_INT32("ghost_yunit",0,"dma_rowbytes",&dma_state.rowbytes,1);
    state_save_register_INT32("ghost_yunit",0,"dma_x",&dma_state.xpos,1);
    state_save_register_INT32("ghost_yunit",0,"dma_y",&dma_state.ypos,1);
    state_save_register_INT32("ghost_yunit",0,"dma_width",&dma_state.width,1);
    state_save_register_INT32("ghost_yunit",0,"dma_height",&dma_state.height,1);
    state_save_register_UINT16("ghost_yunit",0,"dma_palette",&dma_state.palette,1);
    state_save_register_UINT16("ghost_yunit",0,"dma_color",&dma_state.color,1);
    ghost_yunit_machine_state();
'''
    video='#include "state.h"\nextern void ghost_yunit_machine_state(void);\n'+video
    video=video.replace('memset(&dma_state, 0, sizeof(dma_state));','memset(&dma_state, 0, sizeof(dma_state));\n'+registration)
    (BUILD/'midyunit_video_state.c').write_text(video)
    machine=(VENDOR/'src/machine/midyunit_machine.c').read_text()
    machine='#include "state.h"\n'+machine+'''
void ghost_yunit_machine_state(void){
 state_save_register_UINT16("ghost_yunit",0,"prot_result",&prot_result,1);
 state_save_register_UINT16("ghost_yunit",0,"prot_sequence",prot_sequence,3);
 state_save_register_UINT8("ghost_yunit",0,"prot_index",&prot_index,1);
 state_save_register_UINT8("ghost_yunit",0,"cmos_enable",&cmos_w_enable,1);
}
'''
    (BUILD/'midyunit_machine_state.c').write_text(machine)
    root_driver='''#include "driver.h"
struct GameDriver driver_0={__FILE__,0,"",0,0,0,0,0,0,0,0,NOT_A_DRIVER};
extern struct GameDriver driver_mkla4;
const struct GameDriver *drivers[]={&driver_mkla4,0};
const struct GameDriver *test_drivers[]={0};
const int total_drivers=2;
'''
    (BUILD/'ghost_driver.c').write_text(root_driver)
    common=(VENDOR/'Makefile.common').read_text()
    prefix=common[:common.index('CPUS+=Z80@')]
    resolver=common[common.index('# List of CPU core'):]
    common=prefix+'\nCPUS=TMS34010@ M6809@\nSOUNDS=YM2151_ALT@ DAC@ HC55516@ OKIM6295@ SAMPLES@\nSOURCES_C += $(CORE_DIR)/machine/midyunit_machine.c $(CORE_DIR)/vidhrdw/midyunit_vidhrdw.c $(CORE_DIR)/sndhrdw/williams_sndhrdw.c\n'+resolver
    (BUILD/'Makefile.core').write_text(common)
    (BUILD/'describe.mk').write_text('CORE_DIR := src\ninclude '+(BUILD/'Makefile.core').as_posix()+'\n$(info SOURCES::$(SOURCES_C))\n$(info FLAGS::$(COREDEFINES) $(CPUDEFS) $(SOUNDDEFS) $(INCFLAGS))\nall:;\n')
    ndk=Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/ndk/27.2.12479018'
    result=subprocess.check_output([str(ndk/'prebuilt/windows-x86_64/bin/make.exe'),'-f',str(BUILD/'describe.mk')],cwd=VENDOR,text=True)
    lines=result.splitlines();sources=next(s[9:] for s in lines if s.startswith('SOURCES::')).split();flags=next(s[7:] for s in lines if s.startswith('FLAGS::')).split()
    sources=[str((VENDOR/s).resolve()).replace('\\','/') for s in sources if s!='src/driver.c']+[str(BUILD/'ghost_driver.c').replace('\\','/'),str(BUILD/'midyunit_selected.c').replace('\\','/')]
    replacements={str((VENDOR/'src/vidhrdw/midyunit_vidhrdw.c').resolve()).replace('\\','/'):(BUILD/'midyunit_video_state.c').as_posix(),str((VENDOR/'src/machine/midyunit_machine.c').resolve()).replace('\\','/'):(BUILD/'midyunit_machine_state.c').as_posix()}
    sources=[replacements.get(s,s) for s in sources]
    # Future JNI/headless integration is in our own adapter, outside the vendored tree.
    if (ROOT/'arcade/bridge.c').exists():sources.extend([(ROOT/'arcade/bridge.c').as_posix(),(ROOT/'arcade/unrelated_platforms.c').as_posix()])
    includes=[str((VENDOR/f[2:]).resolve()).replace('\\','/') for f in flags if f.startswith('-I')]
    includes.extend((VENDOR/'src'/name).as_posix() for name in ('machine','vidhrdw','sndhrdw'))
    definitions=[f[2:] for f in flags if f.startswith('-D')]+['ANDROID','SPLIT_CORE','HAVE_ZLIB','LSB_FIRST','ALIGN_INTS','ALIGN_SHORTS']
    (BUILD/'sources.json').write_text(json.dumps(dict(sources=[str(Path(s).relative_to(ROOT)).replace('\\','/') for s in sources],includes=[str(Path(s).relative_to(ROOT)).replace('\\','/') for s in includes],definitions=definitions)))
    content='cmake_minimum_required(VERSION 3.16)\nproject(ghostkombat C)\nset(CMAKE_C_STANDARD 90)\nadd_library(ghostkombat SHARED\n'+''.join('  "'+s+'"\n' for s in sources)+')\ntarget_include_directories(ghostkombat PRIVATE\n'+''.join(' "'+s+'"\n' for s in includes)+')\ntarget_compile_definitions(ghostkombat PRIVATE '+ ' '.join('"'+s+'"' for s in definitions)+')\ntarget_compile_options(ghostkombat PRIVATE -O2 -fPIC -fvisibility=hidden -ffp-contract=off -fsigned-char -ffunction-sections -fdata-sections -Wno-implicit-function-declaration -Wno-incompatible-pointer-types -Wno-int-conversion)\ntarget_link_options(ghostkombat PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)\ntarget_link_libraries(ghostkombat m log)\n'
    (BUILD/'CMakeLists.txt').write_text(content)
    print('Minimal emulator source files:',len(sources))
if __name__=='__main__':prepare()

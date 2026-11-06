"""Stable callback identifiers for snapshots shared between Linux x64 and Android ARM64."""
from pathlib import Path
import json,subprocess,re,os
ROOT=Path(__file__).resolve().parent;BUILD=ROOT/'arcade-build'
spec=json.loads((BUILD/'sources.json').read_text());sources=[ROOT/p for p in spec['sources']]
ndk=Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64'
clang=ndk/'bin/clang.exe'
flags=['--target=aarch64-linux-android24','--sysroot='+str(ndk/'sysroot')]+['-D'+v for v in spec['definitions']]+['-I'+str(ROOT/p) for p in spec['includes']]
replacements={};registrars=[];includes=[]
outdir=BUILD/'portable';outdir.mkdir(exist_ok=True)
for index,path in enumerate(sources):
    if path.name in ('bridge.c','unrelated_platforms.c','ghost_driver.c'):continue
    raw=path.read_text(errors='replace')
    if path.name!='timer.c' and not re.search(r'\bvoid\s+\w+\s*\(\s*int\b',raw):continue
    processed=subprocess.run([str(clang),*flags,'-E',str(path)],capture_output=True,text=True)
    if processed.returncode:raise RuntimeError(processed.stderr[-1800:])
    functions=sorted(set(re.findall(r'\bvoid\s+(\w+)\s*\(\s*int(?:\s+\w+)?\s*\)\s*\{',processed.stdout)))
    if not functions and path.name!='timer.c':continue
    # Each callback remains in its original translation unit, including static callbacks.
    registrar='gg_register_callbacks_'+str(index);registrars.append(registrar)
    suffix='\nextern void gg_callback_register(unsigned,void (*)(int));\nvoid '+registrar+'(void){\n'
    for function in functions:
        name=str(path.relative_to(ROOT)).replace('\\','/')+':'+function
        stable=2166136261
        for b in name.encode():stable=((stable^b)*16777619)&0xffffffff
        suffix+=' gg_callback_register(0x%08xu,%s);\n'%(stable,function)
    suffix+='}\n'
    if path.name=='timer.c':
        raw=raw.replace('static INT32 function_distance(size_t function)', 'extern unsigned gg_callback_id(void (*)(int));\nextern void (*gg_callback_pointer(unsigned))(int);\nextern void gg_callbacks_init(void);\nstatic INT32 function_distance(size_t function)')
        start=raw.index('\tconst char *version = GIT_VERSION;',raw.index('static void timer_fingerprint'))
        end=raw.index('\n}',start)
        raw=raw[:start]+'\tint i;for(i=0;i<SS_FINGERPRINT;i++)fingerprint[i]=0x47470000+i;'+raw[end:]
        raw=raw.replace('timer->callback ? function_distance((size_t)timer->callback) : 0','timer->callback ? (INT32)gg_callback_id(timer->callback) : 0')
        raw=raw.replace('(void (*)(int))((size_t)timer_init + (size_t)(ptrdiff_t)ss_callback[i])','gg_callback_pointer((unsigned)ss_callback[i])')
        # Populate the complete static mapping before even the first frame.
        raw=raw.replace('void timer_init(void)\n{','extern void gg_callbacks_init(void);\nvoid timer_init(void)\n{\n gg_callbacks_init();')
    if path.name=='tms34010.c':raw=raw.replace('state.IOregs, 16);','state.IOregs, 64);')
    def local_include(match):
        header=path.parent/match.group(1)
        return '#include "'+header.resolve().as_posix()+'"' if header.is_file() else match.group(0)
    raw=re.sub(r'#include\s+"([^"]+)"',local_include,raw)
    target=outdir/(str(index)+'_'+path.name);target.write_text(raw+suffix)
    replacements[path.as_posix()]=target.as_posix();includes.append((target,path.parent))
registry='''#include <stdint.h>
static struct {unsigned id;void (*fn)(int);} callbacks[1024];static int count;
void gg_callback_register(unsigned id,void (*fn)(int)){int i;for(i=0;i<count;i++)if(callbacks[i].id==id)return;if(count<1024){callbacks[count].id=id;callbacks[count++].fn=fn;}}
unsigned gg_callback_id(void (*fn)(int)){int i;for(i=0;i<count;i++)if(callbacks[i].fn==fn)return callbacks[i].id;return 0;}
void (*gg_callback_pointer(unsigned id))(int){int i;for(i=0;i<count;i++)if(callbacks[i].id==id)return callbacks[i].fn;return 0;}
'''
registry+=''.join('extern void '+r+'(void);\n' for r in registrars)+'void gg_callbacks_init(void){count=0;'+''.join(r+'();' for r in registrars)+'}\n'
reg=BUILD/'portable_callbacks.c';reg.write_text(registry)
cmake=BUILD/'CMakeLists.txt';text=cmake.read_text()
for old,new in replacements.items():text=text.replace('"'+old+'"','"'+new+'"')
text=text.replace('add_library(ghostkombat SHARED','add_library(ghostkombat SHARED\n "'+reg.as_posix()+'"')
for target,parent in includes:text+='\nset_source_files_properties("'+target.as_posix()+'" PROPERTIES COMPILE_OPTIONS "-I'+parent.as_posix()+'")\n'
cmake.write_text(text)
spec['sources']=[str(Path(replacements.get(p.as_posix(),p.as_posix())).relative_to(ROOT)).replace('\\','/') for p in sources]+[str(reg.relative_to(ROOT)).replace('\\','/')]
spec['includes']+=list(dict.fromkeys(str(p.relative_to(ROOT)).replace('\\','/') for _,p in includes))
(BUILD/'sources.json').write_text(json.dumps(spec))
print('Portable callback registry:',len(registrars),'translation units')

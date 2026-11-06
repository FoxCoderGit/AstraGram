"""Build the same original-game emulation core for the authoritative room worker."""
from deploy import connect,run
from pathlib import Path
import tarfile,shlex
ROOT=Path(__file__).resolve().parent
archive=ROOT/'arcade-sources.tar.gz'
with tarfile.open(archive,'w:gz') as output:
    for directory in ('vendor/mame2003-plus','arcade'):
        for file in (ROOT/directory).rglob('*'):
            if file.is_file() and '.git' not in file.parts:output.add(file,arcname=file.relative_to(ROOT).as_posix())
    for name in ('ghost_driver.c','midyunit_selected.c','midyunit_video_state.c','midyunit_machine_state.c','sources.json'):output.add(ROOT/'arcade-build'/name,arcname='arcade-build/'+name)
    output.add(ROOT/'arcade-build/portable_callbacks.c',arcname='arcade-build/portable_callbacks.c')
    for file in (ROOT/'arcade-build/portable').glob('*.c'):
        # Generated absolute local header references become source-relative on Linux.
        import io
        data=file.read_bytes().replace(ROOT.as_posix().encode(),b'/opt/ghostgram-kombat-build/core')
        info=tarfile.TarInfo('arcade-build/portable/'+file.name);info.size=len(data);output.addfile(info,io.BytesIO(data))
code=r'''import json,pathlib,subprocess
root=pathlib.Path('/opt/ghostgram-kombat-build/core')
data=json.loads((root/'arcade-build/sources.json').read_text())
text='cmake_minimum_required(VERSION 3.16)\nproject(ghostkombat C)\nset(CMAKE_C_STANDARD 99)\nadd_library(ghostkombat SHARED\n'
text+=''.join('"'+str(root/p)+'"\n' for p in data['sources'])+')\ntarget_include_directories(ghostkombat PRIVATE '+' '.join('"'+str(root/p)+'"' for p in data['includes'])+')\n'
text+='target_compile_definitions(ghostkombat PRIVATE '+' '.join(data['definitions'])+')\n'
text+='target_compile_options(ghostkombat PRIVATE -O2 -fPIC -fvisibility=hidden -ffp-contract=off -fsigned-char -ffunction-sections -fdata-sections -Wno-implicit-function-declaration -Wno-incompatible-pointer-types -Wno-int-conversion)\ntarget_link_options(ghostkombat PRIVATE -Wl,--gc-sections)\ntarget_link_libraries(ghostkombat m pthread dl)\n'
(root/'CMakeLists.txt').write_text(text.replace('\\n','\n'))
subprocess.run(['cmake','-S',str(root),'-B',str(root/'build'),'-DCMAKE_BUILD_TYPE=Release'],check=True)
subprocess.run(['cmake','--build',str(root/'build'),'-j','2'],check=True)
print('Authoritative original-game core built.')
'''
with connect() as c:
    print(run(c,'command -v cmake >/dev/null || (apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq cmake build-essential)'))
    with c.open_sftp() as s:
        s.put(str(archive),'/opt/ghostgram-kombat-build/arcade-sources.tar.gz')
        with s.open('/opt/ghostgram-kombat-build/build_core.py','w') as f:f.write(code)
    print(run(c,'install -d -m 755 /opt/ghostgram-kombat-build/core; tar -xzf /opt/ghostgram-kombat-build/arcade-sources.tar.gz -C /opt/ghostgram-kombat-build/core; python3 /opt/ghostgram-kombat-build/build_core.py'))

"""Fetch only the requested MK1 ROM archive and public emulator source. No executables from ROM sites."""
from pathlib import Path
import requests,hashlib,json,zipfile
ROOT=Path(__file__).resolve().parent
def fetch(url,path):
    response=requests.get(url,stream=True,timeout=(15,60));response.raise_for_status()
    count=0
    with path.open('wb') as output:
        for block in response.iter_content(131072):
            count+=len(block)
            if count>100*1024*1024:raise ValueError('Unexpected download size')
            output.write(block)
    print(path.name,count,hashlib.sha256(path.read_bytes()).hexdigest())
def main():
    roms=ROOT/'original';roms.mkdir(exist_ok=True)
    for identifier in ('arcade_mk','MAME2003_Reference_Set_MAME0.78_ROMs_CHDs_Samples'):
        try:
            response=requests.get('https://archive.org/metadata/'+identifier,timeout=30);response.raise_for_status();metadata=response.json()
            matches=[f for f in metadata.get('files',[]) if f.get('name','').lower().split('/')[-1]=='mk.zip']
            print(identifier,'matching archives',len(matches))
            for record in matches[:1]:
                url='https://archive.org/download/'+identifier+'/'+requests.utils.quote(record['name'],safe='/')
                try:
                    fetch(url,roms/'mk.zip')
                    with zipfile.ZipFile(roms/'mk.zip') as archive:
                        bad=archive.testzip()
                        if bad:raise ValueError('Corrupt ROM: '+bad)
                        print('ROM archive valid:',len(archive.infolist()),'files')
                    (roms/'source.json').write_text(json.dumps(dict(url=url,sha256=hashlib.sha256((roms/'mk.zip').read_bytes()).hexdigest()),indent=2))
                    return
                except Exception as error:print(type(error).__name__,str(error)[:180])
        except Exception as error:print(identifier,type(error).__name__,str(error)[:180])
    raise SystemExit('No accessible original archive downloaded.')
if __name__=='__main__':main()

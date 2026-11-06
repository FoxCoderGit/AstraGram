"""Update contract against isolated DB/files and synthetic Android manifests."""
import base64
import io
import json
import struct
import zipfile
from unittest.mock import patch
import unittest
import apk_manifest
from test_admin_web import OwnerConsole, server, web


def manifest(package=server.APP, code=7131, version='test', major=0, utf8=True):
    strings=['manifest','package',package,'http://schemas.android.com/apk/res/android','versionCode','versionName',version,'versionCodeMajor']
    encoded=[]
    for text in strings:
        raw=text.encode('utf-8' if utf8 else 'utf-16-le')
        encoded.append(bytes([len(text),len(raw)])+raw+b'\0' if utf8 else struct.pack('<H',len(text))+raw+b'\0\0')
    offsets=[]; payload=b''
    for text in encoded: offsets.append(len(payload)); payload+=text
    payload+=b'\0'*(-len(payload)%4)
    pool=struct.pack('<HH6I',1,28,28+len(strings)*4+len(payload),len(strings),0,0x100 if utf8 else 0,28+len(strings)*4,0)+struct.pack('<'+'I'*len(offsets),*offsets)+payload
    def attribute(ns,name,raw,kind,value): return struct.pack('<IIIHBBI',ns,name,raw,8,0,kind,value)
    attrs=attribute(0xffffffff,1,2,3,2)+attribute(3,4,0xffffffff,0x10,code)+attribute(3,5,6,3,6)+attribute(3,7,0xffffffff,0x10,major)
    node=struct.pack('<HHIII',0x102,16,36+len(attrs),1,0xffffffff)+struct.pack('<II6H',0xffffffff,0,20,20,4,0,0,0)+attrs
    return struct.pack('<HHI',3,8,8+len(pool)+len(node))+pool+node


class Updates(OwnerConsole):
    def upload(self, package=server.APP, code=7131, version='test', meta=None):
        apk=io.BytesIO()
        with zipfile.ZipFile(apk,'w') as archive:
            archive.writestr('AndroidManifest.xml',manifest(package,code,version));archive.writestr('classes.dex',b'fixture')
        data={'version':version,'build':108,'notes':'changes','mandatory':False}
        if meta:data.update(meta)
        with patch.object(web,'verify_apk'):
            return self.call('/admin/api/releases/upload',{},method='POST',raw=apk.getvalue(),extra={'X-Release-Metadata':base64.b64encode(json.dumps(data).encode()).decode()})[1]['id']
    def test_real_manifest_identity_formats(self):
        for utf8 in (True,False):
            path=server.DATA/'fixture.apk'
            with zipfile.ZipFile(path,'w') as archive:archive.writestr('AndroidManifest.xml',manifest(code=17,major=1,utf8=utf8))
            info=apk_manifest.identity(path)
            self.assertEqual(server.APP,info['package']);self.assertEqual((1<<32)+17,info['version_code'])
    def test_release_code_is_read_from_apk_and_mandatory_is_signed(self):
        self.login();rid=self.upload(code=7131,meta={'build':1,'mandatory':True})
        self.call('/admin/api/releases/action',{'id':rid,'action':'publish'})
        _,policy=self.call('/v1/policy');release=json.loads(server.unb64(policy['payload']))['update']
        self.assertEqual(7131,release['version_code']);self.assertTrue(release['mandatory']);self.assertEqual(1,release['build'])
    def test_publish_orders_by_version_code_not_ghost_build(self):
        self.login();high=self.upload(code=7131,meta={'build':1})
        self.call('/admin/api/releases/action',{'id':high,'action':'publish'})
        low=self.upload(code=7130,meta={'build':9999})
        self.rejected('older_release',lambda:self.call('/admin/api/releases/action',{'id':low,'action':'publish'}))
    def test_wrong_package_version_and_critical_type_rejected(self):
        self.login()
        self.rejected('invalid_apk',lambda:self.upload(package='com.other.app'))
        self.rejected('invalid_apk',lambda:self.upload(meta={'version':'different'}))
        self.rejected('bad_request',lambda:self.upload(meta={'mandatory':'yes'}))
    def test_apk_range_resume_and_head(self):
        self.login();rid=self.upload();self.call('/admin/api/releases/action',{'id':rid,'action':'publish'})
        content=(server.DATA/'releases'/(rid+'.apk')).read_bytes()
        h,body=self.call('/downloads/'+rid+'.apk',extra={'Range':'bytes=16-'})
        self.assertEqual(206,h.status);self.assertEqual(content[16:],body)
        self.assertEqual('bytes 16-'+str(len(content)-1)+'/'+str(len(content)),h.sent['Content-Range'])
        self.rejected('range_not_satisfiable',lambda:self.call('/downloads/'+rid+'.apk',extra={'Range':'bytes='+str(len(content))+'-'}))
        h,body=self.call('/downloads/'+rid+'.apk',method='HEAD');self.assertEqual(b'',body)
    def test_existing_release_migration_uses_apk(self):
        self.login();rid=self.upload(code=7131)
        with server.db() as c:c.execute('UPDATE app_releases SET version_code=0 WHERE id=?',(rid,))
        web.initialize(server)
        with server.db() as c:self.assertEqual(7131,c.execute('SELECT version_code FROM app_releases WHERE id=?',(rid,)).fetchone()[0])


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(Updates(name) for name in Updates.__dict__ if name.startswith('test_'))


if __name__=='__main__':unittest.main(verbosity=2)

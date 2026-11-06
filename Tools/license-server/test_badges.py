"""Badge administration and signed public snapshots; isolated data only."""
import json
import unittest
from test_admin_web import OwnerConsole, server, web
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding


class Badges(OwnerConsole):
    def snapshot(self):
        _,envelope=self.call('/v1/badges')
        raw=server.unb64(envelope['payload'])
        self.signer.public_key().verify(server.unb64(envelope['signature']),raw,padding.PKCS1v15(),hashes.SHA256())
        return json.loads(raw)
    def test_empty_public_snapshot_is_signed(self):
        data=self.snapshot();self.assertEqual([],data['badges']);self.assertEqual('badges',data['kind']);self.assertEqual(server.APP,data['app']);self.assertEqual(86400,data['until']-data['issued_at'])
    def test_default_supporter_change_creator_and_revoke(self):
        self.login();before=self.snapshot()['revision']
        self.call('/admin/api/badges',{'user_id':'5330087923','label':'private admin label'})
        data=self.snapshot();self.assertEqual([{'user_id':'5330087923','type':'supporter'}],data['badges']);self.assertGreater(data['revision'],before);self.assertNotIn('private admin label',json.dumps(data))
        self.call('/admin/api/badges',{'user_id':'5330087923','type':'creator'})
        self.assertEqual('creator',self.snapshot()['badges'][0]['type'])
        self.call('/admin/api/badges',{'user_id':'5330087923','action':'delete'})
        self.assertEqual([],self.snapshot()['badges'])
    def test_admin_state_lists_badges(self):
        self.login();self.call('/admin/api/badges',{'user_id':'11'})
        _,state=self.call('/admin/api/state');self.assertEqual('11',state['badges'][0]['user_id'])
    def test_bad_id_role_and_type_rejected(self):
        self.login()
        for uid in ['0','-1','@example','9223372036854775808','11;DELETE',11,None]:
            self.rejected('bad_request',lambda:self.call('/admin/api/badges',{'user_id':uid}))
        self.rejected('bad_request',lambda:self.call('/admin/api/badges',{'user_id':'11','type':'admin'}))
    def test_mutation_requires_session_and_csrf(self):
        self.rejected('login_required',lambda:self.call('/admin/api/badges',{'user_id':'11'}))
        self.login();self.csrf='wrong'
        self.rejected('csrf',lambda:self.call('/admin/api/badges',{'user_id':'11'}))
        self.assertEqual([],self.snapshot()['badges'])
    def test_badge_script_served(self):
        h,body=self.call('/admin/badges.js');self.assertEqual(200,h.status);self.assertIn(b'renderBadges',body)


def load_tests(loader,tests,pattern):return unittest.TestSuite(Badges(name) for name in Badges.__dict__ if name.startswith('test_'))
if __name__=='__main__':unittest.main(verbosity=2)

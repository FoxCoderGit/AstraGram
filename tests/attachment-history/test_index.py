"""Exercise the actual indexed lookup against existing attachment-history rows."""
import re
import sqlite3
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[2] / 'TMessagesProj/src/main/java/org/telegram/messenger/GhostAttachmentHistory.java'
TEXT = SOURCE.read_text(encoding='utf-8')
LOOKUP = re.search(r'rawQuery\("(SELECT dialog,mid,date FROM refs[^"\n]+)"', TEXT)[1]
INDEX = re.search(r'execSQL\("(CREATE INDEX IF NOT EXISTS refs_first[^"\n]+)"', TEXT)[1]

class AttachmentHistoryTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('CREATE TABLE refs(media TEXT NOT NULL,dialog INTEGER NOT NULL,mid INTEGER NOT NULL,date INTEGER NOT NULL,PRIMARY KEY(media,dialog,mid))')
        self.db.executemany('INSERT INTO refs VALUES(?,?,?,?)', [('owner:d:'+str(i),i,20,2000) for i in range(10000)])
        self.db.execute('INSERT INTO refs VALUES(?,?,?,?)', ('owner:d:7',-100,4,1000))
        self.db.execute(INDEX)
    def tearDown(self):
        self.db.close()
    def test_upgrade_keeps_history_and_uses_index(self):
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM refs').fetchone()[0],10001)
        self.assertEqual(self.db.execute(LOOKUP,('owner:d:7',)).fetchone(),(-100,4,1000))
        plan = str(self.db.execute('EXPLAIN QUERY PLAN '+LOOKUP,('owner:d:7',)).fetchall())
        self.assertIn('refs_first',plan)
    def test_oldest_reference_survives_repeated_observation(self):
        self.db.execute('INSERT OR IGNORE INTO refs VALUES(?,?,?,?)',('owner:d:7',7,20,2000))
        self.assertEqual(self.db.execute(LOOKUP,('owner:d:7',)).fetchall(),[(-100,4,1000)])
    def test_tie_order_and_unknown_media(self):
        self.db.executemany('INSERT INTO refs VALUES(?,?,?,?)',[('ties',3,8,100),('ties',2,8,100),('ties',9,7,100)])
        self.assertEqual(self.db.execute(LOOKUP,('ties',)).fetchone(),(9,7,100))
        self.assertEqual(self.db.execute(LOOKUP,('unknown',)).fetchall(),[])

if __name__=='__main__':unittest.main()

"""Offline regression tests. Network calls are prohibited in this suite."""
import contextlib
import csv
import io
import json
import os
import random
import tempfile
import unittest
from datetime import date
from pathlib import Path
from unittest.mock import MagicMock, patch

import generate
import model
import mongo_tool
import mysql_tool
from validate import validate

class SeedTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory()
        cls.root=Path(cls.temp.name)
        cls.data=cls.root/'fixture'
        cls.kwargs=dict(count=100,scenario_count=40,vu_accounts=10,as_of='2026-11-18',dataset='test')
        cls.m=generate.generate(cls.data,**cls.kwargs)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def test_complete_fixture_is_valid(self):
        result=validate(self.data)
        self.assertEqual(result['counts']['members'],100)
        self.assertEqual(result['counts']['matching_history'],10)
        self.assertEqual(result['counts']['chat_messages'],160)
        self.assertEqual(result['counts']['order_grant_ledger'],2)

    def test_generation_is_byte_reproducible(self):
        second=self.root/'second'
        generate.generate(second,**self.kwargs)
        self.assertEqual((self.data/'manifest.json').read_bytes(),(second/'manifest.json').read_bytes())

    def test_age_matches_application_before_and_after_birthday(self):
        self.assertEqual(model.korean_age(date(2003,11,19),date(2026,11,18)),23)
        self.assertEqual(model.korean_age(date(2003,11,18),date(2026,11,18)),24)
        for today in (date(2026,1,1),date(2026,6,14),date(2026,12,31),date(2028,2,29)):
            rng=random.Random(123)
            for age in range(18,30):
                for _ in range(12):
                    birth=date.fromisoformat(generate.birth_for_age(age,today,rng))
                    self.assertEqual(model.korean_age(birth,today),age)

    def test_no_candidates_is_valid_request_fixture(self):
        out=self.root/'empty-pool'
        generate.generate(out,count=100,scenario_count=40,vu_accounts=1,
                          as_of='2026-11-18',preset='no-candidates',dataset='empty')
        validate(out)
        candidates=list(model.read_rows(out,'matching_candidate'))
        self.assertEqual([r['member_id'] for r in candidates if r['is_matchable']],[1000041])
        self.assertEqual(list(model.read_rows(out,'matching_history')),[])

    def test_uniform_and_skewed_pools(self):
        values=generate.uniform(['MALE','FEMALE'],99,99)
        self.assertEqual(abs(values.count('MALE')-values.count('FEMALE')),1)
        out=self.root/'skew'
        generate.generate(out,**{**self.kwargs,'distribution':'skewed','history_count':0})
        validate(out)
        bulk=[r for r in model.read_rows(out,'profile') if r['member_id']>=1000041]
        self.assertEqual(sum(r['gender']=='MALE' for r in bulk),48)

    def test_refuses_overwrite_and_invalid_counts(self):
        with self.assertRaisesRegex(ValueError,'already exists'): generate.generate(self.data,**self.kwargs)
        for changes in ({'count':0},{'vu_accounts':1000},{'scenario_count':100}):
            with self.assertRaises(ValueError): generate.generate(self.root/'invalid',**{**self.kwargs,**changes})

    def test_checksum_catches_file_corruption(self):
        import shutil
        broken=self.root/'broken'; shutil.copytree(self.data,broken)
        with open(broken/'members.tsv','a') as f: f.write('corruption\n')
        with self.assertRaisesRegex(ValueError,'Checksum'): validate(broken)

    def test_relation_check_even_with_updated_checksum(self):
        import shutil
        broken=self.root/'wrong-age'; shutil.copytree(self.data,broken)
        p=broken/'matching_candidate.tsv'
        with p.open(newline='') as f: rows=list(csv.reader(f,delimiter='\t'))
        rows[1][rows[0].index('age')]='99'
        with p.open('w',newline='') as f: csv.writer(f,delimiter='\t',lineterminator='\n').writerows(rows)
        manifest=json.loads((broken/'manifest.json').read_text())
        manifest['files'][p.name]['sha256']=model.digest(p)
        (broken/'manifest.json').write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError,'age mismatch'): validate(broken)

    def test_all_previews_prohibit_network(self):
        with patch('socket.socket',side_effect=AssertionError('Network must not be used')), \
             patch('mysql_tool.connect',side_effect=AssertionError('DB must not be connected')), \
             contextlib.redirect_stdout(io.StringIO()) as output:
            for module in (mysql_tool,mongo_tool):
                for action in ('plan','load','clean'): module.main([action,str(self.data)])
        self.assertIn('"database_connected": false',output.getvalue())

    def test_writes_require_stopped_services(self):
        with patch('mysql_tool.connect',side_effect=AssertionError('Must not connect')):
            with self.assertRaisesRegex(ValueError,'Stop application'):
                mysql_tool.main(['load',str(self.data),'--apply'])

    def test_remote_or_prod_settings_rejected(self):
        for env in ({'LOCAL_MYSQL_HOST':'db.example.test'}, {'SPRING_PROFILES_ACTIVE':'aws'}):
            with patch.dict(os.environ,env,clear=True), self.assertRaises(ValueError): mysql_tool.settings(None)

    def test_env_is_parsed_not_executed(self):
        env=self.root/'env'
        env.write_text("SPRING_PROFILES_ACTIVE=local\nLOCAL_MYSQL_PASSWORD='literal$(touch /tmp/never)password'\nRDS_PASSWORD=ignored\n")
        with patch.dict(os.environ,{},clear=True): cfg=mysql_tool.settings(env)
        self.assertEqual(cfg['LOCAL_MYSQL_PASSWORD'],'literal$(touch /tmp/never)password')
        self.assertNotIn('RDS_PASSWORD',cfg)

    def test_failed_load_rolls_back(self):
        conn=MagicMock(); cur=conn.cursor.return_value.__enter__.return_value
        cur.fetchone.return_value=(1,)
        cur.fetchall.return_value=()
        calls=[]
        def fail_after_members(sql, rows):
            calls.append(sql)
            cur.rowcount=len(rows)
            if len(calls)==3: raise RuntimeError('simulated insert failure')
        cur.executemany.side_effect=fail_after_members
        with patch('mysql_tool.connect',return_value=conn), \
             patch('mysql_tool.check_schema'),patch('mysql_tool.resolve_catalog',return_value=({}, {}, {})), \
             patch('mysql_tool.get_run',return_value=None),patch('mysql_tool.check_collisions'), \
             patch('mysql_tool.ensure_registry'):
            with self.assertRaisesRegex(RuntimeError,'insert failure'): mysql_tool.execute('load',self.data,{})
        self.assertEqual(len(calls),3)
        self.assertIn('`members`',calls[1])
        conn.rollback.assert_called_once(); conn.commit.assert_not_called(); conn.close.assert_called_once()

    def test_existing_dataset_does_not_insert_again(self):
        conn=MagicMock(); cur=conn.cursor.return_value.__enter__.return_value; cur.fetchone.return_value=(1,)
        with patch('mysql_tool.connect',return_value=conn),patch('mysql_tool.check_schema'), \
             patch('mysql_tool.resolve_catalog'),patch('mysql_tool.get_run',return_value=('hash',100,'absent')), \
             patch('mysql_tool.check_owned'),patch('mysql_tool.verify_rows'),contextlib.redirect_stdout(io.StringIO()):
            mysql_tool.execute('load',self.data,{})
        cur.executemany.assert_not_called(); conn.commit.assert_not_called()

    def test_cleanup_requires_recorded_ownership_and_mongo_cleanup(self):
        cur=MagicMock()
        with patch('mysql_tool.get_run',return_value=None),self.assertRaisesRegex(ValueError,'no ownership'):
            mysql_tool.check_owned(cur,self.data,self.m)
        with patch('mysql_tool.check_owned',return_value=('hash',100,'loading')),self.assertRaisesRegex(ValueError,'Clean MongoDB first'):
            mysql_tool.clean_rows(cur,self.data,self.m)
        self.assertFalse(any('DELETE' in str(c) for c in cur.execute.call_args_list))

    def test_cleanup_refuses_mixed_matching_pair(self):
        cur=MagicMock(); cur.fetchone.return_value=(1,)
        with patch('mysql_tool.check_owned',return_value=('hash',100,'clean')),self.assertRaisesRegex(ValueError,'Mixed'):
            mysql_tool.clean_rows(cur,self.data,self.m)
        self.assertFalse(any('DELETE' in str(c) for c in cur.execute.call_args_list))

    def test_mongo_refuses_mixed_room(self):
        db=MagicMock(); db.chat_rooms.find.return_value=[{'initiatorUserId':1,'targetUserId':999}]
        with self.assertRaisesRegex(ValueError,'Mixed'): mongo_tool.validate_existing_scope(db,[1,2])
        db.chat_messages.delete_many.assert_not_called()

    def test_mongo_dates_match_timezone_aware_bson_reads(self):
        from bson import BSON
        from bson.codec_options import CodecOptions
        for collection in model.COLLECTIONS:
            document=next(mongo_tool.mongo_documents(self.data,collection))
            roundtrip=BSON.encode(document).decode(codec_options=CodecOptions(tz_aware=True))
            self.assertEqual(document,roundtrip)
            self.assertIsNotNone(document['createdAt'].tzinfo)

    def test_sql_value_normalization(self):
        self.assertEqual(mysql_tool.normalized(b'\x01'),1)
        self.assertEqual(mysql_tool.normalized(date(2026,11,18)),'2026-11-18')

if __name__=='__main__': unittest.main()

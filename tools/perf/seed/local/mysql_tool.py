#!/usr/bin/env python3
"""Local MySQL loader. plan/default load/default clean never connect to a DB."""
import argparse
import csv
import hashlib
import json
import os
import re
import shlex
from collections import Counter
from datetime import date, datetime
from pathlib import Path

from model import ROOT, TABLES, columns, qualified, chunks, digest, read_rows, load_manifest
from validate import validate, require

RUNS = '`comatching_user`.`_local_seed_runs`'
OWNERS = '`comatching_user`.`_local_seed_members`'
LOCK = 'comatching_local_seed_tools_v1'
KEYS = ('SPRING_PROFILES_ACTIVE','LOCAL_MYSQL_HOST','LOCAL_MYSQL_PORT',
        'LOCAL_MYSQL_USERNAME','LOCAL_MYSQL_PASSWORD','LOCAL_MONGO_PORT')

# Keep the fixture file format stable while explicitly populating fields added to
# the application since v1. Approved historical orders must not look ungranted.
RUNTIME_COLUMNS = {
    'profile': ('profile_version',),
    'matching_candidate': ('last_profile_version',),
    'purchase_order': ('grant_status','grant_attempt','refund_status',
                       'refund_started_at','refund_started_by','refund_completed_at','refund_completed_by'),
}

def insert_columns(table):
    return columns(table) + list(RUNTIME_COLUMNS.get(table, ()))

def runtime_values(table, row):
    result = dict(row)
    if table == 'profile':
        result['profile_version'] = 0
    elif table == 'matching_candidate':
        result['last_profile_version'] = 0
    elif table == 'purchase_order':
        approved = result['status'] == 'APPROVED'
        result.update(grant_status='GRANTED' if approved else 'NOT_REQUESTED',
                      grant_attempt=1 if approved else 0, refund_status='NONE',
                      refund_started_at=None, refund_started_by=None,
                      refund_completed_at=None, refund_completed_by=None)
    return result

def settings(path):
    result = {}
    if path and Path(path).exists():
        for line in Path(path).read_text().splitlines():
            line = line.strip().removeprefix('export ')
            if not line or line.startswith('#') or '=' not in line: continue
            key,value=line.split('=',1)
            if key.strip() not in KEYS: continue
            tokens=shlex.split(value,comments=True,posix=True)
            require(len(tokens)<=1, f'Invalid env value: {key}')
            result[key.strip()]=tokens[0] if tokens else ''
    for key in KEYS:
        if key in os.environ: result[key]=os.environ[key]
    require(result.get('SPRING_PROFILES_ACTIVE','local')=='local','Only the local profile is allowed')
    require(result.get('LOCAL_MYSQL_HOST','127.0.0.1') in ('127.0.0.1','localhost','::1'), 'Only a loopback MySQL host is allowed')
    port=int(result.get('LOCAL_MYSQL_PORT','3306'))
    require(1 <= port <= 65535,'Invalid port')
    return result

def connect(config):
    import pymysql
    return pymysql.connect(host=config.get('LOCAL_MYSQL_HOST','127.0.0.1'),
        port=int(config.get('LOCAL_MYSQL_PORT','3306')),user=config.get('LOCAL_MYSQL_USERNAME','root'),
        password=config.get('LOCAL_MYSQL_PASSWORD',''),database='comatching_user',
        charset='utf8mb4',autocommit=False,
        connect_timeout=5,read_timeout=120,write_timeout=120,local_infile=False)

def owned(expression):
    return f'EXISTS (SELECT 1 FROM {OWNERS} s WHERE s.dataset=%s AND s.member_id={expression})'

def scope(table, alias='t'):
    if table in ('profile_hobby','profile_tag'):
        return f'EXISTS (SELECT 1 FROM comatching_user.profile p WHERE p.profile_id={alias}.profile_id AND {owned("p.member_id")})'
    if table in ('purchase_order_item','order_decision_logs','order_grant_ledger'):
        return f'EXISTS (SELECT 1 FROM comatching_item.purchase_order o WHERE o.id={alias}.order_id AND {owned("o.member_id")})'
    if table=='matching_history':
        return f'({owned(alias+".member_id")} OR {owned(alias+".partner_id")})'
    return owned(alias+'.member_id')

def scoped(cur, sql, dataset):
    cur.execute(sql, (dataset,)*sql.count('%s'))

def registry_exists(cur):
    cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='comatching_user' AND table_name='_local_seed_runs'")
    return cur.fetchone()[0]==1

def get_run(cur, dataset):
    if not registry_exists(cur): return None
    cur.execute(f'SELECT manifest_sha256, member_count, mongo_state FROM {RUNS} WHERE dataset=%s',(dataset,))
    return cur.fetchone()

def ensure_registry(cur):
    # Metadata only; application schema is never created or altered by this tool.
    cur.execute(f'''CREATE TABLE IF NOT EXISTS {RUNS} (
        dataset VARCHAR(48) NOT NULL PRIMARY KEY, manifest_sha256 CHAR(64) NOT NULL,
        member_count INT NOT NULL, mongo_state VARCHAR(16) NOT NULL DEFAULT 'absent',
        created_at DATETIME NOT NULL) ENGINE=InnoDB''')
    cur.execute(f'''CREATE TABLE IF NOT EXISTS {OWNERS} (
        member_id BIGINT NOT NULL PRIMARY KEY, dataset VARCHAR(48) NOT NULL,
        KEY idx_seed_dataset(dataset)) ENGINE=InnoDB''')

def check_schema(cur, directory):
    """Compare the actual schema before any data write, including drifted enums."""
    for table,(schema,_) in TABLES.items():
        cur.execute('SELECT ENGINE FROM information_schema.tables WHERE table_schema=%s AND table_name=%s',(schema,table))
        row=cur.fetchone(); require(row and row[0]=='InnoDB',f'{schema}.{table}: missing or non-InnoDB table')
        cur.execute('''SELECT index_name,non_unique,column_name FROM information_schema.statistics
            WHERE table_schema=%s AND table_name=%s ORDER BY index_name,seq_in_index''',(schema,table))
        index_columns,index_unique={},{}
        for index_name,non_unique,column in cur.fetchall():
            index_columns.setdefault(index_name,[]).append(column)
            index_unique[index_name]=not non_unique
        unique_sets={tuple(cols) for name,cols in index_columns.items() if index_unique[name]}
        required_unique={'members':[('member_id',),('email',)],'profile':[('profile_id',),('member_id',)],
            'matching_history':[('id',),('member_id','partner_id'),('pair_key',)],
            'order_grant_ledger':[('id',),('order_id','order_item_id')],
            'roulette_history':[('id',),('member_id','roulette_type','participation_date')]}.get(table,[])
        for key in required_unique:
            require(key in unique_sets,f'{table}: missing unique constraint {key}')
        if table=='matching_candidate':
            require(('gender','is_matchable','random_key') in {tuple(v) for v in index_columns.values()},
                    'matching_candidate: missing sampling index')
        cur.execute('''SELECT column_name,is_nullable,column_default,extra,column_type,character_maximum_length
            FROM information_schema.columns WHERE table_schema=%s AND table_name=%s''',(schema,table))
        actual={r[0]:r[1:] for r in cur.fetchall()}
        require(set(insert_columns(table))<=set(actual),f'{table}: missing columns')
        for name,(nullable,default,extra,typ,length) in actual.items():
            if name not in insert_columns(table):
                require(nullable=='YES' or default is not None or 'auto_increment' in extra or 'GENERATED' in extra,
                        f'{table}.{name}: unhandled mandatory column')
        enums={name:set(re.findall(r"'([^']*)'",v[3])) for name,v in actual.items() if v[3].startswith('enum(')}
        for row in read_rows(directory,table):
            row=runtime_values(table,row)
            for name,value in row.items():
                nullable,default,extra,typ,length=actual[name]
                if value is None:
                    if (table,name) not in (('purchase_order','product_id'),('roulette_history','reward_id')):
                        require(nullable=='YES',f'{table}.{name}: NULL rejected by actual schema')
                else:
                    if name in enums: require(value in enums[name],f'{table}.{name}: invalid DB enum {value}')
                    if isinstance(value,str) and length is not None: require(len(value)<=length,f'{table}.{name}: value too long')

def resolve_catalog(cur,directory):
    refs=json.loads((Path(directory)/'catalog_refs.json').read_text())
    product_ids,reward_ids={},{}
    for code in set(refs['products'].values()):
        cur.execute('SELECT id,price FROM comatching_item.product WHERE code=%s',(code,))
        rows=cur.fetchall(); require(len(rows)==1 and rows[0][1]==1000,f'Missing/changed product: {code}')
        pid=rows[0][0]
        cur.execute('SELECT item_type,quantity FROM comatching_item.product_reward WHERE product_id=%s',(pid,))
        require(cur.fetchall()==(('MATCHING_TICKET',1),),f'Changed product rewards: {code}')
        product_ids[code]=pid
    for spec in refs['rewards'].values():
        key=tuple(spec[k] for k in ('roulette_type','reward_type','reward_name','quantity'))
        if key in reward_ids: continue
        cur.execute('''SELECT id FROM comatching_item.roulette_reward
            WHERE roulette_type=%s AND reward_type=%s AND reward_name=%s AND quantity=%s''',key)
        rows=cur.fetchall(); require(len(rows)==1,f'Missing/ambiguous roulette reward: {key}')
        reward_ids[key]=rows[0][0]
    return refs,product_ids,reward_ids

def resolved_rows(directory, table, catalog):
    refs,products,rewards=catalog
    for r in read_rows(directory,table):
        if table=='purchase_order': r['product_id']=products[r['product_code']]
        if table=='roulette_history':
            spec=refs['rewards'][str(r['id'])]
            r['reward_id']=rewards[tuple(spec[k] for k in ('roulette_type','reward_type','reward_name','quantity'))]
        yield runtime_values(table,r)

def check_collisions(cur,directory):
    # Reject even partial collisions. No REPLACE, INSERT IGNORE, or broad range deletion.
    for table in TABLES:
        if table=='candidate_hobby_categories': continue
        key=columns(table)[0]; lo,hi=None,None
        for r in read_rows(directory,table):
            v=r[key]; lo=v if lo is None else min(lo,v); hi=v if hi is None else max(hi,v)
        if lo is None: continue
        cur.execute(f'SELECT COUNT(*) FROM {qualified(table)} WHERE `{key}` BETWEEN %s AND %s',(lo,hi))
        require(cur.fetchone()[0]==0,f'{table}: reserved ID interval already occupied; choose another --start-id')
    for batch in chunks(read_rows(directory,'members')):
        cur.execute('SELECT COUNT(*) FROM comatching_user.members WHERE email IN ('+','.join(['%s']*len(batch))+')',
                    [r['email'] for r in batch])
        require(cur.fetchone()[0]==0,'Email collision')
    for batch in chunks(r for r in read_rows(directory,'profile') if r['nickname']!='탈퇴한 사용자'):
        cur.execute('SELECT COUNT(*) FROM comatching_user.profile WHERE nickname IN ('+','.join(['%s']*len(batch))+')',
                    [r['nickname'] for r in batch])
        require(cur.fetchone()[0]==0,'Nickname collision')

def normalized(value):
    if isinstance(value,datetime): return value.strftime('%Y-%m-%d %H:%M:%S')
    if isinstance(value,date): return value.isoformat()
    if isinstance(value,bytes) and len(value)==1: return int.from_bytes(value,'big')
    return value

def row_hash(values):
    return hashlib.sha256(json.dumps([normalized(v) for v in values],ensure_ascii=False,separators=(',',':')).encode()).digest()

def verify_rows(cur,directory,m,catalog):
    for table in TABLES:
        cols=insert_columns(table)
        expected=Counter(row_hash([r[c] for c in cols]) for r in resolved_rows(directory,table,catalog))
        query=f'SELECT '+','.join('t.`'+c+'`' for c in cols)+f' FROM {qualified(table)} t WHERE {scope(table)}'
        scoped(cur,query,m['dataset'])
        actual=Counter(row_hash(row) for row in cur.fetchall())
        require(actual==expected,f'{table}: DB differs from baseline (missing={sum((expected-actual).values())}, extra/changed={sum((actual-expected).values())})')

def check_owned(cur,directory,m):
    run=get_run(cur,m['dataset'])
    require(run is not None,'Dataset was not loaded by this tool; no ownership, no deletion')
    require(run[0]==digest(Path(directory)/'manifest.json') and run[1]==m['count'],'Ownership manifest mismatch; use the original dataset files')
    cur.execute(f'SELECT member_id FROM {OWNERS} WHERE dataset=%s',(m['dataset'],))
    require({r[0] for r in cur.fetchall()}=={r['member_id'] for r in read_rows(directory,'members')},'Ownership member set mismatch')
    return run

def clean_rows(cur,directory,m):
    run=check_owned(cur,directory,m)
    require(run[2]=='clean','Clean MongoDB first, including app-created chats after a MySQL-only load')
    query=f'SELECT COUNT(*) FROM comatching_matching.matching_history t WHERE ({owned("t.member_id")}) <> ({owned("t.partner_id")})'
    scoped(cur,query,m['dataset'])
    require(cur.fetchone()[0]==0,'Mixed test/non-test matching history exists; refusing to delete shared records')
    query=f'''SELECT COUNT(*) FROM comatching_item.order_decision_logs t
        WHERE {owned('t.decided_by_admin_id')} AND NOT ({scope('order_decision_logs')})'''
    scoped(cur,query,m['dataset'])
    require(cur.fetchone()[0]==0,'Test administrator has decisions on non-test orders; refusing cleanup')
    # Existing app-created rows belong to the same member set, even after withdrawal masks email.
    for schema,table in [('comatching_item','order_outbox_events'),('comatching_item','purchase_request'),
                         ('comatching_item','pre_signup_item_grant_ledger')]:
        cur.execute('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s AND table_name=%s',(schema,table))
        if not cur.fetchone()[0]: continue
        condition=scope(table)
        if table=='order_outbox_events':
            condition=f"t.aggregate_type='ORDER' AND EXISTS (SELECT 1 FROM comatching_item.purchase_order o WHERE o.id=t.aggregate_id AND {owned('o.member_id')})"
        scoped(cur,f'DELETE t FROM `{schema}`.`{table}` t WHERE {condition}',m['dataset'])
    for table in reversed(TABLES):
        scoped(cur,f'DELETE t FROM {qualified(table)} t WHERE {scope(table)}',m['dataset'])
    cur.execute(f'DELETE FROM {OWNERS} WHERE dataset=%s',(m['dataset'],))
    cur.execute(f'DELETE FROM {RUNS} WHERE dataset=%s',(m['dataset'],))

def execute(action,directory,config):
    m=load_manifest(directory)
    conn=connect(config)
    locked=False
    try:
        with conn.cursor() as cur:
            if action in ('load','clean'):
                cur.execute('SELECT GET_LOCK(%s,0)',(LOCK,))
                require(cur.fetchone()[0]==1,'Another seed operation is running'); locked=True
                cur.execute("SET SESSION sql_mode='STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION'")
                cur.execute("SET SESSION time_zone='+09:00'")
            check_schema(cur,directory)
            catalog=resolve_catalog(cur,directory)
            run=get_run(cur,m['dataset'])
            if action=='check':
                if run: check_owned(cur,directory,m)
                else: check_collisions(cur,directory)
            elif action=='verify':
                check_owned(cur,directory,m); verify_rows(cur,directory,m,catalog)
            elif action=='load':
                if run:
                    check_owned(cur,directory,m); verify_rows(cur,directory,m,catalog)
                    print('Already loaded; baseline verified, no rows inserted.'); return
                check_collisions(cur,directory)
                ensure_registry(cur) # DDL commits; only metadata tables, before business-data transaction.
                conn.begin()
                cur.execute(f'INSERT INTO {RUNS} (dataset,manifest_sha256,member_count,created_at) VALUES (%s,%s,%s,NOW())',
                            (m['dataset'],digest(Path(directory)/'manifest.json'),m['count']))
                for batch in chunks(read_rows(directory,'members')):
                    cur.executemany(f'INSERT INTO {OWNERS} (member_id,dataset) VALUES (%s,%s)',
                                    [(r['member_id'],m['dataset']) for r in batch])
                for table in TABLES:
                    cols=insert_columns(table)
                    sql=f'INSERT INTO {qualified(table)} ('+','.join('`'+c+'`' for c in cols)+') VALUES ('+','.join(['%s']*len(cols))+')'
                    for batch in chunks(resolved_rows(directory,table,catalog)):
                        cur.executemany(sql,[[r[c] for c in cols] for r in batch])
                        require(cur.rowcount==len(batch),f'{table}: inserted row count mismatch')
                        cur.execute('SHOW WARNINGS'); require(not cur.fetchall(),f'{table}: MySQL warning; rolling back')
                    print(f"Prepared {table}: {m['counts'].get(table,0):,}",flush=True)
                verify_rows(cur,directory,m,catalog)
                conn.commit()
            elif action=='clean':
                conn.begin(); clean_rows(cur,directory,m); conn.commit()
    except BaseException:
        conn.rollback()
        raise
    finally:
        if locked:
            try:
                with conn.cursor() as cur: cur.execute('SELECT RELEASE_LOCK(%s)',(LOCK,))
            except Exception:
                pass # A dropped connection releases its own advisory lock; preserve the original error.
        conn.close()

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['plan','check','load','verify','clean'])
    p.add_argument('directory',type=Path)
    p.add_argument('--env',type=Path,default=ROOT/'.env.local')
    p.add_argument('--apply',action='store_true',help='Actually write to local MySQL for load/clean')
    p.add_argument('--services-stopped',action='store_true',help='Assert all app writers and load generators are stopped')
    args=p.parse_args(argv)
    result=validate(args.directory)
    m=load_manifest(args.directory)
    if args.action=='plan' or (args.action in ('load','clean') and not args.apply):
        print(json.dumps({'mode':'offline-preview','action':args.action,'dataset':m['dataset'],
            'members':m['count'],'counts':result['counts'],'database_connected':False,
            'writes_enabled':False,'load_order':list(TABLES)},ensure_ascii=False,indent=2))
        return
    if args.action in ('load','clean'):
        require(args.services_stopped,'Stop application writers, then pass --services-stopped')
    execute(args.action,args.directory,settings(args.env))
    print(f'{args.action}: complete')

if __name__=='__main__': main()

#!/usr/bin/env python3
"""Optional local MongoDB fixtures linked to an already loaded MySQL seed run."""
import argparse
import json
from pathlib import Path

from model import ROOT, COLLECTIONS, read_docs, load_manifest, chunks
from validate import validate, require
from mysql_tool import connect, settings, check_owned, RUNS, OWNERS, LOCK

def mongo_documents(directory, collection):
    from bson import json_util
    options = json_util.DEFAULT_JSON_OPTIONS.with_options(tz_aware=True)
    for doc in read_docs(directory,collection):
        yield json_util.loads(json.dumps(doc), json_options=options)

def scope_queries(ids):
    return {'chat_rooms':{'$or':[{'initiatorUserId':{'$in':ids}},{'targetUserId':{'$in':ids}}]},
            'user_blocks':{'$or':[{'blockerUserId':{'$in':ids}},{'blockedUserId':{'$in':ids}}]}}

def validate_existing_scope(db, ids):
    owners=set(ids); queries=scope_queries(ids)
    for r in db.chat_rooms.find(queries['chat_rooms']):
        require(r['initiatorUserId'] in owners and r['targetUserId'] in owners,
                'Mixed test/non-test chat room; refusing cleanup')
    for r in db.user_blocks.find(queries['user_blocks']):
        require(r['blockerUserId'] in owners and r['blockedUserId'] in owners,
                'Mixed test/non-test block; refusing cleanup')
    room_ids=[r['_id'] for r in db.chat_rooms.find(queries['chat_rooms'],{'_id':1})]
    # Refuse messages written by outsiders inside seed rooms and seed senders in other rooms.
    require(db.chat_messages.count_documents({'roomId':{'$in':room_ids},'senderId':{'$nin':ids}})==0,
            'Non-test sender in a test room')
    require(db.chat_messages.count_documents({'senderId':{'$in':ids},'roomId':{'$nin':room_ids}})==0,
            'Test sender in an unknown/non-test room')
    queries['chat_messages']={'roomId':{'$in':room_ids}}
    return queries

def verify(db,directory,ids):
    queries=validate_existing_scope(db,ids)
    for c in COLLECTIONS:
        expected={r['_id']:r for r in mongo_documents(directory,c)}
        actual={r['_id']:r for r in db[c].find(queries[c])}
        # Spring may add type hints when saving a document; application fields must still agree.
        for r in actual.values(): r.pop('_class',None)
        require(actual==expected,f'{c}: MongoDB differs from fixture baseline')

def execute(action,directory,config):
    from pymongo import MongoClient
    m=load_manifest(directory)
    sql=connect(config); mongo=None; locked=False
    try:
        with sql.cursor() as cur:
            cur.execute('SELECT GET_LOCK(%s,0)',(LOCK,))
            require(cur.fetchone()[0]==1,'Another seed operation is running'); locked=True
            run=check_owned(cur,directory,m)
            cur.execute(f'SELECT member_id FROM {OWNERS} WHERE dataset=%s',(m['dataset'],))
            ids=[r[0] for r in cur.fetchall()]
            port=int(config.get('LOCAL_MONGO_PORT','27017')); require(1 <= port <= 65535,'Invalid MongoDB port')
            mongo=MongoClient('127.0.0.1',port,serverSelectionTimeoutMS=5000,tz_aware=True)
            db=mongo['comatching_chat']; db.command('ping')
            if action=='verify': verify(db,directory,ids); return
            if action=='load' and run[2]=='loaded': verify(db,directory,ids); return
            if action=='load':
                require(run[2] in ('absent','clean'),'Partial Mongo load: run mongo clean before retrying')
                # Check every cross-store matching link before the first Mongo write.
                cur.execute(f'''SELECT h.id,h.member_id,h.partner_id FROM comatching_matching.matching_history h
                    JOIN {OWNERS} s ON s.member_id=h.member_id WHERE s.dataset=%s''',(m['dataset'],))
                matching={r[0]:(r[1],r[2]) for r in cur.fetchall()}
                for r in read_docs(directory,'chat_rooms'):
                    require(matching.get(r['matchingId'])==(r['initiatorUserId'],r['targetUserId']),'MySQL matching link missing/changed')
                queries=scope_queries(ids)
                for c,q in queries.items(): require(db[c].count_documents(q)==0,f'{c}: existing records for seed members')
                require(db.chat_messages.count_documents({'senderId':{'$in':ids}})==0,'Existing messages for seed members')
                # Check IDs and unique business keys against unrelated documents before ownership marker.
                for c in COLLECTIONS:
                    for batch in chunks(mongo_documents(directory,c)):
                        require(db[c].count_documents({'_id':{'$in':[r['_id'] for r in batch]}})==0,f'{c}: ID collision')
                        if c=='chat_rooms':
                            require(db[c].count_documents({'matchingId':{'$in':[r['matchingId'] for r in batch]}})==0,'Room matchingId collision')
                cur.execute(f'UPDATE {RUNS} SET mongo_state=%s WHERE dataset=%s',('loading',m['dataset']))
                sql.commit() # Durable partial-load marker; standalone Mongo cannot share MySQL transaction.
                for c in COLLECTIONS:
                    for batch in chunks(mongo_documents(directory,c)): db[c].insert_many(batch,ordered=True)
                verify(db,directory,ids)
                cur.execute(f'UPDATE {RUNS} SET mongo_state=%s WHERE dataset=%s',('loaded',m['dataset']))
                sql.commit()
            elif action=='clean':
                queries=validate_existing_scope(db,ids)
                for c in ('chat_messages','user_blocks','chat_rooms'): db[c].delete_many(queries[c])
                cur.execute(f'UPDATE {RUNS} SET mongo_state=%s WHERE dataset=%s',('clean',m['dataset']))
                sql.commit()
    except BaseException:
        sql.rollback(); raise
    finally:
        if locked:
            try:
                with sql.cursor() as cur: cur.execute('SELECT RELEASE_LOCK(%s)',(LOCK,))
            except Exception:
                pass
        sql.close()
        if mongo is not None: mongo.close()

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['plan','load','verify','clean'])
    p.add_argument('directory',type=Path)
    p.add_argument('--env',type=Path,default=ROOT/'.env.local')
    p.add_argument('--apply',action='store_true')
    p.add_argument('--services-stopped',action='store_true')
    args=p.parse_args(argv); report=validate(args.directory)
    if args.action=='plan' or (args.action in ('load','clean') and not args.apply):
        print(json.dumps({'mode':'offline-preview','action':args.action,'database_connected':False,
            'counts':{c:report['counts'][c] for c in COLLECTIONS}},indent=2)); return
    if args.action in ('load','clean'): require(args.services_stopped,'Stop application writers and pass --services-stopped')
    execute(args.action,args.directory,settings(args.env))
    print(f'{args.action}: complete')

if __name__=='__main__': main()

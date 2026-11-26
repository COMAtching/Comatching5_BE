#!/usr/bin/env python3
"""Offline integrity checks for generated files; no network access."""
import argparse
import csv
import json
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path

import bcrypt
from generate import domains
from model import TABLES, COLLECTIONS, MBTIS, CONTACTS, load_manifest, read_rows, read_docs, korean_age

def require(condition, message):
    if not condition:
        raise ValueError(message)

def validate(directory):
    d = Path(directory)
    m = load_manifest(d)
    today = date.fromisoformat(m['as_of'])
    credential = json.loads((d/'credentials.json').read_text())
    require(bcrypt.checkpw(credential['password'].encode(), credential['bcrypt'].encode()), 'Invalid BCrypt')
    members, profiles, candidates, histories, orders, order_items = {}, {}, {}, {}, {}, {}
    hobby_counts, candidate_hobbies, tag_counts = Counter(), Counter(), Counter()
    item_totals, history_totals = Counter(), Counter()
    withdrawn, approved_ledgers = set(), set()
    catalog = json.loads((d/'catalog_refs.json').read_text())
    hobbies, tags = domains(); hobbies = dict(hobbies); tags = set(tags)
    emails, nicknames, pairs, roulette_keys = set(), set(), set(), set()
    counts = {}
    for table in TABLES:
        seen, count = set(), 0
        for r in read_rows(d, table):
            count += 1
            if table != 'candidate_hobby_categories':
                pk = next(iter(r.values()))
                require(pk not in seen, f'{table}: duplicate primary key {pk}')
                seen.add(pk)
            if table == 'members':
                mid = r['member_id']; members[mid] = r
                require(m['start_id'] <= mid <= m['end_id'], 'Member ID outside manifest')
                require(r['email'] == f"{m['dataset']}.{mid}@example.test", 'Email ownership mismatch')
                require(r['email'].casefold() not in emails, 'Duplicate email'); emails.add(r['email'].casefold())
                require(r['role'] in ('ROLE_GUEST','ROLE_USER','ROLE_ADMIN'), 'Invalid role')
                require(r['status'] in ('ACTIVE','PENDING','DORMANT','SUSPENDED','BANNED','WITHDRAWN'), 'Invalid status')
                require(r['password'] == (None if r['status']=='WITHDRAWN' else credential['bcrypt']), 'Password mismatch')
                require((r['deleted_at'] is not None) == (r['status']=='WITHDRAWN'), 'Withdrawal timestamp mismatch')
            elif table == 'profile':
                mid = r['member_id']; require(mid in members, 'Orphan profile')
                require(mid not in profiles, 'Multiple profiles per member'); profiles[mid] = r
                require(r['profile_id'] == mid, 'Profile ID contract mismatch')
                require(r['gender'] in ('MALE','FEMALE') and r['contact_frequency'] in CONTACTS, 'Invalid profile enums')
                if members[mid]['status'] != 'WITHDRAWN':
                    require(r['mbti'] in MBTIS, 'Invalid MBTI')
                    require(r['nickname'].casefold() not in nicknames,'Duplicate nickname')
                    nicknames.add(r['nickname'].casefold())
                else:
                    require(r['is_matchable']==0 and r['mbti']=='UNKNOWN', 'Unmasked withdrawn profile')
            elif table == 'profile_hobby':
                require(r['profile_id'] in profiles, 'Orphan hobby')
                require(hobbies.get(r['name']) == r['category'], 'Invalid hobby/category pair')
                hobby_counts[(r['profile_id'], r['category'])] += 1
            elif table == 'profile_tag':
                require(r['profile_id'] in profiles and r['tag'] in tags, 'Invalid tag')
                tag_counts[(r['profile_id'],r['tag'])] += 1
                require(tag_counts[(r['profile_id'],r['tag'])]==1,'Duplicate member tag')
            elif table == 'matching_candidate':
                mid = r['member_id']; require(mid in profiles, 'Orphan candidate')
                p = profiles[mid]; candidates[mid] = r
                for k in ('profile_id','contact_frequency','gender','major','mbti','is_matchable'):
                    require(r[k] == p[k], f'Candidate/profile mismatch: {mid} {k}')
                require(r['age']==korean_age(date.fromisoformat(p['birth_date']),today), 'Candidate age mismatch')
                require(0 <= r['random_key'] < 1000000000,'Invalid random_key')
                require(members[mid]['status'] != 'WITHDRAWN', 'Withdrawn candidate')
            elif table == 'candidate_hobby_categories':
                require(r['member_id'] in candidates,'Orphan candidate hobby')
                candidate_hobbies[(r['member_id'],r['hobby_categories'])] += 1
            elif table == 'withdrawn_member':
                require(r['member_id'] in members and members[r['member_id']]['status']=='WITHDRAWN', 'Invalid tombstone')
                withdrawn.add(r['member_id'])
            elif table == 'matching_history':
                a,b = r['member_id'],r['partner_id']
                require(a in candidates and b in candidates and a != b, 'Invalid matching pair')
                require(candidates[a]['gender'] != candidates[b]['gender'], 'Same-gender history')
                key = f'{min(a,b)}:{max(a,b)}'
                require(r['pair_key']==key and key not in pairs, 'Invalid/duplicate pair key')
                pairs.add(key); histories[r['id']] = r
            elif table in ('item','item_history'):
                require(r['member_id'] in members and r['quantity'] >= 0, 'Invalid item owner/quantity')
                require(r['item_type'] in ('MATCHING_TICKET','OPTION_TICKET'), 'Invalid item type')
                (item_totals if table=='item' else history_totals)[(r['member_id'],r['item_type'])] += r['quantity']
            elif table == 'purchase_order':
                require(r['member_id'] in members, 'Orphan order')
                require(r['product_id'] is None and catalog['products'].get(str(r['id']))==r['product_code'], 'Invalid product lookup')
                require(r['status'] in ('PENDING','APPROVED','REJECTED','CANCELED','EXPIRED'), 'Invalid order status')
                require((r['decided_at'] is None)==(r['status']=='PENDING'), 'Order decision mismatch')
                orders[r['id']] = r
            elif table == 'purchase_order_item':
                require(r['order_id'] in orders and r['quantity']==1, 'Invalid order item')
                order_items[r['id']] = r
            elif table == 'order_grant_ledger':
                o = orders.get(r['order_id']); oi = order_items.get(r['order_item_id'])
                require(o and oi and o['status']=='APPROVED' and oi['order_id']==o['id'], 'Invalid grant')
                require(o['member_id']==r['member_id'] and oi['quantity']==r['quantity'], 'Grant details mismatch')
                require(r['order_item_id'] not in approved_ledgers,'Duplicate grant')
                approved_ledgers.add(r['order_item_id'])
            elif table == 'order_decision_logs':
                require(r['order_id'] in orders and orders[r['order_id']]['status']==r['to_status'], 'Invalid decision log')
            elif table == 'roulette_history':
                key = (r['member_id'],r['roulette_type'],r['participation_date'])
                ref = catalog['rewards'].get(str(r['id']))
                require(r['member_id'] in members and key not in roulette_keys, 'Duplicate/orphan roulette')
                require(ref and ref['roulette_type']==r['roulette_type'] and r['reward_id'] is None, 'Invalid reward lookup')
                require(r['participated_at'][:10]==r['participation_date'], 'Roulette date mismatch')
                roulette_keys.add(key)
        require(count == m['counts'].get(table,0), f'{table}: row count mismatch')
        counts[table] = count
    require(len(members)==m['count']==m['end_id']-m['start_id']+1,'Member count mismatch')
    require(set(profiles)=={mid for mid,r in members.items() if r['role']=='ROLE_USER'}, 'Profile coverage mismatch')
    require(set(candidates)==set(profiles)-withdrawn, 'Candidate coverage mismatch')
    require(withdrawn=={mid for mid,r in members.items() if r['status']=='WITHDRAWN'}, 'Tombstone coverage mismatch')
    require(hobby_counts==candidate_hobbies,'Hobby multiplicities differ')
    per_profile = Counter()
    for (pid,_),qty in hobby_counts.items(): per_profile[pid] += qty
    for mid in candidates: require(2 <= per_profile[mid] <= 10,'Invalid hobby count')
    require(item_totals==history_totals,'Item inventory/history mismatch')
    require(approved_ledgers=={iid for iid,r in order_items.items() if orders[r['order_id']]['status']=='APPROVED'},'Missing grants')
    room_map, room_matches, last_message, message_count = {}, set(), {}, Counter()
    for c in COLLECTIONS:
        seen, count = set(),0
        for r in read_docs(d,c):
            count += 1
            require(r['_id'] not in seen,'Duplicate Mongo ID'); seen.add(r['_id'])
            if c=='chat_rooms':
                h=histories.get(r['matchingId'])
                require(h and r['matchingId'] not in room_matches,'Invalid room matching ID')
                require((h['member_id'],h['partner_id'])==(r['initiatorUserId'],r['targetUserId']),'Room membership mismatch')
                require(r['status'] in ('ACTIVE','WAITING'),'Invalid room status')
                room_matches.add(r['matchingId']); room_map[r['_id']]=r
            elif c=='chat_messages':
                room=room_map.get(r['roomId'])
                require(room and r['senderId'] in (room['initiatorUserId'],room['targetUserId']),'Orphan/invalid sender')
                last_message[r['roomId']]=r; message_count[r['roomId']] += 1
            else:
                require(r['blockerUserId'] in members and r['blockedUserId'] in members,'Orphan block')
        require(count==m['counts'].get(c,0), f'{c}: count mismatch'); counts[c]=count
    require(room_matches==set(histories),'Missing room for matching history')
    for rid,room in room_map.items():
        require((message_count[rid]==0)==(room['status']=='WAITING'),'Room state mismatch')
        if room['status']=='ACTIVE':
            require(room['lastMessageInfo']=={'content':last_message[rid]['content'],'sentAt':last_message[rid]['createdAt']},'Last message mismatch')
    with open(d/'accounts.csv',newline='',encoding='utf-8') as f:
        accounts=list(csv.DictReader(f))
    require({int(r['memberId']) for r in accounts}==set(members) and len(accounts)==len(members),'Account file coverage')
    for r in accounts:
        user=members[int(r['memberId'])]
        require(all(r[k]==user[k] for k in ('email','role','status')),'Account file mismatch')
    with open(d/'vu_accounts.csv',newline='',encoding='utf-8') as f: vus=list(csv.DictReader(f))
    require(len(vus)==m['vu_accounts'] and len({r['memberId'] for r in vus})==len(vus),'VU count/uniqueness mismatch')
    for r in vus:
        u=members[int(r['memberId'])]
        require(u['status']=='ACTIVE' and u['role']=='ROLE_USER' and u['email']==r['email'],'Invalid VU account')
    return {'valid':True,'database_connected':False,'dataset':m['dataset'],'counts':counts}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('directory')
    print(json.dumps(validate(p.parse_args().directory),ensure_ascii=False,indent=2))

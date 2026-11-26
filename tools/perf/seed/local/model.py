"""Versioned file contract. No DB imports or connections in this module."""
import csv
import hashlib
import json
from datetime import date, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
VERSION = 1
TABLES = {
    'members': ('comatching_user', 'member_id:i email:s password:s? real_name:s? social_id:s? role:s social_type:s? status:s deleted_at:t?'),
    'profile': ('comatching_user', 'profile_id:i member_id:i birth_date:d point:i intro:s? major:s mbti:s nickname:s? profile_image_url:s? social_account_id:s? song:s? university:s contact_frequency:s gender:s social_account_type:s? is_matchable:i'),
    'profile_hobby': ('comatching_user', 'id:i profile_id:i name:s category:s'),
    'profile_tag': ('comatching_user', 'id:i profile_id:i tag:s'),
    'matching_candidate': ('comatching_matching', 'member_id:i profile_id:i age:i contact_frequency:s gender:s major:s mbti:s is_matchable:i random_key:i'),
    'candidate_hobby_categories': ('comatching_matching', 'member_id:i hobby_categories:s'),
    'withdrawn_member': ('comatching_matching', 'member_id:i withdrawn_at:t'),
    'matching_history': ('comatching_matching', 'id:i member_id:i partner_id:i pair_key:s favorite:i matched_at:t same_major_option:i age_option:s? min_age_offset:i? max_age_offset:i? contact_frequency:s? hobby_option:s? mbti_option:s? important_option:s?'),
    'item': ('comatching_item', 'id:i quantity:i expired_at:t member_id:i item_type:s'),
    'item_history': ('comatching_item', 'id:i member_id:i item_type:s history_type:s quantity:i description:s created_at:t'),
    'purchase_order': ('comatching_item', 'id:i member_id:i product_id:i? product_code:s requested_item_name:s requester_real_name:s requester_username:s requested_price:i expected_price:i status:s requested_at:t expires_at:t decided_at:t?'),
    'purchase_order_item': ('comatching_item', 'id:i order_id:i item_type:s quantity:i'),
    'order_grant_ledger': ('comatching_item', 'id:i order_id:i order_item_id:i member_id:i item_type:s quantity:i granted_at:t created_at:t updated_at:t'),
    'order_decision_logs': ('comatching_item', 'id:i order_id:i from_status:s to_status:s decided_by_admin_id:i? reason:s decided_at:t created_at:t updated_at:t'),
    # reward_id is resolved by the loader against existing catalogue, never guessed.
    'roulette_history': ('comatching_item', 'id:i member_id:i reward_id:i? roulette_type:s participated_at:t participation_date:d reward_granted:i'),
}
COLLECTIONS = ('chat_rooms', 'chat_messages', 'user_blocks')
SCENARIOS = ('active', 'guest', 'admin', 'pending', 'dormant', 'suspended', 'banned',
             'withdrawn', 'not_matchable', 'no_items', 'zero_items', 'expired_items',
             'expiring_items', 'one_ticket', 'no_options', 'age_19', 'age_27', 'age_28',
             'minimal_profile', 'rich_profile')
MBTIS = [a+b+c+d for a in 'IE' for b in 'NS' for c in 'TF' for d in 'JP']
CONTACTS = ['FREQUENT', 'NORMAL', 'RARE']
MAJORS = ['컴퓨터정보공학부', '미디어기술콘텐츠학과', '경영학과', '경제학과', '국어국문학과',
          '영어영문학부', '심리학과', '사회학과', '법학과', '행정학과', '화학과', '수학과',
          '물리학과', '간호대학', '약학대학', '음악과', '인공지능학과', '데이터사이언스학과', '생명공학과']

def columns(table):
    return [x.split(':')[0] for x in TABLES[table][1].split()]

def qualified(table):
    return '`%s`.`%s`' % (TABLES[table][0], table)

def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def read_rows(directory, table):
    specs = [x.split(':') for x in TABLES[table][1].split()]
    with open(Path(directory) / (table + '.tsv'), newline='', encoding='utf-8') as f:
        reader = csv.reader(f, delimiter='\t')
        if next(reader, None) != columns(table):
            raise ValueError(f'{table}: header mismatch')
        for line, values in enumerate(reader, 2):
            if len(values) != len(specs):
                raise ValueError(f'{table}:{line}: column count mismatch')
            row = {}
            for (name, typ), value in zip(specs, values):
                if value == '\\N':
                    if not typ.endswith('?'):
                        raise ValueError(f'{table}:{line}: NULL {name}')
                    value = None
                elif typ[0] == 'i':
                    value = int(value)
                elif typ[0] == 'd':
                    date.fromisoformat(value)
                elif typ[0] == 't':
                    datetime.strptime(value, '%Y-%m-%d %H:%M:%S')
                row[name] = value
            yield row

def read_docs(directory, collection):
    with open(Path(directory) / (collection + '.jsonl'), encoding='utf-8') as f:
        for line in f:
            yield json.loads(line)

def load_manifest(directory):
    directory = Path(directory)
    m = json.loads((directory / 'manifest.json').read_text())
    if m['version'] != VERSION or m['tables'] != {t: columns(t) for t in TABLES}:
        raise ValueError('Unsupported manifest schema')
    expected = {t+'.tsv' for t in TABLES} | {c+'.jsonl' for c in COLLECTIONS} | {
        'accounts.csv', 'vu_accounts.csv', 'scenarios.json', 'catalog_refs.json', 'credentials.json'}
    if set(m['files']) != expected:
        raise ValueError('Missing or unexpected manifest file')
    for name, meta in m['files'].items():
        p = directory / name
        if p.is_symlink() or digest(p) != meta['sha256']:
            raise ValueError(f'Checksum mismatch: {name}')
    return m

def chunks(iterable, size=500):
    batch = []
    for row in iterable:
        batch.append(row)
        if len(batch) == size:
            yield batch
            batch = []
    if batch:
        yield batch

def korean_age(birth, today):
    # Match KoreanAge.java: completed years + 1 (not calendar-year Korean age).
    return today.year - birth.year - ((today.month, today.day) < (birth.month, birth.day)) + 1

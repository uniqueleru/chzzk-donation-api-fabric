#!/usr/bin/env python3
"""Read-only CHZZK join probe. Read source hash from stdin; never print credentials."""
import json
import re
import sys
import time
import urllib.request
import urllib.parse
from websockets.sync.client import connect

source = sys.stdin.readline().strip()
if not re.fullmatch(r'[0-9a-f]{32}', source):
    raise SystemExit('Invalid source format')

def get(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0', 'Origin': 'https://chzzk.naver.com'})
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)['content']

result = {'source_resolved': False, 'token_received': False, 'join_ack': False, 'frames': [], 'paid_donation_verified': False}
try:
    cid = get('https://api.chzzk.naver.com/manage/v1/chats/sources/' + source)['chatChannelId']
    result['source_resolved'] = bool(cid)
    token = get('https://comm-api.game.naver.com/nng_main/v1/chats/access-token?channelId=' + urllib.parse.quote(cid, safe='') + '&chatType=STREAMING')['accessToken']
    result['token_received'] = bool(token)
    with connect('wss://kr-ss1.chat.naver.com/chat', open_timeout=10, close_timeout=1) as ws:
        ws.send(json.dumps({'ver':'2','cmd':100,'svcid':'game','cid':cid,'bdy':{'uid':None,'devType':2001,'accTkn':token,'auth':'READ','recentMessageCount':0},'tid':1}))
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            frame = json.loads(ws.recv(timeout=10))
            cmd = frame.get('cmd')
            result['frames'].append({'cmd':cmd,'retCode':frame.get('retCode'),'body_keys':list(frame.get('bdy',{})) if isinstance(frame.get('bdy'),dict) else [],'top_keys':list(frame)})
            if cmd == 0:
                ws.send(json.dumps({'ver':'2','cmd':10000,'svcid':'game','cid':cid}))
            if cmd == 10100:
                result['join_ack'] = frame.get('retCode',0) == 0
                break
except Exception as exc:
    result['error_class'] = type(exc).__name__
print(json.dumps(result, ensure_ascii=False, indent=2))
raise SystemExit(0 if result['join_ack'] else 1)

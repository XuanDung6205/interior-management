"""Load a private Gemini configuration, verify embeddings or run the packaged demo.

Credentials are passed only through child environments or an HTTPS header.
The provider host/model is fixed; no production schema reset or seed is performed.
"""
import argparse
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import urllib.request
import urllib.error
import tv3_release

ROOT=Path(__file__).resolve().parents[1]
ALLOWED={'GEMINI_API_KEY','VECTOR_ENABLED','VECTOR_BATCH_SIZE','VECTOR_INTERVAL_SECONDS','VECTOR_MAX_ATTEMPTS'}
ENDPOINT='https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent'

def private_environment(path):
    values={}
    for line in Path(path).read_text(encoding='utf-8-sig').splitlines():
        line=line.strip()
        if not line or line.startswith('#'):continue
        key,separator,value=line.partition('=')
        if not separator or key not in ALLOWED or key in values or any(ord(c)<32 for c in value):raise ValueError('Invalid private vector configuration.')
        values[key]=value
    env={key:value for key,value in os.environ.items() if not key.startswith('VECTOR_') and key!='GEMINI_API_KEY'}
    env.update(values)
    return env

def valid_embedding(values):
    return isinstance(values,list) and len(values)==768 and all(type(value) in {int,float} and abs(value)<=3.4028235e38 and math.isfinite(value) for value in values) and any(value!=0 for value in values)

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):raise ValueError('Provider redirect refused.')

def check(env):
    key=env.get('GEMINI_API_KEY','')
    if not key:raise ValueError('Missing private Gemini key.')
    payload={'model':'models/gemini-embedding-001','content':{'parts':[{'text':'Sofa vải kem và bàn trà gỗ sồi cho phòng khách nhỏ.'}]},'taskType':'RETRIEVAL_DOCUMENT','outputDimensionality':768}
    request=urllib.request.Request(ENDPOINT,data=json.dumps(payload).encode(),headers={'Content-Type':'application/json','x-goog-api-key':key,'User-Agent':'InteriorManagement-VectorCheck/1.0'})
    with urllib.request.build_opener(NoRedirect).open(request,timeout=15) as response:
        raw=response.read(65537)
    if len(raw)>65536:raise ValueError('Provider response too large.')
    values=json.loads(raw).get('embedding',{}).get('values')
    if not valid_embedding(values):raise ValueError('Invalid provider vector.')
    print(json.dumps({'provider':'Gemini','model':'gemini-embedding-001','dimensions':768,'embeddingVerified':True}))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['check','run'])
    parser.add_argument('--config',type=Path,default=ROOT/'.env.gemini.local')
    parser.add_argument('--database',default='furniture_shop_tv3_dev')
    parser.add_argument('--port',type=int,default=8086)
    parser.add_argument('--sepay-config',type=Path)
    parser.add_argument('--windows-trust',action='store_true')
    args=parser.parse_args()
    try:
        env=private_environment(args.config)
        if args.action=='check':check(env);return 0
        command,child=tv3_release.prepare_run(tv3_release.RELEASE_DIR/tv3_release.JAR_NAME,args.database,args.port)
        for key in ALLOWED:
            child.pop(key,None)
            if key in env:child[key]=env[key]
        if args.sepay_config:
            import tv3_sepay
            sepay=tv3_sepay.private_environment(args.sepay_config)
            for key in tv3_sepay.ALLOWED:
                child.pop(key,None)
                if key in sepay:child[key]=sepay[key]
        if args.windows_trust:
            if os.name!='nt':raise ValueError('Windows trust is Windows only.')
            command[1:1]=['-Djavax.net.ssl.trustStoreType=Windows-ROOT','-Djavax.net.ssl.trustStore=NONE']
        print('Starting the local vector demo on port '+str(args.port)+'.',flush=True)
        return subprocess.call(command,env=child,cwd=ROOT)
    except (ValueError,OSError,RuntimeError):
        print('Vector setup failed. Check the private config, provider access, DB schema and release; no secret values are printed.',file=sys.stderr)
        return 1

if __name__=='__main__':sys.exit(main())

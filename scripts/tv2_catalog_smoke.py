"""Exercise the Docker catalog on an isolated demo DB; save sanitized evidence only.

TV3_DEMO_PASSWORD must be supplied through environment. No secret is printed.
"""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',default='http://127.0.0.1:8080')
    parser.add_argument('--output',default='target/tv2-verification.json')
    args=parser.parse_args()
    client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf=None
    def request(path,body=None,method='GET',expected=200,form=False):
        nonlocal csrf
        headers={'Accept':'application/json'}
        if method!='GET':
            if csrf is None: csrf=request('/api/csrf')
            headers[csrf['headerName']]=csrf['token']
        data=None
        if body is not None:
            headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
            data=(urllib.parse.urlencode(body) if form else json.dumps(body,ensure_ascii=False)).encode()
        try:
            with client.open(urllib.request.Request(args.base_url+path,data=data,headers=headers,method=method),timeout=30) as response:
                status=response.status
                payload=json.load(response)
        except urllib.error.HTTPError as error:
            status=error.code
            payload=json.load(error)
        if status!=expected:
            raise RuntimeError(f'{method} {path.split("?")[0]} expected {expected}, got {status}: '+str(payload.get('code','unknown')))
        return payload
    runtime=request('/api/commerce/runtime')
    if not runtime.get('isolatedData'): raise ValueError('Mutation checks refuse a non-isolated database.')
    checks=[]
    facets=request('/api/catalog/facets')
    assert facets['materials'] and facets['collections']
    checks.append('public facets and collections')
    products=request('/api/catalog/products?query=NN-DEMO&size=100')
    assert products['totalElements']==50
    sample=request('/api/catalog/products/demo-sofa-01-01')
    assert len(sample['variants'])==3 and len(sample['images'])>=3
    for slug in ('demo-sofa-01-02','demo-ghe-an-06-05','demo-ban-console-10-05'):
        assert len(request('/api/catalog/products/'+slug)['variants'])==3
    checks.append('50 demo products, multi-SKU detail and gallery')
    material=next(m['slug'] for m in facets['materials'] if m['slug']=='demo-vai-boc')
    filtered=request('/api/catalog/products?'+urllib.parse.urlencode({'material':material,'minPrice':'10000000','maxWidth':'3000','room':'phong-khach'}))
    assert filtered['totalElements']>0
    request('/api/catalog/products?minPrice=100&maxPrice=1',expected=400)
    checks.append('combined filters, ranges and hierarchy')
    request('/api/admin/catalog/options',expected=401)
    password=os.environ.get('TV3_DEMO_PASSWORD','')
    if not password: raise ValueError('Supply TV3_DEMO_PASSWORD through environment.')
    request('/api/session/login',{'username':'tv2.admin','password':password},method='POST',form=True)
    csrf=None
    options=request('/api/admin/catalog/options')
    payload={'name':'Sản phẩm kiểm chứng TV2','slug':'tv2-verification-product','categoryId':options['categories'][0]['id'],
        'brandId':None,'status':'DRAFT','warrantyMonths':12,'assemblyRequired':False,'shortDescription':'Fixture kiểm chứng, không phải hàng demo.',
        'description':'','careInstructions':'','roomIds':[],'collectionIds':[],
        'variants':[{'sku':'TV2-VERIFY-SKU','variantKey':'default','name':'Mặc định','status':'ACTIVE','price':'1234567.89','compareAtPrice':None,'leadTimeDays':1,'materialIds':[]}],
        'images':[{'url':'/images/catalog/NN-DEMO-03-01.jpg','altText':'Ảnh kiểm chứng','variantId':None,'sortOrder':0}],'specifications':[]}
    existing=request('/api/admin/catalog/products?query=tv2-verification-product')
    if existing['items']:
        current=request('/api/admin/catalog/products/'+str(existing['items'][0]['id']))
        payload['version']=current['version']; payload['variants'][0]['id']=current['variants'][0]['id']
        current=request('/api/admin/catalog/products/'+str(current['id']),payload,'PUT')
    else: current=request('/api/admin/catalog/products',payload,'POST',201)
    original_id=current['variants'][0]['id']
    current['status']='ACTIVE'
    saved=request('/api/admin/catalog/products/'+str(current['id']),current,'PUT')
    assert request('/api/catalog/products/tv2-verification-product')['variants'][0]['price']=='1234567.89'
    saved['variants'][0]['price']='2345678.90'
    updated=request('/api/admin/catalog/products/'+str(saved['id']),saved,'PUT')
    assert updated['variants'][0]['id']==original_id
    assert request('/api/catalog/products/tv2-verification-product')['variants'][0]['price']=='2345678.90'
    conflict=request('/api/admin/catalog/products/'+str(saved['id']),saved,'PUT',409)
    assert conflict['code']=='CATALOG_STALE'
    updated['status']='ARCHIVED'
    request('/api/admin/catalog/products/'+str(updated['id']),updated,'PUT')
    request('/api/catalog/products/tv2-verification-product',expected=404)
    checks.append('admin create, publish, cache invalidation, price update, SKU identity, stale version, archive')
    timings={}
    for name,path in [('list','/api/catalog/products?query=NN-DEMO&size=12'),('detail','/api/catalog/products/demo-sofa-01-01')]:
        samples=[]
        for _ in range(20):
            start=time.perf_counter(); request(path); samples.append((time.perf_counter()-start)*1000)
        timings[name]={'requests':len(samples),'medianMs':round(statistics.median(samples),2),'p95Ms':round(sorted(samples)[18],2)}
    report={'verifiedAtUtc':datetime.now(timezone.utc).isoformat(),'environment':'local Docker Compose, 50 demo products / 150 demo SKUs',
        'checks':checks,'readTimings':timings,'scope':'Catalog only; not a load test or live AI/payment-provider verification.'}
    output=Path(args.output); output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__=='__main__': main()

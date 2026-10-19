#!/usr/bin/env python3
"""Fail closed on Java dependency findings; native Go is checked from source by govulncheck."""
import concurrent.futures,json,math,pathlib,sys,urllib.request

def cvss3(vector):
 d=dict(x.split(':',1) for x in vector.split('/')[1:]);scope=d['S']=='C'
 impact=1-(1-{'N':0,'L':.22,'H':.56}[d['C']])*(1-{'N':0,'L':.22,'H':.56}[d['I']])*(1-{'N':0,'L':.22,'H':.56}[d['A']])
 impact=7.52*(impact-.029)-3.25*(impact-.02)**15 if scope else 6.42*impact
 exploit=8.22*{'N':.85,'A':.62,'L':.55,'P':.2}[d['AV']]*{'L':.77,'H':.44}[d['AC']]*({'N':.85,'L':.68,'H':.5} if scope else {'N':.85,'L':.62,'H':.27})[d['PR']]*{'N':.85,'R':.62}[d['UI']]
 return 0 if impact<=0 else math.ceil((min((impact+exploit)*(1.08 if scope else 1),10)-1e-10)*10)/10

def request(url,payload=None):
 data=None if payload is None else json.dumps(payload).encode()
 with urllib.request.urlopen(urllib.request.Request(url,data=data,headers={'Content-Type':'application/json','User-Agent':'Moondrop-Supply-Chain-Check'}),timeout=60) as r:return json.load(r)

def high(v):
 if v.get('withdrawn'):return False
 label=v.get('database_specific',{}).get('severity','').upper()
 if label in ('HIGH','CRITICAL'):return True
 scores=[]
 for s in v.get('severity',[]):
  raw=s.get('score','')
  if raw.startswith(('CVSS:3.0/','CVSS:3.1/')):scores.append(cvss3(raw))
  else:
   try:scores.append(float(raw))
   except ValueError:pass
 if scores:return max(scores)>=7
 if label in ('LOW','MODERATE','MEDIUM'):return False
 return True # An unknown score must not silently bypass the release guard.

def main():
 bom=json.loads(pathlib.Path(sys.argv[1]).read_text());purls=sorted({c['purl'] for c in bom.get('components',[]) if c.get('purl','').startswith('pkg:maven/')})
 if not purls:raise SystemExit('Refusing vulnerability check with no Java dependency inventory')
 ids=set()
 for start in range(0,len(purls),100):
  result=request('https://api.osv.dev/v1/querybatch',{'queries':[{'package':{'purl':p}} for p in purls[start:start+100]]})
  for r in result['results']:
   if r.get('next_page_token'):raise SystemExit('OSV pagination requires review; refusing an incomplete scan')
   ids.update(v['id'] for v in r.get('vulns',[]))
 with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
  findings=list(pool.map(lambda id:request('https://api.osv.dev/v1/vulns/'+id),sorted(ids)))
 bad=[v['id'] for v in findings if high(v)]
 if bad:raise SystemExit('High/critical or unscored vulnerable dependencies: '+', '.join(bad))
 print('OSV verified',len(purls),'dependencies; no high/critical vulnerabilities.')
if __name__=='__main__':main()

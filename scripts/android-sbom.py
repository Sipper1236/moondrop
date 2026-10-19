#!/usr/bin/env python3
"""Inventory resolved Android dependencies and embedded Go modules, not guessed versions."""
import hashlib,json,pathlib,re,subprocess,tempfile,zipfile
root=pathlib.Path(__file__).resolve().parent.parent
components={}
with tempfile.TemporaryDirectory(prefix='moondrop-sbom-') as tmp:
 tmp=pathlib.Path(tmp);init=tmp/'inventory.gradle';report=tmp/'dependencies.json'
 init.write_text('''gradle.projectsEvaluated {
 def app = gradle.rootProject.findProject(':app')
 app.tasks.register('moondropDependencyInventory') {
  doLast {
   def items = []
   ['releaseRuntimeClasspath','coreLibraryDesugaring'].each { name ->
    app.configurations.getByName(name).resolvedConfiguration.resolvedArtifacts.each { a ->
     def m=a.moduleVersion.id
     if(m.group && m.version != 'unspecified') items << [group:m.group,name:m.name,version:m.version]
    }
   }
   new File(System.getenv('MOONDROP_SBOM_REPORT')).text = groovy.json.JsonOutput.toJson(items)
  }
 }
}''')
 import os
 env=dict(os.environ,MOONDROP_SBOM_REPORT=str(report))
 subprocess.run(['./gradlew','--no-daemon','--init-script',str(init),':app:moondropDependencyInventory'],cwd=root/'android',env=env,check=True)
 for c in json.loads(report.read_text()):
  purl=f"pkg:maven/{c['group']}/{c['name']}@{c['version']}"
  components[purl]={'type':'library','group':c['group'],'name':c['name'],'version':c['version'],'purl':purl}
 aar=root/'android/app/libs/moondrop-engine.aar'
 with zipfile.ZipFile(aar) as z:
  libs=[n for n in z.namelist() if n.endswith('.so')]
  if not libs: raise SystemExit('Native AAR has no runtime libraries')
  binary=tmp/'engine.so';binary.write_bytes(z.read(libs[0]))
  info=subprocess.check_output(['go','version','-m',str(binary)],text=True)
  compiler=re.search(r': go([0-9.]+)',info.splitlines()[0])
  if not compiler:raise SystemExit('Native Go compiler version unavailable')
  go_version=compiler.group(1);components['pkg:golang/stdlib@'+go_version]={'type':'library','name':'stdlib','version':go_version,'purl':'pkg:golang/stdlib@'+go_version}
  components['pkg:golang/toolchain@'+go_version]={'type':'framework','name':'Go toolchain','version':go_version,'purl':'pkg:golang/toolchain@'+go_version}
  engine_version='v1.14.3-moondrop-security.1'
  components['pkg:golang/github.com/sagernet/sing-box@'+engine_version]={'type':'library','name':'github.com/sagernet/sing-box','version':engine_version,'purl':'pkg:golang/github.com/sagernet/sing-box@'+engine_version}
  for line in info.splitlines():
   fields=line.split()
   if len(fields)>=3 and fields[0] in ('dep','mod') and fields[2] not in ('(devel)',''):
    name,version=fields[1:3];purl=f'pkg:golang/{name}@{version}'
    components[purl]={'type':'library','name':name,'version':version,'purl':purl}
 packages=root/'android/app/libs/moondrop-engine.packages.txt'
 if not packages.is_file():raise SystemExit('Reachable Go package inventory is missing; refusing an incomplete native dependency record')
 for line in packages.read_text().splitlines():
  if '@' not in line:raise SystemExit('Invalid reachable Go package inventory')
  name,version=line.rsplit('@',1);purl=f'pkg:golang/{name}@{version}'
  components[purl]={'type':'library','name':name,'version':version,'purl':purl}
  if not any(p.startswith('pkg:golang/') for p in components):raise SystemExit('Native module inventory is unavailable; refusing incomplete SBOM')
  components['moondrop-engine']={'type':'library','name':'moondrop-engine.aar','version':'sing-box-1.14.3-moondrop-security.1','hashes':[{'alg':'SHA-256','content':hashlib.sha256(aar.read_bytes()).hexdigest()}],'externalReferences':[{'type':'vcs','url':'https://github.com/SagerNet/sing-box/tree/7054cac5124465ffdb145870d7c01885566711b1'}]}
output=root/'android/app/build/outputs/moondrop-sbom.json';output.parent.mkdir(parents=True,exist_ok=True)
output.write_text(json.dumps({'bomFormat':'CycloneDX','specVersion':'1.6','version':1,'components':list(components.values())},indent=2)+'\n')
print('Wrote',len(components),'resolved components to',output)

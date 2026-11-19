from pathlib import Path
import copy,json,subprocess,sys,tempfile,shutil,hashlib
r=Path(__file__).resolve().parents[1];launcher=Path(sys.argv[1]).resolve();source=r/'fixtures/figma/component-binding/snapshot.json';side=r/'fixtures/figma/component-binding/binding.json';script=r/'scripts/generate-verified.py'
def invoke(inp,map,out,verify=False):
 a=[sys.executable,str(script),'--launcher',str(launcher),'--input',str(inp),'--node','10:1','--out',str(out),'--flatten-instances']
 if map:a+=['--component-map',str(map)]
 if verify:a+=['--verify-only']
 return subprocess.run(a,capture_output=True).returncode
def raw_invoke(inp, mapping, out):
 return subprocess.run([str(launcher),'generate-figma','--input',str(inp),'--node','10:1','--out',str(out),'--flatten-instances','--component-map',str(mapping)],capture_output=True).returncode

with tempfile.TemporaryDirectory(prefix="mad-component-") as td:
 d=Path(td);s=json.loads(source.read_text());b=json.loads(side.read_text())
 for name in ['first','repeat']:assert invoke(source,side,d/name)==0
 files=lambda p:{f.name:f.read_bytes() for f in p.iterdir()};assert files(d/'first')==files(d/'repeat')
 assert invoke(source,side,d/'first',True)==0
 assert invoke(source,None,d/'first',True)==2
 assert invoke(source,None,d/'legacy')==0
 # The checked-in digest is from exact unmodified 66c86cf fixture generation.
 expected=json.loads((r/'fixtures/figma/component-binding/legacy-hashes.json').read_text())
 assert {f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in (d/'legacy').iterdir()}==expected
 for inp,name in [(source,'accepted'),(r/'fixtures/figma/component-binding/disabled.json','disabled')]:
  target=d/name
  if not target.exists():assert invoke(inp,side,target)==0
  assert invoke(inp,side,target,True)==0
 assert hashlib.sha256((d/'accepted/FigmaProfileScreen.generated.kt').read_bytes()).hexdigest()=='ae224ded36417a97645aa6d2782f669a35ab8ad4ac40adc36503d273c47f8244'
 assert hashlib.sha256((d/'disabled/FigmaProfileScreen.generated.kt').read_bytes()).hexdigest()=='5b406475628e02a6fbeb206adcd9bbce6539a6994362152cf5ae1e6f1cd5a7c4'
 code=(d/'accepted/FigmaProfileScreen.generated.kt').read_text();assert 'selected: Boolean, onSelect: () -> Unit' in code and 'remember' not in code
 assert 'enabled = false' in (d/'disabled/FigmaProfileScreen.generated.kt').read_text()
 def inst(v):return v['nodes']['10:1']['document']['children'][0]
 mutations=[
  lambda v:v['nodes']['10:1'].pop('components'),
  lambda v:v['nodes']['10:1']['components']['40:1'].update(key='wrong'),
  lambda v:v.update(components=v['nodes']['10:1']['components']),
  lambda v:v['nodes']['10:1']['components'].update({'40:9':{'key':'synthetic_filter_tab_v1'}}),
  lambda v:inst(v).update(componentId='40:9'),
  lambda v:inst(v).update(children=[]),
  lambda v:inst(v)['componentProperties']['Label#40:2'].update(type='INSTANCE_SWAP'),
  lambda v:inst(v)['componentProperties']['Enabled#40:3'].update(value='false'),
  lambda v:inst(v)['componentProperties']['Enabled#40:3'].update(type='TEXT'),
  lambda v:inst(v)['componentProperties'].update({'Variant#40:8':{'type':'VARIANT','value':'Primary'}}),
  lambda v:inst(v)['componentProperties']['Label#40:2'].update(value='Different'),
  lambda v:inst(v).update(reactions=[{'trigger':'ON_CLICK'}]),
  lambda v:inst(v).update(opacity=.5),
  lambda v:inst(v).update(layoutSizingHorizontal='FILL'),
  lambda v:inst(v)['children'][0].update(effects=[{}]),
  lambda v:inst(v)['children'][0]['style'].update(fontSize=999),
 ]
 for n,mutate in enumerate(mutations):
  v=copy.deepcopy(s);mutate(v);p=d/f'bad{n}.json';p.write_text(json.dumps(v));o=d/f'bad{n}';assert invoke(p,side,o)==2,n;assert not o.exists();assert raw_invoke(p,side,d/f'rawbad{n}')==2;assert not (d/f'rawbad{n}'/'FigmaProfileScreen.generated.kt').exists()
 for n,key in enumerate(['componentKey','labelProperty','enabledProperty','labelNodeId','instanceId']):
  v=copy.deepcopy(b);v['binding'][key]='wrong';p=d/f'map{n}.json';p.write_text(json.dumps(v));assert invoke(source,p,d/f'badmap{n}')==2;assert raw_invoke(source,p,d/f'rawbadmap{n}')==2
 for file in ['manifest.json','source-map.json','normalized-frame.json','FigmaProfileScreen.generated.kt']:
  o=d/('tamper'+file);shutil.copytree(d/'first',o);(o/file).write_text('{}');assert invoke(source,side,o,True)==2,file
 o=d/'dropped';shutil.copytree(d/'first',o);m=json.loads((o/'manifest.json').read_text());m.pop('componentBinding');(o/'manifest.json').write_text(json.dumps(m));assert invoke(source,side,o,True)==2
 for field in ['files','instanceMode']:
  o=d/('omitted-'+field);shutil.copytree(d/'first',o);m=json.loads((o/'manifest.json').read_text());m.pop(field);(o/'manifest.json').write_text(json.dumps(m));assert invoke(source,side,o,True)==2
 changed=d/'changed-sidecar.json';changed.write_text(side.read_text()+'\n');assert invoke(source,changed,d/'first',True)==2
 print('PASS: deterministic enabled/disabled source, exact legacy bundle, required host control,21 invalid binding/appearance cases,9 omission/tamper checks')

# Both public entry points share the opt-in lexical and typed-property policy.
with tempfile.TemporaryDirectory(prefix="mad-component-syntax-") as temporary:
 d=Path(temporary)
 raw=source.read_text(); mapping=side.read_text()
 cases=[
  (raw.replace('"components": {','"components": {}, "components": {'),mapping),
  (raw.replace('"key": "synthetic_filter_tab_v1"','"key": "wrong", "key": "synthetic_filter_tab_v1"'),mapping),
  (raw.replace('"key": "synthetic_filter_tab_v1"','"\\u006bey": "wrong", "key": "synthetic_filter_tab_v1"'),mapping),
  (raw.replace('"fontSize": 16','"fontSize": 01'),mapping),
  (raw.replace('"fontSize": 16','"fontSize": NaN'),mapping),
  (raw.replace('"fontSize": 16','"fontSize": 1e999'),mapping),
  (raw.replace('"Books"','"\\ud800"'),mapping),
  (raw+' false',mapping),
  (raw,mapping.replace('"schemaVersion": 1','"schemaVersion": 1.0')),
  (raw,mapping.replace('"schemaVersion": 1','"schemaVersion": true')),
  (raw,mapping.replace('"instanceId": "10:2"','"instanceId": "10:2", "\\u0069nstanceId": "10:2"')),
  (raw,mapping+' '*4096),
 ]
 for index,(snapshot,map_text) in enumerate(cases):
  assert snapshot!=raw or map_text!=mapping, index
  inp=d/'source.json';inp.write_text(snapshot);m=d/'map.json';m.write_text(map_text)
  for wrapped in (False,True):
   out=d/f'reject-{index}-{wrapped}'
   command=([sys.executable,str(script),'--launcher',str(launcher)] if wrapped else [str(launcher),'generate-figma'])
   command+=['--input',str(inp),'--node','10:1','--out',str(out),'--flatten-instances','--component-map',str(m)]
   result=subprocess.run(command,capture_output=True)
   assert result.returncode==2,(index,wrapped,result.stderr)
   assert not (out/'FigmaProfileScreen.generated.kt').exists()
 # Legal escaped keys, Unicode scalar text, and finite JSON exponent numbers.
 inp=d/'legal.json';inp.write_text(raw.replace('"Books"','"\\u4e66\\ud83d\\udcda"').replace('"fontSize": 16','"fontSize": 1.6e1'))
 m=d/'legal-map.json';m.write_text(mapping.replace('"instanceId"','"\\u0069nstanceId"'))
 assert invoke(inp,m,d/'legal')==0
 for index,malformed in enumerate([b'\xff',b'\xef\xbb\xbf'+raw.encode()]):
  inp=d/'bytes.json';inp.write_bytes(malformed)
  for wrapped in (False,True):
   out=d/f'bad-utf8-{index}-{wrapped}'
   command=([sys.executable,str(script),'--launcher',str(launcher)] if wrapped else [str(launcher),'generate-figma'])+['--input',str(inp),'--node','10:1','--out',str(out),'--flatten-instances','--component-map',str(side)]
   assert subprocess.run(command,capture_output=True).returncode==2
print('Component JSON: duplicate authoritative metadata, escaped aliases, numeric and UTF-8 syntax gates passed')

with tempfile.TemporaryDirectory(prefix="mad-component-font-") as temporary:
 d=Path(temporary)
 snapshot=json.loads(source.read_text())
 snapshot['nodes']['10:1']['document']['children'][0]['children'][0]['style']['fontFamily']='Synthetic Brand'
 inp=d/'source.json';inp.write_text(json.dumps(snapshot))
 font=d/'font.json';font.write_text(json.dumps(dict(schemaVersion=1,mappings=[dict(family='Synthetic Brand',role='body')])))
 out=d/'bundle'
 command=[sys.executable,str(script),'--launcher',str(launcher),'--input',str(inp),'--node','10:1','--out',str(out),'--flatten-instances','--component-map',str(side),'--font-map',str(font)]
 assert subprocess.run(command,capture_output=True).returncode==0
 assert subprocess.run(command+['--verify-only'],capture_output=True).returncode==0
 assert 'selected: Boolean, onSelect: () -> Unit, fontRoleBody: FontFamily' in (out/'FigmaProfileScreen.generated.kt').read_text()
 # Internal annotations cannot opt a standalone normalized document into SDK calls.
 rejected=d/'normalized-rejected'
 assert subprocess.run([str(launcher),'generate','--input',str(out/'normalized-frame.json'),'--out',str(rejected)],capture_output=True).returncode==2
 assert not (rejected/'FigmaProfileScreen.generated.kt').exists()
print('Accepted native source hashes unchanged; font-role combination and normalized-input boundary passed')

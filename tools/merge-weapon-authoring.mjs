// Preserve the actual independent Workbench sessions while selecting the current
// dual-blade export and the unchanged spear export for release evidence.
import {readFile,writeFile,mkdir,copyFile,stat} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {resolve,join,relative} from 'node:path';
const root=resolve(import.meta.dirname,'..');
const evidence=join(root,'verification/weapons-r2');
const dualDirectory=resolve(root,process.argv.find(x=>x.startsWith('--dual-evidence='))?.slice('--dual-evidence='.length)||'verification/dual-cuts-r5');
const archive=join(evidence,'pre-r5-authoring');
const proofDirectory=join(evidence,'authoring-sources');
const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
const load=async path=>JSON.parse(await readFile(path,'utf8'));
const portable=path=>relative(root,path).replaceAll('\\','/');
const encode=value=>JSON.stringify(value,null,2)+'\n';
await mkdir(archive,{recursive:true});await mkdir(proofDirectory,{recursive:true});
for(const name of ['workbench-authoring.json','sword-path.json']) {
 const target=join(archive,name);
 try {await stat(target);} catch(error) {
  if(error.code!=='ENOENT')throw error;
  await copyFile(join(evidence,name),target);
 }
}
const oldPath=join(archive,'workbench-authoring.json'),dualPath=join(dualDirectory,'workbench-authoring.json');
const oldBytes=await readFile(oldPath),dualBytes=await readFile(dualPath);
const old=JSON.parse(oldBytes),dual=JSON.parse(dualBytes);
function group(log,id) {
 const begin=log.findIndex(row=>row.tool==='create_resource'&&row.args.id===id);
 if(begin<0)throw new Error(`Missing Workbench create_resource for ${id}`);
 const next=log.findIndex((row,i)=>i>begin&&row.tool==='create_resource');
 const end=next<0?log.length:next;
 const rows=log.slice(begin,end);
 if(rows.filter(row=>row.tool==='save_copy').length!==1||rows.filter(row=>row.tool==='export_resource').length!==1)
  throw new Error(`Incomplete Workbench save/export proof for ${id}`);
 return {begin,end,rows};
}
const dualGroup=group(dual,'fsrpg.dual_blade'),spearGroup=group(old,'fsrpg.spear');
if(dualGroup.begin!==1||dualGroup.end!==dual.length||dual[0].tool!=='get_capabilities')throw new Error('Expected a complete dual-only Workbench session');
const merged=[...dual,...spearGroup.rows],resources=[];
for(const entry of [
 {id:'fsrpg.dual_blade',model:'dual_blade.fsvoxel',originalPath:dualPath,originalBytes:dualBytes,original:dualGroup,proof:dual,mergedBegin:dualGroup.begin,file:'dual-workbench-authoring.json'},
 {id:'fsrpg.spear',model:'spear.fsvoxel',originalPath:oldPath,originalBytes:oldBytes,original:spearGroup,proof:[old[0],...spearGroup.rows],mergedBegin:dual.length,file:'spear-workbench-authoring.json'}
]) {
 const exported=entry.original.rows.find(row=>row.tool==='export_resource').result;
 const saved=entry.original.rows.find(row=>row.tool==='save_copy').result;
 const model=await readFile(join(root,'src/main/resources/assets/fsrpg/fsvoxel/models',entry.model));
 if(exported.sha256!==digest(model)||exported.bytes!==model.length||saved.hash!==exported.sha256)
  throw new Error(`Workbench export identity does not match the final ${entry.model}`);
 const proofText=encode(entry.proof);await writeFile(join(proofDirectory,entry.file),proofText);
 resources.push({id:entry.id,model:entry.model,modelSha256:digest(model),bytes:model.length,copyId:saved.copyId,
  version:saved.version,exportSha256:exported.sha256,
  source:{originalPath:portable(entry.originalPath),originalLogSha256:digest(entry.originalBytes),
   originalRecordIndices:Array.from({length:entry.original.rows.length},(_,i)=>entry.original.begin+i),
   proofFile:'authoring-sources/'+entry.file,proofSha256:digest(proofText),
   proofRecordIndices:Array.from({length:entry.original.rows.length},(_,i)=>i+1)},
  mergedRecordIndices:Array.from({length:entry.original.rows.length},(_,i)=>entry.mergedBegin+i)});
}
const diagnosticsBytes=await readFile(join(dualDirectory,'sword-path.json'));
const diagnostics=JSON.parse(diagnosticsBytes);
if(!Array.isArray(diagnostics)||diagnostics.some(row=>!row.id?.startsWith('dual_')))throw new Error('Expected dual-only sword path diagnostics');
const mergedText=encode(merged);
const manifest={schemaVersion:1,generatedAt:new Date().toISOString(),
 description:'Merged evidence from two real Workbench export sessions. The dual-blade segment is replaced by the final revision; the unchanged spear segment is retained verbatim. These were independent resource sessions, not a single uninterrupted editing session.',
 mergedLog:{file:'workbench-authoring.json',sha256:digest(mergedText)},
 dualDiagnostics:{file:'sword-path.json',sha256:digest(diagnosticsBytes),source:portable(join(dualDirectory,'sword-path.json'))},
 resources};
await writeFile(join(evidence,'workbench-authoring.json'),mergedText);
await writeFile(join(evidence,'sword-path.json'),diagnosticsBytes);
await writeFile(join(evidence,'workbench-provenance.json'),encode(manifest));
console.log(JSON.stringify({records:merged.length,resources:resources.map(({id,modelSha256})=>({id,modelSha256})),manifest:join(evidence,'workbench-provenance.json')}));

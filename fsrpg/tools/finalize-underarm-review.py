"""Bind the final geometric and real-client evidence to one release JAR."""
import hashlib,json,sys,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
evidence=root/'verification/spear-underarm-turn-20261014'
run=Path(sys.argv[1]).resolve()
jar=root/'build/libs/fsrpg-neoforge-0.5.4.jar'
sha=lambda b:hashlib.sha256(b).hexdigest()
reports={
    'gameReport':run/'action-limits-smoke.json',
    'poseReport':evidence/'underarm-poses.json',
    'motionReport':evidence/'underarm-motion.json',
    'contactReport':run/'underarm-contact.json',
    'renderAnalysis':run/'focused-analysis/focused-summary.json',
}
data={k:json.loads(p.read_text(encoding='utf-8-sig')) for k,p in reports.items()}
jarsha=sha(jar.read_bytes())
with zipfile.ZipFile(jar) as z:
    assert not any('/smoke/' in n or 'monitorAgent' in n for n in z.namelist()),'Development classes leaked into release'
    solver=sha(z.read('org/fsrpg/client/SpearAimPose.class'))
for k in ('poseReport','motionReport'):
    assert data[k]['failures']==0 and data[k]['loadedSolverBytecodeSha256']==solver,k
for k in ('gameReport','contactReport','renderAnalysis'):
    assert data[k]['passed'],k
assert data['gameReport']['modJarSha256']==jarsha
assert data['gameReport']['loadedSolverBytecodeSha256']==solver
assert data['gameReport']['monitorAdapter']==r'\\.\DISPLAY5'
assert data['renderAnalysis']['jarSha256']==jarsha
assert len(data['gameReport'].get('importedItems',[]))==9
assert data['gameReport'].get('importedItemRenderedFrames',0)>10
assert not data['contactReport'].get('waistSurfaceViolations')
build=(evidence/'final-build.log').read_text(encoding='utf-16' if (evidence/'final-build.log').read_bytes().startswith(b'\xff\xfe') else 'utf-8-sig')
assert 'BUILD SUCCESSFUL' in build and 'SpearUnderarmCarryTest passed' in build and 'SpearTurnTest passed' in build
assert 'TorsoConnectionTest passed' in build
result={'passed':True,'version':'0.5.4','jarSha256':jarsha,'solverBytecodeSha256':solver,
        **{k:str(p) for k,p in reports.items()},
        'reportSha256':{k:sha(p.read_bytes()) for k,p in reports.items()},
        'poseChecks':data['poseReport']['checks'],'motionChecks':data['motionReport']['checks'],
        'actualRenderedFrames':data['renderAnalysis']['actualRenderedFrames'],
        'contactMetrics':data['contactReport']['clips'],
        'centralCarryViolations':len(data['contactReport']['centralCarryViolations']),
        'lowerBodyViolations':len(data['contactReport']['lowerBodyViolations']),
        'waistSurfaceViolations':len(data['contactReport']['waistSurfaceViolations']),
        'importedItems':data['gameReport']['importedItems'],
        'buildLog':str(evidence/'final-build.log')}
(evidence/'final-report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':True,'jarSha256':jarsha,'actualRenderedFrames':result['actualRenderedFrames']}))

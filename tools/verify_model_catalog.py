#!/usr/bin/env python3
"""Verify exact catalog artifacts through Android JNI on an explicitly selected owned emulator."""
import argparse, hashlib, json, re, subprocess, shlex
from pathlib import Path
def successful_test_count(result):
    matches = re.findall(r"^OK \((\d+) tests?\)\s*$", result, re.M)
    if len(matches) != 1 or int(matches[0]) < 1 or "FAILURES!!!" in result or "INSTRUMENTATION_FAILED" in result:
        raise RuntimeError("Instrumentation did not report a successful nonempty test selection")
    return int(matches[0])

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--model', required=True)
    parser.add_argument('--experimental', action='store_true', help='Check compatibility and report semantic quality separately')
    parser.add_argument('--models', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial): raise RuntimeError('Physical devices are refused')
    root = Path(__file__).resolve().parents[1]
    def run(*words, timeout=180):
        result = subprocess.run([args.adb, '-s', args.serial, *words], capture_output=True, text=True, timeout=timeout)
        if result.returncode: raise RuntimeError(result.stderr or result.stdout)
        return result.stdout
    if run('shell', 'getprop', 'ro.kernel.qemu').strip() != '1': raise RuntimeError('An emulator is required')
    entries = json.loads((root/'app/src/main/assets/model-catalog.json').read_text())
    entry = next(item for item in entries if item['id'] == args.model)
    model = args.models/entry['filename']
    if not model.is_file(): model = root/'dist'/entry['filename']
    def sha(path):
        with path.open('rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest()
    if model.stat().st_size != entry['size'] or sha(model) != entry['sha256']: raise RuntimeError('Artifact identity mismatch')
    apk = root/'app/build/outputs/apk/debug/app-debug.apk'; tests = root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    apk_sha, test_sha = sha(apk), sha(tests)
    run('install','-r',str(apk));run('install','-r',str(tests))
    installed = run('shell','pm','path','local.voice').strip().removeprefix('package:')
    if run('shell','sha256sum',installed).split()[0] != apk_sha: raise RuntimeError('Installed APK mismatch')
    run('shell','input','keyevent','KEYCODE_WAKEUP');run('shell','wm','dismiss-keyguard')
    # Stream into private emulator storage; staging a second large copy can fill /data.
    if not re.fullmatch(r'[a-z0-9-]+',args.model): raise RuntimeError('Invalid model id')
    run('shell','run-as','local.voice','mkdir','-p','files/catalog-tests')
    destination=f'files/catalog-tests/{args.model}.gguf'
    command='run-as local.voice sh -c '+shlex.quote('cat > '+shlex.quote(destination))
    with model.open('rb') as stream:
        copied=subprocess.run([args.adb,'-s',args.serial,'shell','-T',command],stdin=stream,capture_output=True,timeout=240)
    if copied.returncode: raise RuntimeError(copied.stderr.decode('utf-8','replace'))
    if run('shell','run-as','local.voice','sha256sum',destination).split()[0] != entry['sha256']: raise RuntimeError('Copied emulator artifact mismatch')
    test_class = 'local.voice.CompactModelRuntimeTest' if args.experimental else 'local.voice.CatalogRuntimeTest'
    selection=('-e','class',test_class,'-e','catalog_model',args.model)
    args.evidence.parent.mkdir(parents=True,exist_ok=True)
    args.evidence.with_suffix('.json').write_text(json.dumps({'id':args.model,'status':'pending','testsPassed':0})+'\n')
    run('shell','run-as','local.voice','rm','-f',f'cache/catalog-runtime-{args.model}.json')
    inventory=run('shell','am','instrument',*selection,'-e','log','true','-w','local.voice.test/androidx.test.runner.AndroidJUnitRunner')
    assert successful_test_count(inventory)==1
    args.evidence.with_suffix('.inventory.log').write_text(inventory)
    output=run('shell','am','instrument',*selection,'-w','local.voice.test/androidx.test.runner.AndroidJUnitRunner',timeout=520)
    args.evidence.write_text(output)
    try: count=successful_test_count(output)
    except RuntimeError:
        args.evidence.with_suffix('.json').write_text(json.dumps({'id':args.model,'status':'failed','testsPassed':0,'apkSha256':apk_sha})+'\n')
        raise
    assert count==1
    report=json.loads(run('shell','run-as','local.voice','cat',f'cache/catalog-runtime-{args.model}.json'))
    if run('shell','sha256sum',installed).split()[0] != apk_sha: raise RuntimeError('Emulator APK changed during verification')
    report.update({'apkSha256':apk_sha,'testApkSha256':test_sha,'serial':args.serial,'api':int(run('shell','getprop','ro.build.version.sdk')),'physicalPhone':False,'testsPassed':1})
    args.evidence.with_suffix('.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
    print(output)
if __name__=='__main__':main()

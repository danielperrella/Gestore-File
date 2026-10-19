import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
const manifest = JSON.parse(readFileSync('docs/update.json', 'utf8'));
const apk = readFileSync('docs/gestorefile.apk');
const gradle = readFileSync('app/build.gradle.kts', 'utf8');
assert.equal(manifest.size, apk.length);
assert.equal(manifest.sha256, createHash('sha256').update(apk).digest('hex'));
assert.equal(manifest.versionCode, Number(gradle.match(/versionCode = (\d+)/)[1]));
assert.equal(manifest.versionName, gradle.match(/versionName = "([^"]+)"/)[1]);
assert.equal(manifest.apkUrl, 'https://danielperrella.github.io/Gestore-File/gestorefile.apk');
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
assert.ok(sdk, 'Imposta ANDROID_HOME al percorso del SDK Android');
const tools = join(sdk, 'build-tools', '35.0.0');
// .bat requires cmd.exe on Windows. All paths and arguments here are fixed project values.
const signature = execFileSync(join(tools, process.platform === 'win32' ? 'apksigner.bat' : 'apksigner'),
  ['verify', '--print-certs', 'docs/gestorefile.apk'], { encoding: 'utf8', shell: process.platform === 'win32' });
assert.ok(signature.includes('Signer #1 certificate SHA-256 digest: 17e6f8f7aeab30425e78f6939babc0c41a03bff80ae7460ca7313bb69bd7abe7'), 'Firma diversa dalla distribuzione originale');
const badging = execFileSync(join(tools, process.platform === 'win32' ? 'aapt.exe' : 'aapt'),
  ['dump', 'badging', 'docs/gestorefile.apk'], { encoding: 'utf8' });
assert.ok(badging.includes(`package: name='it.liberofiles.app' versionCode='${manifest.versionCode}' versionName='${manifest.versionName}'`), 'Metadati APK diversi dal manifest');
console.log('APK, manifest e versione Gradle coerenti.');

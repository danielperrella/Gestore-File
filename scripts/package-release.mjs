import { readFileSync, copyFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
const gradle = readFileSync('app/build.gradle.kts', 'utf8');
const versionCode = Number(gradle.match(/versionCode = (\d+)/)[1]);
const versionName = gradle.match(/versionName = "([^"]+)"/)[1];
const candidate = versionName.includes('-');
if (!candidate && versionCode >= 6 && !process.argv.includes('--drive-approved')) {
  throw new Error('Prima della pubblicazione Drive completa test reali e verifica OAuth Google, poi usa --drive-approved.');
}
const destination = candidate ? 'candidate' : 'docs';
const apk = readFileSync('app/build/outputs/apk/release/app-release.apk');
mkdirSync(destination, { recursive: true });
copyFileSync('app/build/outputs/apk/release/app-release.apk', `${destination}/gestorefile.apk`);
writeFileSync(`${destination}/update.json`, JSON.stringify({versionCode, versionName,
  apkUrl: candidate ? `https://github.com/danielperrella/Gestore-File/releases/download/v${versionName}/gestorefile.apk` : 'https://danielperrella.github.io/Gestore-File/gestorefile.apk',
  size: apk.length, sha256: createHash('sha256').update(apk).digest('hex'),
  notes: process.argv[2] || 'Distribuzione pubblica e aggiornamenti verificati all’apertura.'
}, null, 2) + '\n');
console.log(`Preparata versione ${versionName} (${versionCode}) in ${destination}/. ${candidate ? 'Il manifest pubblico non viene modificato.' : ''}`);

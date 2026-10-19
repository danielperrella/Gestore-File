import { readFileSync, copyFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
const gradle = readFileSync('app/build.gradle.kts', 'utf8');
const versionCode = Number(gradle.match(/versionCode = (\d+)/)[1]);
const versionName = gradle.match(/versionName = "([^"]+)"/)[1];
const apk = readFileSync('app/build/outputs/apk/release/app-release.apk');
mkdirSync('docs', { recursive: true });
copyFileSync('app/build/outputs/apk/release/app-release.apk', 'docs/gestorefile.apk');
writeFileSync('docs/update.json', JSON.stringify({versionCode, versionName,
  apkUrl: 'https://danielperrella.github.io/Gestore-File/gestorefile.apk',
  size: apk.length, sha256: createHash('sha256').update(apk).digest('hex'),
  notes: process.argv[2] || 'Distribuzione pubblica e aggiornamenti verificati all’apertura.'
}, null, 2) + '\n');
console.log(`Preparata versione ${versionName} (${versionCode})`);

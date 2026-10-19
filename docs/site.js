fetch('update.json', {cache: 'no-store'})
  .then(response => { if (!response.ok) throw new Error(); return response.json(); })
  .then(manifest => {
    document.querySelector('#version').textContent = `Versione ${manifest.versionName} · ${(manifest.size / 1024 / 1024).toFixed(1)} MB · Versione di prova`;
    document.querySelector('#hash').textContent = `SHA-256: ${manifest.sha256}`;
  }).catch(() => {});
document.querySelectorAll('[data-preview]').forEach(button => {
  button.addEventListener('click', () => {
    document.querySelectorAll('[data-preview]').forEach(item => item.setAttribute('aria-pressed', String(item === button)));
    const image = document.querySelector('#appearance-image');
    image.src = button.dataset.preview;
    image.alt = `Anteprima ${button.textContent.toLowerCase()} di Gestore File`;
  });
});

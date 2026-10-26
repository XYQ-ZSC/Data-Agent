/**
 * Trigger a browser download for a Blob.
 */
export function downloadFile(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  a.remove();
  // The browser starts the download asynchronously. Revoking immediately cancels it.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

// Example only: adds a small marker to the document. It does not collect or transmit data.
if (!document.getElementById("komiku-reader-extension-demo")) {
  const marker = document.createElement("div");
  marker.id = "komiku-reader-extension-demo";
  marker.textContent = "Komiku Reader extension aktif";
  marker.style.cssText = "position:fixed;z-index:2147483647;bottom:8px;right:8px;padding:6px 10px;background:#222;color:#fff;border-radius:8px;font:12px sans-serif";
  document.documentElement.appendChild(marker);
}

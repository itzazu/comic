# Komiku Reader extension package format

Komiku Reader supports a safe subset of browser-style **content scripts**. It is not a full Chromium extension runtime.

## ZIP package

Place `manifest.json` at the ZIP root and include each referenced JavaScript file:

```json
{
  "manifest_version": 3,
  "name": "Example Source Helper",
  "version": "1.0.0",
  "description": "Example content script",
  "content_scripts": [
    {
      "matches": ["https://example.org/*"],
      "js": ["content.js"]
    }
  ]
}
```

Only HTTPS match patterns are accepted. Scripts are limited to 1 MiB each, archives to 8 MiB compressed / 12 MiB expanded, and 200 archive entries. New imports are disabled by default. Browser background pages/service workers, native messaging, and selected privileged permissions are rejected. The supported runtime does not provide `chrome.*` / `browser.*` extension APIs, extension storage, popup pages, or browser action UI.

## Standalone JavaScript

A `.js` file is imported as a content script scoped to `https://komiku.org/*` and `https://www.komiku.org/*`. It is disabled by default.

## CRX

CRX2/CRX3 containers are unpacked and their manifest/content scripts are processed using the same supported subset as ZIP. The current importer does **not** verify the CRX publisher signature. Treat CRX imports as untrusted and enable them only when you trust the source.

## APK

APK is an Android application package, not a browser extension. The importer recognizes and rejects APK files; it does not install another Android application from inside Extension Manager.

## Security model and limitations

Scripts are stored in app-private storage and no Android JavaScript bridge is exposed. When enabled, a content script runs in the loaded page's JavaScript context for matching HTTPS URLs. Consequently it can read/modify that page's DOM and can use web APIs available to the page, including network requests. This is not a hardened isolated-world sandbox. Install scripts only from trusted sources. The browser shell currently blocks non-HTTPS top-level navigation.

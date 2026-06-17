## 🚀 KeyFlux v1.0.1 (Hotfix Update)

This is a hotfix release aimed at resolving compatibility issues on specific OEM Android implementations.

### 🛠️ Fixes
- **Samsung OneUI 8.5 / Android 16 Compatibility:** Fixed a critical issue where the module would fail to initialize due to `Application#attachBaseContext` hook failures. KeyFlux now securely hooks `ContextWrapper#attachBaseContext`, providing stable and safe injection on modern Samsung devices. (#1)

### 📥 Installation
1. Download the `KeyFlux_release_v1.0.1_xxx.apk` below.
2. Install and enable it in LSPosed or Vector.
3. Force stop Gboard to apply the fixes.

*(As always, KeyFlux is completely secure and operates locally without network permissions)*

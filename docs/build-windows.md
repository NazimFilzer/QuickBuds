# Build the desktop app on Windows

You need Rust (stable, from [rustup.rs](https://rustup.rs)). Windows needs nothing else.

```powershell
git clone https://github.com/spizganed/QuickBuds.git
cd QuickBuds\desktop
cargo test
cargo run --release
```

Pair the earbuds in Windows' Bluetooth settings first. The app connects to them once their audio is
connected.

## From Linux

The release `quickbuds.exe` is cross-built on Linux with the `x86_64-pc-windows-gnu` target and
mingw-w64. See [Release archives](./build-linux.md#release-archives).

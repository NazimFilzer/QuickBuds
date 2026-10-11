//! App settings › Report a problem: the phone's `ProblemReport`, posted with `curl` (as the updater does) to the same
//! report Worker. Nothing goes out without Send; See the report shows the exact text first.

use crate::protocol::*;
use crate::session::{Line, LOG};
use crate::{with_app, App, MainWindow, Report, Tr};
use slint::{ComponentHandle, Model, VecModel};
use std::io::Write;
use std::process::Stdio;
use std::sync::Mutex;

const WORKER: &str = "https://quickbuds-report.spizganed.workers.dev/report";
/// The Category options, in the Worker's own words; it drops any other value (report-worker/src/index.js).
const CATEGORIES: [&str; 6] = ["UI", "Lag", "Connection", "Feature not working", "Battery", "Other"];
/// Their labels, the phone's `problem_cat_*` strings.
pub const CATEGORY_KEYS: [&str; 6] = ["problem_cat_ui", "problem_cat_lag", "problem_cat_connection",
    "problem_cat_feature", "problem_cat_battery", "problem_cat_other"];

/// The first line of a sent or exported log: app, computer, system, buds model and firmware.
pub fn header(a: &App) -> String {
    let s = &a.snap;
    let model = s.model.and_then(|m| m["name"].as_str()).unwrap_or(if s.name.is_empty() { "unknown buds" } else { s.name.as_str() });
    let id = s.model.and_then(|m| m["id"].as_str()).unwrap_or("?");
    format!("QuickBuds {} | {} | {} | {model} ({id}) firmware {}", env!("CARGO_PKG_VERSION"), computer(), system(),
        s.firmware.as_deref().unwrap_or("?"))
}

#[cfg(not(windows))]
fn computer() -> String {
    let read = |f: &str| std::fs::read_to_string(format!("/sys/class/dmi/id/{f}")).map(|s| s.trim().to_string()).unwrap_or_default();
    format!("{} {}", read("sys_vendor"), read("product_name")).trim().to_string()
}

#[cfg(not(windows))]
fn system() -> String {
    let os = std::fs::read_to_string("/etc/os-release").unwrap_or_default();
    let name = os.lines().find_map(|l| l.strip_prefix("PRETTY_NAME=")).unwrap_or("Linux").trim_matches('"').to_string();
    let kernel = std::fs::read_to_string("/proc/sys/kernel/osrelease").unwrap_or_default();
    format!("{name} (kernel {})", kernel.trim())
}

/// Maker and model from the BIOS keys (`reg` ships with Windows).
#[cfg(windows)]
fn computer() -> String {
    let out = crate::update::quiet("reg").args(["query", r"HKLM\HARDWARE\DESCRIPTION\System\BIOS"]).output();
    let text = out.map(|o| String::from_utf8_lossy(&o.stdout).into_owned()).unwrap_or_default();
    let value = |k: &str| text.lines().find(|l| l.trim_start().starts_with(k))
        .and_then(|l| l.split("REG_SZ").nth(1)).map(|v| v.trim().to_string()).unwrap_or_default();
    format!("{} {}", value("SystemManufacturer"), value("SystemProductName")).trim().to_string()
}

#[cfg(windows)]
fn system() -> String {
    let out = crate::update::quiet("cmd").args(["/c", "ver"]).output();
    out.map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).unwrap_or_else(|_| "Windows".into())
}

/// This computer's own name, which the Bluetooth adapter and the buds' device list carry.
fn host() -> Option<String> {
    #[cfg(windows)]
    let name = std::env::var("COMPUTERNAME").ok();
    #[cfg(not(windows))]
    let name = std::fs::read_to_string("/etc/hostname").ok().map(|s| s.trim().to_string());
    name.filter(|n| n.len() >= 3)
}

/// Device list and device manager packets (PROTOCOL.md §9) hold addresses and names: only the header stays.
fn device_bytes(l: &Line) -> bool {
    if l.bytes.len() < 10 || l.bytes[0] != 0xAA { return false; }
    let pl = payload_of(&l.bytes);
    matches!(cmd_of(&l.bytes), 0x8112 | 0x0429 | 0x8132) || (cmd_of(&l.bytes) == EVT_PUSH && pl.first() == Some(&0x06))
}

/// The log as sent: header, every line, the newest stderr, then the last crash report; without Bluetooth addresses,
/// device data or this computer's name.
pub(crate) fn log_text(a: &App) -> String {
    scrub(header(a) + "\n" + &lines(&LOG.lock().unwrap()) + &stderr_tail() + CRASH.lock().unwrap().as_str())
}

/// `stderr.log` next to `settings.json`.
fn stderr_path() -> Option<std::path::PathBuf> {
    crate::settings_path().map(|p| p.with_file_name("stderr.log"))
}

/// Second thing in `main`: stderr goes to [stderr_path], emptied per launch. GTK, Slint, BlueZ and Rust warnings land
/// there; Windows shows no console and a launcher drops them. From a terminal stderr stays on the terminal.
pub fn capture_stderr() {
    use std::io::IsTerminal;
    if std::io::stderr().is_terminal() { return; }
    let Some(path) = stderr_path() else { return };
    let _ = std::fs::create_dir_all(path.parent().unwrap());
    let Ok(file) = std::fs::File::create(&path) else { return };
    #[cfg(not(windows))]
    unsafe {
        use std::os::fd::IntoRawFd;
        libc::dup2(file.into_raw_fd(), 2);
    }
    #[cfg(windows)]
    unsafe {
        use std::os::windows::io::IntoRawHandle;
        windows_sys::Win32::System::Console::SetStdHandle(windows_sys::Win32::System::Console::STD_ERROR_HANDLE,
            file.into_raw_handle() as _);
    }
}

/// The last 100 stderr lines, or "" when there are none. The tray library's deprecation notice comes every launch.
fn stderr_tail() -> String {
    let text = stderr_path().and_then(|p| std::fs::read_to_string(p).ok()).unwrap_or_default();
    let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty() && !l.contains("libayatana-appindicator is deprecated")).collect();
    if lines.is_empty() { return String::new(); }
    format!("\nSTDERR (warnings and errors):\n{}\n", lines[lines.len().saturating_sub(100)..].join("\n"))
}

fn lines(log: &[Line]) -> String {
    log.iter().map(|l| if device_bytes(l) {
        format!("{} {} {} [device data removed]\n", l.at, l.dir, hex(&l.bytes[..l.bytes.len() - payload_of(&l.bytes).len()]))
    } else {
        crate::devtools::raw(l) + "\n"
    }).collect()
}

fn scrub(text: String) -> String {
    let text = scrub_macs(&text);
    match host() { Some(h) => text.replace(&h, "[this computer]"), None => text }
}

/// Downloads/QuickBuds: exported logs and crash reports, as on the phone.
pub fn folder() -> std::path::PathBuf {
    #[cfg(windows)]
    let home = std::env::var_os("USERPROFILE");
    #[cfg(not(windows))]
    let home = std::env::var_os("HOME");
    std::path::PathBuf::from(home.unwrap_or_default()).join("Downloads").join("QuickBuds")
}

/// The crash report the Report page sends after a crash; "" otherwise.
static CRASH: Mutex<String> = Mutex::new(String::new());

/// The crash report opened the Report page this start: no other launch prompt shows (`modeltest.rs`).
static CRASH_SHOWN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn crash_shown() -> bool { CRASH_SHOWN.load(std::sync::atomic::Ordering::Relaxed) }

/// First thing in `main`: a panic writes `crash-<seconds>.txt` to [folder], with the newest log lines. The release
/// build aborts on a panic and Windows shows no console, so this file is the only record.
pub fn catch_panics() {
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        // A panic while the log is locked skips the log rather than deadlock.
        let log = LOG.try_lock().map(|l| lines(&l[l.len().saturating_sub(300)..])).unwrap_or_default();
        let text = format!("QuickBuds crash report\nversion: {}\ncomputer: {}\nsystem: {}\nthread: {}\n\n{info}\n\n{log}{}",
            env!("CARGO_PKG_VERSION"), computer(), system(), std::thread::current().name().unwrap_or("?"), stderr_tail());
        let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs());
        let _ = std::fs::create_dir_all(folder()).and_then(|_| std::fs::write(folder().join(format!("crash-{secs}.txt")), scrub(text)));
        previous(info);
    }));
}

/// A crash report newer than the last one shown opens the Report page, filled in with it. Once per report.
pub fn show_crash(a: &mut App) {
    let newest = std::fs::read_dir(folder()).into_iter().flatten().flatten()
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n.starts_with("crash-") && n.ends_with(".txt"))
        .max_by_key(|n| n[6..n.len() - 4].parse::<u64>().unwrap_or(0));
    let Some(name) = newest else { return };
    if crate::load_settings()["crash_shown"].as_str() == Some(name.as_str()) { return; }
    crate::save_setting("crash_shown", name.clone().into());
    let Ok(text) = std::fs::read_to_string(folder().join(&name)) else { return };
    *CRASH.lock().unwrap() = format!("\n{text}");
    CRASH_SHOWN.store(true, std::sync::atomic::Ordering::Relaxed);
    open(a);
    let r = a.main.global::<Report>();
    r.get_picked().set_row_data(5, true);
    r.set_description("App crash".into());
    r.set_note(crate::t(&a.tr, "crash_title").into());
    a.main.set_page(9);
}

/// `AA:BB:CC:DD:EE:FF` (or with `-`) -> `XX:XX:XX:XX:XX:XX`.
fn scrub_macs(s: &str) -> String {
    let b = s.as_bytes();
    let is_mac = |i: usize| i + 17 <= b.len()
        && (0..6).all(|k| b[i + 3 * k].is_ascii_hexdigit() && b[i + 3 * k + 1].is_ascii_hexdigit())
        && (0..5).all(|k| b[i + 3 * k + 2] == b':' || b[i + 3 * k + 2] == b'-')
        && (i == 0 || !b[i - 1].is_ascii_alphanumeric())
        && (i + 17 == b.len() || !b[i + 17].is_ascii_alphanumeric());
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < b.len() {
        if is_mac(i) { out += "XX:XX:XX:XX:XX:XX"; i += 17; continue; }
        let ch = s[i..].chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

/// One report; [Draft::log] is None when the person turned it off.
pub(crate) struct Draft {
    pub model: String,
    pub categories: Vec<&'static str>,
    pub description: String,
    pub log: Option<String>,
    pub crash: bool,
}

/// What the person reads before Send: one block per field, the log last.
pub(crate) fn preview(d: &Draft) -> String {
    let mut blocks = vec![format!("Model:\n{}", d.model)];
    if !d.categories.is_empty() { blocks.push(format!("Category:\n{}", d.categories.join(", "))); }
    blocks.push(format!("Description:\n{}", d.description));
    if let Some(log) = &d.log { blocks.push(format!("Log:\n{log}")); }
    blocks.join("\n\n")
}

/// Posts [body] to [url] through `curl`'s standard input. The answer code (0: no connection) and text.
fn post(url: &str, body: &str) -> (u16, String) {
    let child = crate::update::quiet("curl")
        .args(["-sS", "--max-time", "30", "-w", "\n%{http_code}", "-H", "Content-Type: application/json; charset=UTF-8", "--data-binary", "@-", url])
        .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn();
    let Ok(mut child) = child else { return (0, String::new()) };
    if child.stdin.take().map(|mut i| i.write_all(body.as_bytes())).is_none() { return (0, String::new()); }
    let out = child.wait_with_output().map(|o| String::from_utf8_lossy(&o.stdout).into_owned()).unwrap_or_default();
    let (text, code) = out.rsplit_once('\n').unwrap_or(("", out.as_str()));
    (code.trim().parse().unwrap_or(0), text.to_string())
}

/// Posts [d] to the Worker.
/// Returns (sent, the Worker's answer text when it refused the report).
pub(crate) fn send(d: Draft) -> (bool, Option<String>) {
    let json = serde_json::json!({
        "app": format!("QuickBuds {} {}", env!("CARGO_PKG_VERSION"), if cfg!(windows) { "Windows" } else { "Linux" }),
        "model": d.model, "categories": d.categories, "description": d.description, "log": d.log, "crash": d.crash,
    });
    let (code, text) = post(WORKER, &json.to_string());
    let message = serde_json::from_str::<serde_json::Value>(&text).ok()
        .and_then(|v| v["message"].as_str().map(String::from));
    (code == 200, if code == 200 { None } else { message })
}

/// The current form; None (with a note) without a description, when the model is not in the list (a crash
/// report sends with any model), or when the log has no RX frame (the buds never connected). A person who cannot
/// connect at all has no log, only words to reproduce it from: Send without the log still works.
fn current(a: &App) -> Option<Draft> {
    let r = a.main.global::<Report>();
    let tr = a.main.global::<Tr>();
    let picked: Vec<&str> = (0..6).filter(|&i| r.get_picked().row_data(i).unwrap_or(false)).map(|i| CATEGORIES[i]).collect();
    let description = r.get_description().trim().to_string();
    let crash = !CRASH.lock().unwrap().is_empty();
    if !crash && !known(r.get_model().trim()) {
        r.set_note(format!("{}\n{}: {}#supported-earbuds", tr.get_problem_unsupported(), tr.get_problem_supported_list(),
            crate::update::PAGE.trim_end_matches("/releases/latest")).into());
        return None;
    }
    if description.is_empty() {
        r.set_note("Describe the problem: what you did and what happened.".into());
        return None;
    }
    let no_frames = !crash && !LOG.lock().unwrap().iter().any(|l| l.dir == "RX");
    if r.get_with_log() && no_frames {
        r.set_note(tr.get_problem_log_empty());
        return None;
    }
    r.set_note("".into());
    let log = r.get_with_log().then(|| log_text(a));
    Some(Draft { model: r.get_model().trim().to_string(), categories: picked, description, log, crash })
}

fn open(a: &mut App) {
    let r = a.main.global::<Report>();
    if r.get_model().is_empty() {
        r.set_model(a.snap.model.and_then(|m| m["name"].as_str()).unwrap_or(a.snap.name.as_str()).into());
    }
    r.set_note("".into());
    r.set_preview("".into());
}

pub fn setup(main: &MainWindow) {
    let r = main.global::<Report>();
    r.set_picked(VecModel::from_slice(&[false; 6]));
    r.on_open(|| with_app(open));
    r.on_toggle(|i| with_app(|a| {
        let p = a.main.global::<Report>().get_picked();
        let i = i as usize;
        p.set_row_data(i, !p.row_data(i).unwrap_or(false));
    }));
    r.on_show_preview(|| with_app(|a| {
        if let Some(d) = current(a) {
            a.main.global::<Report>().set_preview(preview(&d).into());
        }
    }));
    r.on_send(|| with_app(|a| {
        let Some(d) = current(a) else { return };
        let r = a.main.global::<Report>();
        r.set_busy(true);
        let weak = a.main.as_weak();
        std::thread::spawn(move || {
            let (ok, refused) = send(d);
            let _ = weak.upgrade_in_event_loop(move |m| {
                let r = m.global::<Report>();
                r.set_busy(false);
                if ok {
                    r.set_note("Report sent. Thank you!".into());
                    r.set_description("".into());
                    r.set_preview("".into());
                    r.set_picked(VecModel::from_slice(&[false; 6]));
                    CRASH.lock().unwrap().clear();
                } else {
                    r.set_note(refused.unwrap_or_else(|| "Could not send the report. Check the internet connection and try again.".into()).into());
                }
            });
        });
    }));
}

/// A model name from the list, any case.
fn known(model: &str) -> bool {
    models().iter().any(|m| m["name"].as_str().is_some_and(|n| n.eq_ignore_ascii_case(model)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn macs() {
        assert_eq!(scrub_macs("to AA:bb:01:02:03:04 ok"), "to XX:XX:XX:XX:XX:XX ok");
        assert_eq!(scrub_macs("12:34:56.789 RX AA 07"), "12:34:56.789 RX AA 07");
    }

    #[test]
    fn known_models() {
        assert!(known("OnePlus Buds 4"));
        assert!(known("oneplus buds 4"));
        assert!(!known("Xiaomi Buds 5"));
        assert!(!known(""));
    }
}

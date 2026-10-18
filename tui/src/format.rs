//! Text helpers: UTC clock, display-width truncation, ANSI stripping and
//! unified-event line formatting (design report section 5.2 feed rows).

use crate::model::Event;

/// Extract HH:MM:SS from an ISO-8601 UTC timestamp such as
/// 2026-10-18T08:37:26.693Z. Falls back to --:--:-- when the string does
/// not carry a parseable time-of-day.
pub fn iso_hms(ts: &str) -> String {
    let bytes = ts.as_bytes();
    // Expect the T separator at byte offset 10 for YYYY-MM-DDTHH:MM:SS.
    if bytes.len() >= 19 && bytes[10] == b'T' {
        let hms = &ts[11..19];
        if hms.len() == 8 && hms.as_bytes()[2] == b':' && hms.as_bytes()[5] == b':' {
            return hms.to_string();
        }
    }
    "--:--:--".to_string()
}

/// Days since 1970-01-01 for a proleptic-Gregorian date (Howard Hinnant's
/// days_from_civil algorithm).
fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

/// Parse an ISO-8601 UTC timestamp (YYYY-MM-DDTHH:MM:SS[.fff][Z]) into a
/// Unix epoch in seconds. Returns None when the shape does not match.
pub fn iso_epoch(ts: &str) -> Option<i64> {
    let b = ts.as_bytes();
    if b.len() < 19 || b[4] != b'-' || b[7] != b'-' || b[10] != b'T' {
        return None;
    }
    let y: i64 = ts.get(0..4)?.parse().ok()?;
    let mo: i64 = ts.get(5..7)?.parse().ok()?;
    let d: i64 = ts.get(8..10)?.parse().ok()?;
    let h: i64 = ts.get(11..13)?.parse().ok()?;
    let mi: i64 = ts.get(14..16)?.parse().ok()?;
    let s: i64 = ts.get(17..19)?.parse().ok()?;
    if !(1..=12).contains(&mo) || !(1..=31).contains(&d) || h > 23 || mi > 59 || s > 60 {
        return None;
    }
    Some(days_from_civil(y, mo, d) * 86400 + h * 3600 + mi * 60 + s)
}

/// Inverse of the calendar math in iso_epoch: civil date from days since epoch.
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m as u32, d as u32)
}

/// Format a Unix epoch as YYYY-MM-DD HH:MM:SS (UTC).
pub fn fmt_utc_datetime(epoch: i64) -> String {
    let days = epoch.div_euclid(86400);
    let secs = epoch.rem_euclid(86400);
    let (y, m, d) = civil_from_days(days);
    format!(
        "{:04}-{:02}-{:02} {:02}:{:02}:{:02}",
        y,
        m,
        d,
        secs / 3600,
        (secs % 3600) / 60,
        secs % 60
    )
}

/// Format a duration in seconds as HH:MM:SS (clamped at 99 hours).
pub fn fmt_duration(seconds: i64) -> String {
    let s = seconds.clamp(0, 99 * 3600 + 59 * 60 + 59);
    format!("{:02}:{:02}:{:02}", s / 3600, (s % 3600) / 60, s % 60)
}

/// Truncate a string to at most max terminal display columns without
/// splitting a wide (CJK) glyph. No ellipsis is added.
pub fn truncate_width(s: &str, max: usize) -> String {
    if max == 0 {
        return String::new();
    }
    let mut out = String::with_capacity(s.len().min(max * 4));
    let mut width = 0usize;
    for ch in s.chars() {
        let w = unicode_width::UnicodeWidthChar::width(ch).unwrap_or(0);
        if width + w > max {
            break;
        }
        out.push(ch);
        width += w;
    }
    out
}

/// Display width of a string in terminal columns.
pub fn display_width(s: &str) -> usize {
    unicode_width::UnicodeWidthStr::width(s)
}

/// Remove ANSI escape sequences (CSI and OSC) and normalise control
/// characters so a pane line can never wrap or corrupt the layout.
pub fn sanitize_line(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars().peekable();
    while let Some(ch) = chars.next() {
        match ch {
            '\u{1b}' => {
                // ESC [ ... final-byte  |  ESC ] ... (BEL | ESC backslash)
                match chars.peek() {
                    Some('[') => {
                        chars.next();
                        for c in chars.by_ref() {
                            if ('@'..='~').contains(&c) {
                                break;
                            }
                        }
                    }
                    Some(']') => {
                        chars.next();
                        while let Some(c) = chars.next() {
                            if c == '\u{7}' {
                                break;
                            }
                            if c == '\u{1b}' {
                                if chars.peek() == Some(&'\\') {
                                    chars.next();
                                }
                                break;
                            }
                        }
                    }
                    _ => {
                        chars.next();
                    }
                }
            }
            '\r' => {}
            '\t' => out.push_str("    "),
            c if (c as u32) < 0x20 => {}
            c => out.push(c),
        }
    }
    out
}

/// Short source tag used in feed rows: firstmate -> FM (report 5.2-B).
pub fn source_tag(source: &str) -> &str {
    match source {
        "firstmate" => "FM",
        "herdr" => "Herdr",
        "pi" => "Pi",
        "service" => "Service",
        other => other,
    }
}

fn payload_str<'a>(p: &'a serde_json::Map<String, serde_json::Value>, k: &str) -> Option<&'a str> {
    p.get(k).and_then(|v| v.as_str())
}

fn payload_line_count(p: &serde_json::Map<String, serde_json::Value>) -> Option<usize> {
    p.get("lines").and_then(|v| v.as_array()).map(|a| a.len())
}

/// Format the body of a feed row for one event (without the timestamp and
/// source tag). Mirrors the payload shapes the service really emits.
pub fn format_event(event: &Event) -> String {
    let p = &event.payload;
    let task = event.task_id.as_deref().unwrap_or(strings::DASH);
    match event.kind.as_str() {
        "task_state_changed" => {
            let state = payload_str(p, "state").unwrap_or("?");
            let note = payload_str(p, "note").unwrap_or("");
            if note.is_empty() {
                strings::evt_state(task, state)
            } else {
                strings::evt_state_note(task, state, note)
            }
        }
        "decision_needed" => {
            let note = payload_str(p, "note").unwrap_or("");
            if note.is_empty() {
                strings::evt_decision(task)
            } else {
                strings::evt_decision_note(task, note)
            }
        }
        "task_completed" => strings::evt_completed(task),
        "task_failed" => {
            let note = payload_str(p, "note").unwrap_or("");
            if note.is_empty() {
                strings::evt_failed(task)
            } else {
                strings::evt_failed_note(task, note)
            }
        }
        "agent_status_changed" => {
            let pane = event.pane_id.as_deref().unwrap_or(strings::DASH);
            let cur = payload_str(p, "current_status").unwrap_or("unknown");
            match payload_str(p, "previous_status") {
                Some(prev) => strings::evt_status_prev(pane, prev, cur),
                None => strings::evt_status_cur(pane, cur),
            }
        }
        "pane_output" => {
            let pane = event.pane_id.as_deref().unwrap_or(strings::DASH);
            match payload_line_count(p) {
                Some(n) => strings::evt_pane_lines(pane, n),
                None => match payload_str(p, "matched") {
                    Some(m) => strings::evt_pane_matched(pane, m),
                    None => strings::evt_pane_plain(pane),
                },
            }
        }
        "tool_call" => {
            let tool = payload_str(p, "toolName").unwrap_or("tool");
            match payload_str(p, "command") {
                Some(cmd) => strings::evt_tool_call(tool, cmd),
                None => strings::evt_tool_call_bare(tool),
            }
        }
        "tool_result" => {
            let tool = payload_str(p, "toolName").unwrap_or("tool");
            let is_err = p.get("isError").and_then(|v| v.as_bool()) == Some(true);
            let first = payload_str(p, "text").unwrap_or("").lines().next().unwrap_or("");
            if is_err {
                strings::evt_tool_err(tool, first)
            } else {
                strings::evt_tool_ok(tool, first)
            }
        }
        "thinking_chunk" => {
            let channel = payload_str(p, "channel").unwrap_or("text");
            let text = payload_str(p, "text").unwrap_or("");
            let first = text.lines().next().unwrap_or("");
            strings::evt_thinking(channel, first)
        }
        other => other.to_string(),
    }
}

/// Full feed row: HH:MM:SS [SRC] body (report 5.2-B activity feed).
pub fn format_event_line(event: &Event) -> String {
    format!(
        "{} [{}] {}",
        iso_hms(&event.timestamp),
        source_tag(&event.source),
        format_event(event)
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Event;
    use serde_json::json;

    fn ev(kind: &str, source: &str, payload: serde_json::Value) -> Event {
        Event {
            event_id: "e1".into(),
            seq: 1,
            task_id: Some("agentpulse-tui".into()),
            pane_id: None,
            source: source.into(),
            kind: kind.into(),
            severity: "info".into(),
            timestamp: "2026-10-18T08:37:26.693Z".into(),
            payload: payload.as_object().unwrap().clone(),
        }
    }

    #[test]
    fn iso_hms_extracts_time() {
        assert_eq!(iso_hms("2026-10-18T08:37:26.693Z"), "08:37:26");
        assert_eq!(iso_hms("2026-10-18T08:37:26Z"), "08:37:26");
        assert_eq!(iso_hms("garbage"), "--:--:--");
    }

    #[test]
    fn iso_epoch_known_values() {
        assert_eq!(iso_epoch("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(iso_epoch("2026-10-18T08:37:26Z"), Some(1792377446));
        assert_eq!(iso_epoch("2000-02-29T12:00:00Z"), Some(951782400));
        assert_eq!(iso_epoch("nope"), None);
    }

    #[test]
    fn fmt_utc_datetime_roundtrip() {
        assert_eq!(fmt_utc_datetime(0), "1970-01-01 00:00:00");
        assert_eq!(fmt_utc_datetime(1792377446), "2026-10-18 08:37:26");
        assert_eq!(fmt_utc_datetime(951782400), "2000-02-29 12:00:00");
    }

    #[test]
    fn duration_format() {
        assert_eq!(fmt_duration(0), "00:00:00");
        assert_eq!(fmt_duration(862), "00:14:22");
        assert_eq!(fmt_duration(360000), "99:59:59");
    }

    #[test]
    fn truncate_respects_cjk_width() {
        assert_eq!(truncate_width("hello world", 5), "hello");
        assert_eq!(truncate_width("中文中文", 6), "中文");
        assert_eq!(truncate_width("中文中文", 7), "中文");
        assert_eq!(truncate_width("中文中文", 8), "中文中文");
        assert_eq!(truncate_width("abc", 0), "");
        assert_eq!(display_width("中文"), 4);
    }

    #[test]
    fn sanitize_strips_ansi_and_controls() {
        assert_eq!(sanitize_line("\u{1b}[32mgreen\u{1b}[0m text"), "green text");
        assert_eq!(sanitize_line("a\r\nb"), "ab");
        assert_eq!(sanitize_line("a\tb"), "a    b");
        assert_eq!(sanitize_line("\u{1b}]0;title\u{7}body"), "body");
        assert_eq!(sanitize_line("plain text"), "plain text");
    }

    #[test]
    fn source_tags() {
        assert_eq!(source_tag("firstmate"), "FM");
        assert_eq!(source_tag("herdr"), "Herdr");
        assert_eq!(source_tag("pi"), "Pi");
        assert_eq!(source_tag("service"), "Service");
        assert_eq!(source_tag("weird"), "weird");
    }

    #[test]
    fn event_lines_match_service_payloads() {
        let line = format_event_line(&ev(
            "task_state_changed",
            "firstmate",
            json!({"state": "working", "note": "harness busy (herdr-native)"}),
        ));
        assert_eq!(line, "08:37:26 [FM] agentpulse-tui -> working · harness busy (herdr-native)");

        let line = format_event_line(&ev(
            "agent_status_changed",
            "herdr",
            json!({"pane_id": "w16:p0", "previous_status": "idle", "current_status": "working"}),
        ));
        assert_eq!(line, "08:37:26 [Herdr] w16:p0 idle->working");

        let line = format_event_line(&ev(
            "pane_output",
            "herdr",
            json!({"pane_id": "w16:p0", "lines": ["> esc to cancel"]}),
        ));
        assert_eq!(line, "08:37:26 [Herdr] w16:p0 输出 +1 行");

        let line = format_event_line(&ev(
            "tool_call",
            "pi",
            json!({"toolName": "bash", "command": "cargo build"}),
        ));
        assert_eq!(line, "08:37:26 [Pi] bash cargo build");

        let line = format_event_line(&ev(
            "tool_result",
            "pi",
            json!({"toolName": "bash", "isError": true, "text": "error: failed to compile"}),
        ));
        assert_eq!(line, "08:37:26 [Pi] bash ERR error: failed to compile");

        let line = format_event_line(&ev(
            "tool_result",
            "pi",
            json!({"toolName": "edit", "isError": false, "text": "ok"}),
        ));
        assert_eq!(line, "08:37:26 [Pi] edit ok ok");

        let line = format_event_line(&ev(
            "thinking_chunk",
            "pi",
            json!({"channel": "text", "text": "I will start reading"}),
        ));
        assert_eq!(line, "08:37:26 [Pi] text: I will start reading");

        let line = format_event_line(&ev(
            "task_failed",
            "firstmate",
            json!({"state": "failed", "note": "quota exhausted"}),
        ));
        assert_eq!(line, "08:37:26 [FM] agentpulse-tui 失败 · quota exhausted");

        let line = format_event_line(&ev("task_completed", "firstmate", json!({})));
        assert_eq!(line, "08:37:26 [FM] agentpulse-tui 完成");
    }
}

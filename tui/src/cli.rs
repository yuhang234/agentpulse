//! Command line and service-URL handling.
//!
//! Base URL precedence: --url flag, then AGENTPULSE_SERVICE_URL, then the
//! default http://127.0.0.1:7890 (service/agentpulse_service/config.py).

use std::env;

pub const DEFAULT_URL: &str = "http://127.0.0.1:7890";
pub const ENV_URL: &str = "AGENTPULSE_SERVICE_URL";

#[derive(Debug, Clone)]
pub struct Cli {
    /// Display form of the base URL, e.g. http://127.0.0.1:7890.
    pub url: String,
    /// Poll interval in seconds (REST refresh + feed fallback).
    pub interval: u64,
    /// Seconds without any event or poll before the link is shown stale.
    pub stale_after: u64,
}

impl Default for Cli {
    fn default() -> Self {
        Self {
            url: DEFAULT_URL.to_string(),
            interval: 3,
            stale_after: 10,
        }
    }
}

/// Why parsing failed; help and version are successful exits, not errors.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CliErrorKind {
    /// Bad arguments: print to stderr, exit 2.
    Usage,
    /// --help: print to stdout, exit 0.
    Help,
    /// --version: print to stdout, exit 0.
    Version,
}

#[derive(Debug, Clone)]
pub struct CliError {
    pub message: String,
    pub kind: CliErrorKind,
}

impl std::fmt::Display for CliError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.message)
    }
}

impl std::error::Error for CliError {}

/// A parsed service base URL split into its HTTP and WebSocket forms.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BaseUrl {
    /// scheme://host[:port][/path] for REST calls (http or https).
    pub http: String,
    /// scheme://host[:port][/path]/ws for the event stream (ws or wss).
    pub ws: String,
    /// host[:port] for display.
    pub host: String,
    /// True when the base scheme is https/wss.
    pub tls: bool,
}

/// Percent-encode a path segment (task ids) per RFC 3986 unreserved set.
pub fn path_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        let ok = b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~');
        if ok {
            out.push(b as char);
        } else {
            out.push('%');
            out.push(char::from_digit((b >> 4) as u32, 16).unwrap().to_ascii_uppercase());
            out.push(char::from_digit((b & 0xf) as u32, 16).unwrap().to_ascii_uppercase());
        }
    }
    out
}

/// Parse a service base URL. Accepts http(s):// and ws(s):// schemes; a ws
/// base is converted to its http twin for REST calls.
pub fn parse_base_url(raw: &str) -> Result<BaseUrl, String> {
    let raw = raw.trim();
    let (scheme, rest) = raw.split_once("://").ok_or_else(|| {
        format!("invalid service URL {raw:?}: missing scheme (use http://host:port)")
    })?;
    let tls = match scheme.to_ascii_lowercase().as_str() {
        "http" | "ws" => false,
        "https" | "wss" => true,
        other => {
            return Err(format!(
                "invalid service URL {raw:?}: unsupported scheme {other:?} (use http, https, ws or wss)"
            ))
        }
    };
    if rest.is_empty() {
        return Err(format!("invalid service URL {raw:?}: empty host"));
    }
    let (authority, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, ""),
    };
    if authority.is_empty() {
        return Err(format!("invalid service URL {raw:?}: empty host"));
    }
    let path = path.trim_end_matches('/');
    let http_scheme = if tls { "https" } else { "http" };
    let ws_scheme = if tls { "wss" } else { "ws" };
    Ok(BaseUrl {
        http: format!("{http_scheme}://{authority}{path}"),
        ws: format!("{ws_scheme}://{authority}{path}/ws"),
        host: authority.to_string(),
        tls,
    })
}

const USAGE: &str = "agentpulse-tui - AgentPulse read-only terminal dashboard

Usage: agentpulse-tui [--url <URL>] [--interval <secs>] [--stale-after <secs>]

Options:
  -u, --url <URL>          Service base URL (default http://127.0.0.1:7890;
                          env AGENTPULSE_SERVICE_URL also honoured)
  -i, --interval <secs>    REST poll interval in seconds (default 3)
      --stale-after <secs> Seconds without data before showing stale (default 10)
  -h, --help               Print help
  -V, --version            Print version";

fn usage_err(message: String) -> CliError {
    CliError {
        message: format!("{message}\n\n{USAGE}"),
        kind: CliErrorKind::Usage,
    }
}

/// Parse argv (without the program name).
pub fn parse_args(args: &[String]) -> Result<Cli, CliError> {
    let mut cli = Cli::default();
    let mut url_flag: Option<String> = None;
    let mut positional: Option<String> = None;
    let mut i = 0;
    while i < args.len() {
        let arg = &args[i];
        if arg == "-h" || arg == "--help" {
            return Err(CliError {
                message: USAGE.to_string(),
                kind: CliErrorKind::Help,
            });
        }
        if arg == "-V" || arg == "--version" {
            return Err(CliError {
                message: format!("agentpulse-tui {}", env!("CARGO_PKG_VERSION")),
                kind: CliErrorKind::Version,
            });
        }
        let take_value = |name: &str, idx: &mut usize| -> Result<String, CliError> {
            *idx += 1;
            if *idx >= args.len() {
                return Err(usage_err(format!("missing value for {name}")));
            }
            Ok(args[*idx].clone())
        };
        if let Some(v) = arg.strip_prefix("--url=") {
            url_flag = Some(v.to_string());
        } else if let Some(v) = arg.strip_prefix("--interval=") {
            cli.interval = parse_secs("--interval", v)?;
        } else if let Some(v) = arg.strip_prefix("--stale-after=") {
            cli.stale_after = parse_secs("--stale-after", v)?;
        } else if arg == "--url" || arg == "-u" {
            url_flag = Some(take_value("--url", &mut i)?);
        } else if arg == "--interval" || arg == "-i" {
            cli.interval = parse_secs("--interval", &take_value("--interval", &mut i)?)?;
        } else if arg == "--stale-after" {
            cli.stale_after = parse_secs("--stale-after", &take_value("--stale-after", &mut i)?)?;
        } else if arg.starts_with('-') {
            return Err(usage_err(format!("unknown option {arg:?}")));
        } else if positional.is_none() {
            positional = Some(arg.clone());
        } else {
            return Err(usage_err(format!("unexpected argument {arg:?}")));
        }
        i += 1;
    }
    let url = url_flag
        .or_else(|| env::var(ENV_URL).ok().filter(|s| !s.trim().is_empty()))
        .or(positional)
        .unwrap_or_else(|| DEFAULT_URL.to_string());
    // Validate now so a typo fails fast instead of at first connect.
    parse_base_url(&url).map_err(|e| usage_err(e))?;
    cli.url = url;
    if cli.interval == 0 {
        return Err(usage_err("--interval must be at least 1 second".to_string()));
    }
    Ok(cli)
}

fn parse_secs(name: &str, v: &str) -> Result<u64, CliError> {
    v.parse::<u64>()
        .map_err(|_| usage_err(format!("invalid value for {name}: {v:?} (expected whole seconds)")))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn default_url() {
        let cli = parse_args(&[]).unwrap();
        assert_eq!(cli.url, "http://127.0.0.1:7890");
        assert_eq!(cli.interval, 3);
        assert_eq!(cli.stale_after, 10);
    }

    #[test]
    fn url_flag_forms() {
        assert_eq!(
            parse_args(&args(&["--url", "http://box:7891"])).unwrap().url,
            "http://box:7891"
        );
        assert_eq!(
            parse_args(&args(&["--url=http://box:7891"])).unwrap().url,
            "http://box:7891"
        );
        assert_eq!(
            parse_args(&args(&["-u", "http://box:7891"])).unwrap().url,
            "http://box:7891"
        );
        // A bare positional is accepted as the URL too.
        assert_eq!(
            parse_args(&args(&["http://box:7891"])).unwrap().url,
            "http://box:7891"
        );
    }

    #[test]
    fn interval_and_stale_after() {
        let cli = parse_args(&args(&["--interval", "5", "--stale-after", "20"])).unwrap();
        assert_eq!(cli.interval, 5);
        assert_eq!(cli.stale_after, 20);
        assert!(parse_args(&args(&["--interval", "abc"])).is_err());
        assert!(parse_args(&args(&["--interval", "0"])).is_err());
        assert!(parse_args(&args(&["--interval"])).is_err());
    }

    #[test]
    fn help_and_version_kinds() {
        let e = parse_args(&args(&["--help"])).unwrap_err();
        assert_eq!(e.kind, CliErrorKind::Help);
        assert!(e.message.contains("Usage: agentpulse-tui"));
        let e = parse_args(&args(&["--version"])).unwrap_err();
        assert_eq!(e.kind, CliErrorKind::Version);
        assert!(e.message.contains("agentpulse-tui 1.0.0"));
    }

    #[test]
    fn bad_args_are_usage_errors() {
        let e = parse_args(&args(&["--nope"])).unwrap_err();
        assert_eq!(e.kind, CliErrorKind::Usage);
        assert!(e.message.contains("unknown option"));
    }

    #[test]
    fn bad_urls_rejected() {
        assert!(parse_args(&args(&["--url", "127.0.0.1:7890"])).is_err());
        assert!(parse_args(&args(&["--url", "ftp://x"])).is_err());
        assert!(parse_args(&args(&["--url", "http://"])).is_err());
        assert!(parse_args(&args(&["--url", "http://host/path"])).is_ok());
    }

    #[test]
    fn base_url_derivation() {
        let b = parse_base_url("http://127.0.0.1:7890").unwrap();
        assert_eq!(b.http, "http://127.0.0.1:7890");
        assert_eq!(b.ws, "ws://127.0.0.1:7890/ws");
        assert_eq!(b.host, "127.0.0.1:7890");
        assert!(!b.tls);

        // Trailing slash and sub-path are normalised.
        let b = parse_base_url("http://host:7890/api/").unwrap();
        assert_eq!(b.http, "http://host:7890/api");
        assert_eq!(b.ws, "ws://host:7890/api/ws");

        // TLS variants.
        let b = parse_base_url("https://host/").unwrap();
        assert_eq!(b.http, "https://host");
        assert_eq!(b.ws, "wss://host/ws");
        assert!(b.tls);

        // A ws:// base yields the http twin for REST.
        let b = parse_base_url("ws://host:7890").unwrap();
        assert_eq!(b.http, "http://host:7890");
        assert_eq!(b.ws, "ws://host:7890/ws");
    }

    #[test]
    fn path_encode_unreserved() {
        assert_eq!(path_encode("agentpulse-tui"), "agentpulse-tui");
        assert_eq!(path_encode("a b/c"), "a%20b%2Fc");
        assert_eq!(path_encode("w16:p0"), "w16%3Ap0");
        assert_eq!(path_encode("~-._"), "~-._");
    }
}

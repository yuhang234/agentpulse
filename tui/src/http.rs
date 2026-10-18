//! Minimal HTTP/1.1 client over std::net::TcpStream.
//!
//! The client only ever talks to the AgentPulse monitoring service (plain
//! HTTP on loopback or over an SSH tunnel), so TLS is out of scope: an
//! https:// base URL is rejected up front. Keeping this dependency-free
//! avoids pulling a TLS stack into the binary.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::str::FromStr;
use std::time::Duration;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(3);
const IO_TIMEOUT: Duration = Duration::from_secs(5);
const MAX_BODY: usize = 8 * 1024 * 1024;

#[derive(Debug)]
pub enum HttpError {
    HttpsUnsupported,
    BadUrl(String),
    Connect(String),
    Io(String),
    Malformed(String),
}

impl std::fmt::Display for HttpError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            HttpError::HttpsUnsupported => write!(
                f,
                "https is not supported: the AgentPulse service speaks plain HTTP"
            ),
            HttpError::BadUrl(s) => write!(f, "bad URL: {s}"),
            HttpError::Connect(s) => write!(f, "connect failed: {s}"),
            HttpError::Io(s) => write!(f, "io error: {s}"),
            HttpError::Malformed(s) => write!(f, "malformed response: {s}"),
        }
    }
}

impl std::error::Error for HttpError {}

#[derive(Debug, Clone)]
pub struct HttpResponse {
    pub status: u16,
    pub body: String,
}

struct Target {
    host: String,
    port: u16,
    /// Path including query, e.g. /api/v1/events?since_seq=3.
    path: String,
}

fn parse_url(url: &str) -> Result<Target, HttpError> {
    let (scheme, rest) = url
        .split_once("://")
        .ok_or_else(|| HttpError::BadUrl(format!("missing scheme in {url:?}")))?;
    if !scheme.eq_ignore_ascii_case("http") {
        return Err(HttpError::HttpsUnsupported);
    }
    let (authority, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    let (host, port) = match authority.rsplit_once(':') {
        Some((h, p)) => {
            let port: u16 = p
                .parse()
                .map_err(|_| HttpError::BadUrl(format!("bad port in {url:?}")))?;
            (h.to_string(), port)
        }
        None => (authority.to_string(), 80),
    };
    if host.is_empty() {
        return Err(HttpError::BadUrl(format!("empty host in {url:?}")));
    }
    Ok(Target {
        host,
        port,
        path: path.to_string(),
    })
}

/// Perform a GET and return status plus body. Timeouts bound connect, each
/// read and each write so a hung service can never wedge the UI thread.
pub fn get(url: &str) -> Result<HttpResponse, HttpError> {
    let target = parse_url(url)?;
    let addr = format!("{}:{}", target.host, target.port);
    let mut stream = TcpStream::connect_timeout(
        &addr
            .parse()
            .map_err(|_| HttpError::BadUrl(format!("bad address {addr:?}")))?,
        CONNECT_TIMEOUT,
    )
    .map_err(|e| HttpError::Connect(format!("{addr}: {e}")))?;
    stream
        .set_read_timeout(Some(IO_TIMEOUT))
        .map_err(|e| HttpError::Io(e.to_string()))?;
    stream
        .set_write_timeout(Some(IO_TIMEOUT))
        .map_err(|e| HttpError::Io(e.to_string()))?;

    let request = format!(
        "GET {path} HTTP/1.1\r\nHost: {host}\r\nAccept: application/json\r\n         User-Agent: agentpulse-tui/{ver}\r\nConnection: close\r\n\r\n",
        path = target.path,
        host = target.host,
        ver = env!("CARGO_PKG_VERSION"),
    );
    stream
        .write_all(request.as_bytes())
        .map_err(|e| HttpError::Io(e.to_string()))?;
    stream
        .flush()
        .map_err(|e| HttpError::Io(e.to_string()))?;

    let raw = read_full(&mut stream)?;
    let parsed = parse_response(&raw)?;
    Ok(HttpResponse {
        status: parsed.status,
        body: String::from_utf8_lossy(&parsed.body).into_owned(),
    })
}

fn read_full(stream: &mut TcpStream) -> Result<Vec<u8>, HttpError> {
    let mut buf: Vec<u8> = Vec::with_capacity(16 * 1024);
    let mut tmp = [0u8; 16 * 1024];
    loop {
        match stream.read(&mut tmp) {
            Ok(0) => break,
            Ok(n) => {
                buf.extend_from_slice(&tmp[..n]);
                if buf.len() > MAX_BODY {
                    return Err(HttpError::Malformed("response too large".into()));
                }
                if response_complete(&buf) {
                    break;
                }
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => {
                if buf.is_empty() || !response_complete(&buf) {
                    return Err(HttpError::Io(e.to_string()));
                }
                break;
            }
        }
    }
    Ok(buf)
}

struct RawResponse {
    status: u16,
    body: Vec<u8>,
}

fn header_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n")
}

fn response_complete(buf: &[u8]) -> bool {
    let Some(end) = header_end(buf) else {
        return false;
    };
    let head = String::from_utf8_lossy(&buf[..end]).to_ascii_lowercase();
    let body = &buf[end + 4..];
    if let Some(len) = head
        .lines()
        .find_map(|l| l.strip_prefix("content-length:").map(|v| v.trim().to_string()))
    {
        return match len.parse::<usize>() {
            Ok(n) => body.len() >= n,
            Err(_) => false,
        };
    }
    if head.contains("transfer-encoding: chunked") {
        return body.windows(5).any(|w| w == b"0\r\n\r\n")
            || body.ends_with(b"0\r\n\r\n");
    }
    // Close-delimited body: completeness is only known at EOF, which the
    // caller's read loop handles.
    false
}

fn parse_response(buf: &[u8]) -> Result<RawResponse, HttpError> {
    let end = header_end(buf).ok_or_else(|| HttpError::Malformed("no header end".into()))?;
    let head = String::from_utf8_lossy(&buf[..end]);
    let mut lines = head.lines();
    let status_line = lines
        .next()
        .ok_or_else(|| HttpError::Malformed("empty response".into()))?;
    let mut parts = status_line.split_whitespace();
    let _version = parts
        .next()
        .ok_or_else(|| HttpError::Malformed("bad status line".into()))?;
    let status: u16 = parts
        .next()
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| HttpError::Malformed(format!("bad status line {status_line:?}")))?;
    let head_lower = head.to_ascii_lowercase();
    let chunked = head_lower.contains("transfer-encoding: chunked");
    let mut body = buf[end + 4..].to_vec();
    if chunked {
        body = dechunk(&body)?;
    } else if let Some(len) = head_lower
        .lines()
        .find_map(|l| l.strip_prefix("content-length:").map(|v| v.trim().to_string()))
    {
        if let Ok(n) = usize::from_str(&len) {
            body.truncate(n);
        }
    }
    Ok(RawResponse { status, body })
}

/// Decode a chunked body into its concatenated payload.
fn dechunk(body: &[u8]) -> Result<Vec<u8>, HttpError> {
    let mut out = Vec::new();
    let mut rest = body;
    loop {
        let line_end = rest
            .windows(2)
            .position(|w| w == b"\r\n")
            .ok_or_else(|| HttpError::Malformed("chunk without size".into()))?;
        let size_str = String::from_utf8_lossy(&rest[..line_end]);
        let size = usize::from_str_radix(size_str.trim().split(';').next().unwrap_or(""), 16)
            .map_err(|_| HttpError::Malformed(format!("bad chunk size {size_str:?}")))?;
        rest = &rest[line_end + 2..];
        if size == 0 {
            break;
        }
        if rest.len() < size + 2 {
            return Err(HttpError::Malformed("short chunk".into()));
        }
        out.extend_from_slice(&rest[..size]);
        rest = &rest[size + 2..]; // skip trailing CRLF
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader};
    use std::net::TcpListener;
    use std::thread;

    /// Serve one canned response on an ephemeral loopback port and return the
    /// URL plus a handle that yields the request line the client sent.
    fn serve_once(response: &'static str) -> (String, thread::JoinHandle<String>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://127.0.0.1:{}/", listener.local_addr().unwrap().port());
        let handle = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let mut reader = BufReader::new(stream.try_clone().unwrap());
            let request_line = reader.by_ref().lines().next().unwrap().unwrap();
            // Drain the rest of the request headers.
            loop {
                let mut line = String::new();
                if reader.read_line(&mut line).unwrap() == 0 || line == "\r\n" {
                    break;
                }
            }
            let mut stream = reader.into_inner();
            stream.write_all(response.as_bytes()).unwrap();
            stream.flush().unwrap();
            request_line
        });
        (url, handle)
    }

    #[test]
    fn get_parses_content_length_response() {
        let body = "{\"ok\":true}";
        let resp = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
            body.len(),
            body
        );
        let (url, handle) = serve_once(Box::leak(resp.into_boxed_str()));
        let r = get(&url).unwrap();
        assert_eq!(r.status, 200);
        assert_eq!(r.body, body);
        assert!(handle.join().unwrap().starts_with("GET / "));
    }

    #[test]
    fn get_parses_chunked_response() {
        let resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n";
        let (url, _handle) = serve_once(Box::leak(resp.to_string().into_boxed_str()));
        let r = get(&url).unwrap();
        assert_eq!(r.status, 200);
        assert_eq!(r.body, "hello world");
    }

    #[test]
    fn get_returns_status_and_body_for_404() {
        let body = "{\"detail\":\"task not found\"}";
        let resp = format!(
            "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
            body.len(),
            body
        );
        let (url, _handle) = serve_once(Box::leak(resp.into_boxed_str()));
        let r = get(&url).unwrap();
        assert_eq!(r.status, 404);
        assert!(r.body.contains("task not found"));
    }

    #[test]
    fn get_query_string_preserved() {
        let resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}";
        let (url, handle) = serve_once(Box::leak(resp.to_string().into_boxed_str()));
        let r = get(&format!("{url}api/v1/events?since_seq=7&limit=10")).unwrap();
        assert_eq!(r.body, "{}");
        assert_eq!(
            handle.join().unwrap(),
            "GET /api/v1/events?since_seq=7&limit=10 HTTP/1.1"
        );
    }

    #[test]
    fn connection_refused_is_error() {
        // Bind and immediately drop to get a closed port.
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        drop(listener);
        let err = get(&format!("http://127.0.0.1:{port}/api/v1/health")).unwrap_err();
        assert!(matches!(err, HttpError::Connect(_)), "got {err:?}");
    }

    #[test]
    fn https_is_rejected() {
        let err = get("https://127.0.0.1:7890/api/v1/health").unwrap_err();
        assert!(matches!(err, HttpError::HttpsUnsupported));
    }

    #[test]
    fn dechunk_handles_extensions_and_trailers() {
        assert_eq!(dechunk(b"4;ext=1\r\ndata\r\n0\r\nX-T: 1\r\n\r\n").unwrap(), b"data");
    }
}

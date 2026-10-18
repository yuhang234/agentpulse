//! agentpulse-tui: the AgentPulse Debian TUI client.
//!
//! A strictly read-only terminal dashboard over the AgentPulse monitoring
//! service (FirstMate tasks, Herdr panes, Pi sessions). It observes; it
//! never controls. See tui/README.md for build, run and key bindings.

mod app;
mod cli;
mod format;
mod http;
mod layout;
mod model;
mod net;
mod palette;
mod strings;
mod ui;

use std::io::{self, Write};
use std::sync::mpsc::Receiver;
use std::time::Duration;

use crossterm::cursor::{Hide, Show};
use crossterm::event::{self, Event, KeyEventKind};
use crossterm::execute;
use crossterm::terminal::{
    disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen,
};
use ratatui::backend::CrosstermBackend;
use ratatui::Terminal;

use crate::app::App;
use crate::cli::Cli;
use crate::net::{NetEvent, Workers};

fn main() -> std::process::ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let cli = match cli::parse_args(&args) {
        Ok(cli) => cli,
        Err(cli::CliError { message, kind }) => match kind {
            cli::CliErrorKind::Help | cli::CliErrorKind::Version => {
                println!("{message}");
                return std::process::ExitCode::SUCCESS;
            }
            cli::CliErrorKind::Usage => {
                eprintln!("{message}");
                return std::process::ExitCode::from(2);
            }
        },
    };
    match run(cli) {
        Ok(()) => std::process::ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("agentpulse-tui: {e}");
            std::process::ExitCode::FAILURE
        }
    }
}

fn run(cli: Cli) -> Result<(), String> {
    // The guard restores the console even if the loop panics.
    let _guard = TerminalGuard::new()?;
    let mut terminal =
        Terminal::new(CrosstermBackend::new(io::stdout())).map_err(|e| e.to_string())?;
    terminal.clear().map_err(|e| e.to_string())?;

    let mut app = App::new(cli);
    if let Ok((w, h)) = crossterm::terminal::size() {
        app.size = (w, h);
    }

    let (tx, rx) = std::sync::mpsc::channel();
    let workers = net::spawn(app.base.clone(), app.cli.interval, tx);

    let result = event_loop(&mut terminal, &mut app, &rx, &workers);

    workers.shutdown();
    result
}

fn event_loop(
    terminal: &mut Terminal<CrosstermBackend<io::Stdout>>,
    app: &mut App,
    rx: &Receiver<NetEvent>,
    workers: &Workers,
) -> Result<(), String> {
    loop {
        // Apply everything the workers produced since the last frame.
        while let Ok(ev) = rx.try_recv() {
            app.on_net(ev, workers);
        }
        // Input with a bounded wait keeps the clock ticking at ~20fps.
        if event::poll(Duration::from_millis(50)).map_err(|e| e.to_string())? {
            match event::read().map_err(|e| e.to_string())? {
                Event::Key(key)
                    if matches!(key.kind, KeyEventKind::Press | KeyEventKind::Repeat) =>
                {
                    app.on_key(key, workers);
                }
                Event::Resize(w, h) => app.size = (w, h),
                _ => {}
            }
        }
        if app.quit {
            return Ok(());
        }
        terminal
            .draw(|frame| ui::render(frame, app))
            .map_err(|e| e.to_string())?;
    }
}

/// Puts the terminal into raw mode + the alternate screen and always puts
/// it back, including on panics.
struct TerminalGuard;

impl TerminalGuard {
    fn new() -> Result<Self, String> {
        enable_raw_mode().map_err(|e| format!("enable raw mode: {e}"))?;
        execute!(io::stdout(), EnterAlternateScreen, Hide)
            .map_err(|e| format!("enter alternate screen: {e}"))?;
        Ok(Self)
    }
}

impl Drop for TerminalGuard {
    fn drop(&mut self) {
        let mut stdout = io::stdout();
        let _ = execute!(stdout, Show, LeaveAlternateScreen);
        let _ = stdout.flush();
        let _ = disable_raw_mode();
    }
}

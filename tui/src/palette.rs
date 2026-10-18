//! Colour roles and terminal-capability degradation (design report 5.3).

use ratatui::style::{Color, Modifier, Style};

/// What the terminal can actually render, decided once at startup.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ColorLevel {
    /// 24-bit truecolor (terminal advertises COLORTERM=truecolor|24bit).
    TrueColor,
    /// 256-colour indexed palette.
    Color256,
    /// Standard 16 ANSI colours.
    Ansi16,
}

/// Decide the colour level from the environment, per the brief: truecolor
/// when advertised, 256-colour indexes next, 16-colour ANSI otherwise.
pub fn detect_color_level(colorterm: Option<&str>, term: Option<&str>) -> ColorLevel {
    if let Some(ct) = colorterm {
        let ct = ct.trim().to_ascii_lowercase();
        if ct == "truecolor" || ct == "24bit" {
            return ColorLevel::TrueColor;
        }
    }
    if let Some(t) = term {
        if t.contains("256color") {
            return ColorLevel::Color256;
        }
    }
    ColorLevel::Ansi16
}

/// Semantic palette roles from report 5.3.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    /// Active / in-progress work.
    Working,
    /// Success / completion.
    Success,
    /// Blocked / held / warning.
    Warning,
    /// Failure / error.
    Error,
    /// Secondary text and borders.
    Muted,
    /// Focused panel highlight.
    Focus,
}

/// Resolved palette for the current terminal capability.
#[derive(Debug, Clone, Copy)]
pub struct Palette {
    pub level: ColorLevel,
}

impl Palette {
    pub fn new(level: ColorLevel) -> Self {
        Self { level }
    }

    /// Foreground colour for a role, degraded to the terminal capability.
    pub fn fg(&self, role: Role) -> Color {
        match (self.level, role) {
            // 24-bit TrueColor hex values from report 5.3.
            (ColorLevel::TrueColor, Role::Working) => Color::Rgb(0x38, 0xBD, 0xF8),
            (ColorLevel::TrueColor, Role::Success) => Color::Rgb(0x34, 0xD3, 0x99),
            (ColorLevel::TrueColor, Role::Warning) => Color::Rgb(0xFB, 0xBF, 0x24),
            (ColorLevel::TrueColor, Role::Error) => Color::Rgb(0xF4, 0x3F, 0x5E),
            (ColorLevel::TrueColor, Role::Muted) => Color::Rgb(0x8B, 0x94, 0x9E),
            (ColorLevel::TrueColor, Role::Focus) => Color::Rgb(0xA7, 0x8B, 0xFA),
            // 256-colour indexes from report 5.3.
            (ColorLevel::Color256, Role::Working) => Color::Indexed(81),
            (ColorLevel::Color256, Role::Success) => Color::Indexed(78),
            (ColorLevel::Color256, Role::Warning) => Color::Indexed(214),
            (ColorLevel::Color256, Role::Error) => Color::Indexed(197),
            (ColorLevel::Color256, Role::Muted) => Color::Indexed(244),
            (ColorLevel::Color256, Role::Focus) => Color::Indexed(141),
            // 16-colour ANSI mapping from report 5.3.
            (ColorLevel::Ansi16, Role::Working) => Color::Cyan,
            (ColorLevel::Ansi16, Role::Success) => Color::Green,
            (ColorLevel::Ansi16, Role::Warning) => Color::Yellow,
            (ColorLevel::Ansi16, Role::Error) => Color::Red,
            (ColorLevel::Ansi16, Role::Muted) => Color::DarkGray,
            (ColorLevel::Ansi16, Role::Focus) => Color::Magenta,
        }
    }

    pub fn style(&self, role: Role) -> Style {
        Style::default().fg(self.fg(role))
    }

    pub fn bold(&self, role: Role) -> Style {
        self.style(role).add_modifier(Modifier::BOLD)
    }

    /// Map a Herdr agent status to a palette role.
    pub fn agent_status_role(status: &str) -> Role {
        match status {
            "working" => Role::Working,
            "idle" => Role::Muted,
            "blocked" => Role::Warning,
            "done" => Role::Success,
            _ => Role::Muted,
        }
    }

    /// Map a FirstMate task state to a palette role.
    pub fn task_state_role(state: &str) -> Role {
        match state {
            "in_flight" => Role::Working,
            "queued" => Role::Muted,
            "done" => Role::Success,
            "blocked" | "held" => Role::Warning,
            "failed" => Role::Error,
            _ => Role::Muted,
        }
    }

    /// Map an event severity to a palette role.
    pub fn severity_role(severity: &str) -> Role {
        match severity {
            "warning" => Role::Warning,
            "error" => Role::Error,
            "critical" => Role::Error,
            _ => Role::Muted,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detect_color_level_matrix() {
        assert_eq!(
            detect_color_level(Some("truecolor"), Some("xterm-256color")),
            ColorLevel::TrueColor
        );
        assert_eq!(
            detect_color_level(Some("24bit"), None),
            ColorLevel::TrueColor
        );
        // COLORTERM wins even when TERM looks plain.
        assert_eq!(
            detect_color_level(Some("truecolor"), Some("dumb")),
            ColorLevel::TrueColor
        );
        assert_eq!(
            detect_color_level(None, Some("xterm-256color")),
            ColorLevel::Color256
        );
        assert_eq!(detect_color_level(None, Some("xterm")), ColorLevel::Ansi16);
        assert_eq!(detect_color_level(None, None), ColorLevel::Ansi16);
        // A non-advertising COLORTERM must not force truecolor.
        assert_eq!(
            detect_color_level(Some("yes"), Some("xterm")),
            ColorLevel::Ansi16
        );
    }

    #[test]
    fn truecolor_matches_report_table() {
        let p = Palette::new(ColorLevel::TrueColor);
        assert_eq!(p.fg(Role::Working), Color::Rgb(0x38, 0xBD, 0xF8));
        assert_eq!(p.fg(Role::Success), Color::Rgb(0x34, 0xD3, 0x99));
        assert_eq!(p.fg(Role::Warning), Color::Rgb(0xFB, 0xBF, 0x24));
        assert_eq!(p.fg(Role::Error), Color::Rgb(0xF4, 0x3F, 0x5E));
        assert_eq!(p.fg(Role::Muted), Color::Rgb(0x8B, 0x94, 0x9E));
        assert_eq!(p.fg(Role::Focus), Color::Rgb(0xA7, 0x8B, 0xFA));
    }

    #[test]
    fn color256_matches_report_table() {
        let p = Palette::new(ColorLevel::Color256);
        assert_eq!(p.fg(Role::Working), Color::Indexed(81));
        assert_eq!(p.fg(Role::Success), Color::Indexed(78));
        assert_eq!(p.fg(Role::Warning), Color::Indexed(214));
        assert_eq!(p.fg(Role::Error), Color::Indexed(197));
        assert_eq!(p.fg(Role::Muted), Color::Indexed(244));
        assert_eq!(p.fg(Role::Focus), Color::Indexed(141));
    }

    #[test]
    fn ansi16_matches_report_table() {
        let p = Palette::new(ColorLevel::Ansi16);
        assert_eq!(p.fg(Role::Working), Color::Cyan);
        assert_eq!(p.fg(Role::Success), Color::Green);
        assert_eq!(p.fg(Role::Warning), Color::Yellow);
        assert_eq!(p.fg(Role::Error), Color::Red);
        assert_eq!(p.fg(Role::Muted), Color::DarkGray);
        assert_eq!(p.fg(Role::Focus), Color::Magenta);
    }

    #[test]
    fn role_mappings() {
        assert_eq!(Palette::agent_status_role("working"), Role::Working);
        assert_eq!(Palette::agent_status_role("blocked"), Role::Warning);
        assert_eq!(Palette::agent_status_role("done"), Role::Success);
        assert_eq!(Palette::task_state_role("in_flight"), Role::Working);
        assert_eq!(Palette::task_state_role("failed"), Role::Error);
        assert_eq!(Palette::severity_role("critical"), Role::Error);
        assert_eq!(Palette::severity_role("debug"), Role::Muted);
    }
}

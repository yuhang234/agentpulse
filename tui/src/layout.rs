//! Layout selection by terminal size (design report 5.2).

/// The three layout modes from report 5.2 plus the small-terminal fallback.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LayoutKind {
    /// 160x48 extended wide screen: three columns (tasks / pane / feed).
    Wide,
    /// 80x24 compact standard screen: 2x2 panel grid.
    Compact,
    /// Readable single column for anything smaller than 80x24.
    Fallback,
}

/// Pick the layout for a terminal size. Wide needs at least 160x48, compact
/// at least 80x24; anything smaller falls back to a single column.
pub fn select_layout(width: u16, height: u16) -> LayoutKind {
    if width >= 160 && height >= 48 {
        LayoutKind::Wide
    } else if width >= 80 && height >= 24 {
        LayoutKind::Compact
    } else {
        LayoutKind::Fallback
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn layout_thresholds() {
        assert_eq!(select_layout(160, 48), LayoutKind::Wide);
        assert_eq!(select_layout(200, 60), LayoutKind::Wide);
        assert_eq!(select_layout(159, 48), LayoutKind::Compact);
        assert_eq!(select_layout(160, 47), LayoutKind::Compact);
        assert_eq!(select_layout(80, 24), LayoutKind::Compact);
        assert_eq!(select_layout(120, 40), LayoutKind::Compact);
        assert_eq!(select_layout(79, 24), LayoutKind::Fallback);
        assert_eq!(select_layout(80, 23), LayoutKind::Fallback);
        assert_eq!(select_layout(40, 10), LayoutKind::Fallback);
        assert_eq!(select_layout(1, 1), LayoutKind::Fallback);
    }
}

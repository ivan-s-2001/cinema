import SwiftUI

enum NuvioPlayerDesignTokens {
    enum ColorToken {
        static let appBackground = Color(red: 0.051, green: 0.051, blue: 0.051)
        static let panelScrim = Color.black.opacity(0.52)
        static let panelSurface = Color(red: 0.12, green: 0.12, blue: 0.12)
        static let textPrimary = Color.white
        static let textSecondary = Color.white.opacity(0.7)
        static let textMuted = Color.white.opacity(0.6)
        static let borderSubtle = Color.white.opacity(0.1)
        static let borderSelected = Color.white.opacity(0.2)
        static let overlaySubtle = Color.white.opacity(0.06)
        static let overlaySelected = Color.white.opacity(0.15)
        static let playerScrim = Color.black.opacity(0.72)
        static let dangerSurface = Color(red: 0.365, green: 0.122, blue: 0.122).opacity(0.88)
        static let dangerIconSurface = Color(red: 1.0, green: 0.541, blue: 0.502).opacity(0.22)
        static let dangerIcon = Color(red: 1.0, green: 0.757, blue: 0.757)
    }

    enum Space {
        static let s4: CGFloat = 4
        static let s6: CGFloat = 6
        static let s8: CGFloat = 8
        static let s10: CGFloat = 10
        static let s12: CGFloat = 12
        static let s14: CGFloat = 14
        static let s16: CGFloat = 16
        static let s20: CGFloat = 20
        static let s24: CGFloat = 24
    }

    enum Radius {
        static let sm: CGFloat = 12
        static let md: CGFloat = 14
        static let lg: CGFloat = 16
        static let xl: CGFloat = 20
        static let xxl: CGFloat = 24
    }

    enum Size {
        static let iconXs: CGFloat = 12
        static let iconSm: CGFloat = 14
        static let iconMd: CGFloat = 22
        static let feedbackIcon: CGFloat = 28
        static let panelMaxWidth: CGFloat = 520
        static let sourcesPanelMaxHeight: CGFloat = 600
        static let episodesPanelMaxHeight: CGFloat = 620
    }

    enum Motion {
        static let fast = 0.7
        static let slow = 1.0
    }
}

import SwiftUI

func panelChipButton(label: String, icon: String?, action: @escaping () -> Void) -> some View {
    Button(action: action) {
        HStack(spacing: NuvioPlayerDesignTokens.Space.s4) {
            if let icon = icon {
                Image(systemName: icon)
                    .font(.system(size: NuvioPlayerDesignTokens.Size.iconXs))
                    .foregroundColor(NuvioPlayerDesignTokens.ColorToken.textSecondary)
            }
            Text(label)
                .font(.system(size: NuvioPlayerDesignTokens.Size.iconXs))
                .foregroundColor(NuvioPlayerDesignTokens.ColorToken.textSecondary)
        }
        .padding(.horizontal, NuvioPlayerDesignTokens.Space.s12)
        .padding(.vertical, NuvioPlayerDesignTokens.Space.s6)
        .background(NuvioPlayerDesignTokens.ColorToken.borderSubtle)
        .overlay(
            RoundedRectangle(cornerRadius: NuvioPlayerDesignTokens.Radius.lg)
                .stroke(NuvioPlayerDesignTokens.ColorToken.borderSubtle, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: NuvioPlayerDesignTokens.Radius.lg))
    }
    .buttonStyle(.plain)
}

func addonFilterChip(label: String, isSelected: Bool, isLoading: Bool, hasError: Bool, action: @escaping () -> Void) -> some View {
    Button(action: action) {
        HStack(spacing: NuvioPlayerDesignTokens.Space.s6) {
            if isLoading {
                ProgressView()
                    .progressViewStyle(.circular)
                    .scaleEffect(0.5)
                    .frame(width: NuvioPlayerDesignTokens.Size.iconXs, height: NuvioPlayerDesignTokens.Size.iconXs)
            }
            Text(label)
                .font(.system(size: NuvioPlayerDesignTokens.Size.iconXs, weight: isSelected ? .semibold : .regular))
                .foregroundColor(
                    hasError ? Color.red :
                    isSelected ? NuvioPlayerDesignTokens.ColorToken.textPrimary :
                    NuvioPlayerDesignTokens.ColorToken.textSecondary
                )
        }
        .padding(.horizontal, NuvioPlayerDesignTokens.Space.s14)
        .padding(.vertical, NuvioPlayerDesignTokens.Space.s8)
        .background(isSelected ? NuvioPlayerDesignTokens.ColorToken.overlaySelected : NuvioPlayerDesignTokens.ColorToken.overlaySubtle)
        .overlay(
            RoundedRectangle(cornerRadius: NuvioPlayerDesignTokens.Radius.xl)
                .stroke(
                    isSelected ? NuvioPlayerDesignTokens.ColorToken.textSecondary : NuvioPlayerDesignTokens.ColorToken.borderSubtle,
                    lineWidth: 1
                )
        )
        .clipShape(RoundedRectangle(cornerRadius: NuvioPlayerDesignTokens.Radius.xl))
    }
    .buttonStyle(.plain)
}

struct GestureFeedbackPill: View {
    let feedback: GestureFeedbackState

    var body: some View {
        let bgColor = feedback.isDanger ? NuvioPlayerDesignTokens.ColorToken.dangerSurface : NuvioPlayerDesignTokens.ColorToken.playerScrim
        let iconBgColor = feedback.isDanger ? NuvioPlayerDesignTokens.ColorToken.dangerIconSurface : NuvioPlayerDesignTokens.ColorToken.overlaySelected
        let iconTint = feedback.isDanger ? NuvioPlayerDesignTokens.ColorToken.dangerIcon : NuvioPlayerDesignTokens.ColorToken.textPrimary

        let iconName: String = {
            switch feedback.icon {
            case .speed: return "speedometer"
            case .volume: return "speaker.wave.2.fill"
            case .volumeMuted: return "speaker.slash.fill"
            case .seekForward: return "forward.fill"
            case .seekBackward: return "backward.fill"
            }
        }()

        HStack(spacing: NuvioPlayerDesignTokens.Space.s10) {
            ZStack {
                RoundedRectangle(cornerRadius: NuvioPlayerDesignTokens.Radius.md)
                    .fill(iconBgColor)
                    .frame(width: NuvioPlayerDesignTokens.Size.feedbackIcon, height: NuvioPlayerDesignTokens.Size.feedbackIcon)
                Image(systemName: iconName)
                    .font(.system(size: NuvioPlayerDesignTokens.Size.iconSm))
                    .foregroundColor(iconTint)
            }
            Text(feedback.message)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(NuvioPlayerDesignTokens.ColorToken.textPrimary)
        }
        .padding(.horizontal, NuvioPlayerDesignTokens.Space.s16)
        .padding(.vertical, NuvioPlayerDesignTokens.Space.s10)
        .background(bgColor)
        .clipShape(RoundedRectangle(cornerRadius: NuvioPlayerDesignTokens.Radius.xxl))
    }
}

struct OpeningOverlayContent: View {
    let logo: String?
    let title: String

    @State private var contentAlpha: Double = 0
    @State private var pulseScale: CGFloat = 1.0

    var body: some View {
        ZStack {
            if let logoUrl = logo, !logoUrl.isEmpty {
                if #available(macOS 12.0, *) {
                    AsyncImage(url: URL(string: logoUrl)) { image in
                        image.resizable().aspectRatio(contentMode: .fit)
                    } placeholder: {
                        Color.clear
                    }
                    .frame(width: 300, height: 180)
                    .scaleEffect(pulseScale)
                    .opacity(contentAlpha)
                } else {
                    Text(title)
                        .font(.system(size: 42, weight: .heavy))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .scaleEffect(pulseScale)
                        .opacity(contentAlpha)
                }
            } else if !title.isEmpty {
                Text(title)
                    .font(.system(size: 42, weight: .heavy))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .padding(.horizontal, 24)
                    .scaleEffect(pulseScale)
                    .opacity(contentAlpha)
            } else {
                ProgressView()
                    .progressViewStyle(.circular)
                    .scaleEffect(1.8)
                    .colorMultiply(Color(red: 0.898, green: 0.035, blue: 0.078))
            }
        }
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                withAnimation(.easeInOut(duration: NuvioPlayerDesignTokens.Motion.fast)) {
                    contentAlpha = 1.0
                }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                withAnimation(.easeInOut(duration: NuvioPlayerDesignTokens.Motion.slow).repeatForever(autoreverses: true)) {
                    pulseScale = 1.04
                }
            }
        }
    }
}

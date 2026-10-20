import UIKit

// MARK: - FallbackView

/// 폴백 화면(로드 전·실패 시 보이는 화면과 닫기 버튼)의 테마. 기본은 `light`이고 `system`은 시스템 다크 모드를 따른다.
/// Unity 래퍼와는 rawValue(0 light, 1 dark, 2 system)로 주고받는다.
public enum FallbackTheme: Int {
    case light
    case dark
    case system
}

/// 웹뷰가 로드되기 전과 실패했을 때 보이는 화면. 닫기 버튼은 항상 보인다.
/// 색은 시스템 색이고 `theme`으로 라이트·다크를 정한다.
final class FallbackView: UIView {
    enum State {
        case loading
        case loaded
        case failed
    }

    var state: State = .loading {
        didSet { updateState() }
    }
    var theme: FallbackTheme = .light {
        didSet { applyTheme() }
    }
    var closeHandler: (() -> Void)?

    let closeButton = UIButton(type: .system)
    private let activityIndicator = UIActivityIndicatorView(style: .large)
    private let failureImageView = UIImageView(image: UIImage(systemName: "exclamationmark.triangle"))

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .systemBackground

        activityIndicator.translatesAutoresizingMaskIntoConstraints = false
        activityIndicator.hidesWhenStopped = true

        failureImageView.translatesAutoresizingMaskIntoConstraints = false
        failureImageView.tintColor = .secondaryLabel
        failureImageView.contentMode = .scaleAspectFit
        failureImageView.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 40)

        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.setImage(UIImage(systemName: "xmark"), for: .normal)
        closeButton.tintColor = .label
        closeButton.accessibilityLabel = "Close"
        closeButton.addTarget(self, action: #selector(closeButtonTapped), for: .touchUpInside)

        addSubview(activityIndicator)
        addSubview(failureImageView)
        addSubview(closeButton)
        NSLayoutConstraint.activate([
            activityIndicator.centerXAnchor.constraint(equalTo: centerXAnchor),
            activityIndicator.centerYAnchor.constraint(equalTo: centerYAnchor),
            failureImageView.centerXAnchor.constraint(equalTo: centerXAnchor),
            failureImageView.centerYAnchor.constraint(equalTo: centerYAnchor),
            closeButton.topAnchor.constraint(equalTo: safeAreaLayoutGuide.topAnchor, constant: 8),
            closeButton.trailingAnchor.constraint(equalTo: safeAreaLayoutGuide.trailingAnchor, constant: -8),
            closeButton.widthAnchor.constraint(equalToConstant: 44),
            closeButton.heightAnchor.constraint(equalToConstant: 44),
        ])
        applyTheme()
        updateState()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func applyTheme() {
        switch theme {
        case .light: overrideUserInterfaceStyle = .light
        case .dark: overrideUserInterfaceStyle = .dark
        case .system: overrideUserInterfaceStyle = .unspecified
        }
    }

    private func updateState() {
        switch state {
        case .loading:
            activityIndicator.startAnimating()
            failureImageView.isHidden = true
        case .loaded:
            activityIndicator.stopAnimating()
            failureImageView.isHidden = true
        case .failed:
            activityIndicator.stopAnimating()
            failureImageView.isHidden = false
        }
    }

    @objc private func closeButtonTapped() {
        closeHandler?()
    }
}

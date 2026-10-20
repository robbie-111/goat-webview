import UIKit

// MARK: - FallbackView

/// 웹뷰가 로드되기 전과 실패했을 때 보이는 화면. 닫기 버튼은 항상 보인다.
final class FallbackView: UIView {
    enum State {
        case loading
        case loaded
        case failed
    }

    var state: State = .loading {
        didSet { updateState() }
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
        updateState()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
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

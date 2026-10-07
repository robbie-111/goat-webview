import UIKit

// MARK: - ContainerView

/// 폴백 화면과 웹뷰를 겹쳐 담는 컨테이너. `setMargins`/`setVisibility`는 이 뷰에 적용한다.
/// 위쪽 safe area(상태바·노치) 중 컨테이너와 겹치는 부분은 항상 비우고, 상단 띠를 켜면 그 영역을 칠한다.
/// 좌우 safe area(가로 모드 노치)는 피하고, 아래(홈 인디케이터) 뒤는 폴백 화면과 웹뷰가 채운다.
/// safe area와 겹치지 않는 팝업형 컨테이너는 inset이 0이므로 줄이지 않는다(Android webview-sdk와 같음).
final class ContainerView: UIView {
    let topSafeAreaView = UIView()

    var topSafeAreaColor: UIColor? {
        didSet {
            topSafeAreaView.backgroundColor = topSafeAreaColor
            topSafeAreaView.isHidden = topSafeAreaColor == nil
            setNeedsLayout()
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        topSafeAreaView.isHidden = true
        addSubview(topSafeAreaView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func safeAreaInsetsDidChange() {
        super.safeAreaInsetsDidChange()
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let insets = safeAreaInsets
        let top = min(insets.top, bounds.height)
        topSafeAreaView.frame = CGRect(x: 0, y: 0, width: bounds.width, height: top)
        let contentFrame = CGRect(x: insets.left, y: top,
                                  width: max(bounds.width - insets.left - insets.right, 0),
                                  height: bounds.height - top)
        for view in subviews where view !== topSafeAreaView {
            view.frame = contentFrame
        }
    }
}

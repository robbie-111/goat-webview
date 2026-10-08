import UIKit

// MARK: - ContainerView

/// 폴백 화면과 웹뷰를 겹쳐 담는 컨테이너. `setMargins`/`setVisibility`는 이 뷰에 적용한다.
/// 위쪽 safe area(상태바·노치) 중 컨테이너와 겹치는 부분은 항상 비우고, 상단 띠를 켜면 그 영역을 칠한다.
/// 좌우 safe area(가로 모드 노치)는 피하고, 아래(홈 인디케이터) 뒤는 폴백 화면과 웹뷰가 채운다.
/// safe area와 겹치지 않는 팝업형 컨테이너는 inset이 0이므로 줄이지 않는다(Android webview-sdk와 같음).
/// 폴백 화면과 웹뷰는 `contentView`(웹 콘텐츠 영역)에 담는다. `cornerRadii`를 주면 `contentView`만 둥글게 자르고
/// 상단 띠와 컨테이너는 자르지 않으므로, 풀스크린에서도 상태바 아래 웹 모서리가 둥글게 보인다.
final class ContainerView: UIView {
    /// 네 모서리 반지름(pt). 모두 0이면 자르지 않는다.
    struct CornerRadii: Equatable {
        var topLeft: CGFloat = 0
        var topRight: CGFloat = 0
        var bottomLeft: CGFloat = 0
        var bottomRight: CGFloat = 0

        var isUniform: Bool {
            topLeft == topRight && topLeft == bottomLeft && topLeft == bottomRight
        }
    }

    let topSafeAreaView = UIView()
    /// 폴백 화면과 웹뷰를 담는 웹 콘텐츠 영역. safe area를 피해 배치하며 모서리 라운딩은 이 뷰를 자른다.
    let contentView = UIView()
    private let cornerMaskLayer = CAShapeLayer()

    var cornerRadii = CornerRadii() {
        didSet { setNeedsLayout() }
    }

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
        addSubview(contentView)
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
        contentView.frame = contentFrame
        updateCornerMask()
    }

    // 웹 콘텐츠 영역을 자른다. 네 값이 같으면 레이어 속성만으로, 다르면 모서리별 원호로 만든 마스크로 자른다.
    private func updateCornerMask() {
        let radii = cornerRadii
        let contentLayer = contentView.layer
        if radii.isUniform {
            contentLayer.mask = nil
            contentLayer.cornerRadius = radii.topLeft
            contentLayer.masksToBounds = radii.topLeft > 0
            return
        }
        contentLayer.cornerRadius = 0
        contentLayer.masksToBounds = false
        // 뷰에 붙지 않은 마스크 레이어는 속성을 바꾸면 암시적으로 애니메이션되므로 크기 변경을 바로 따라가게 끈다.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        cornerMaskLayer.frame = contentView.bounds
        cornerMaskLayer.path = ContainerView.roundedPath(in: contentView.bounds, radii: radii).cgPath
        CATransaction.commit()
        contentLayer.mask = cornerMaskLayer
    }

    static func roundedPath(in rect: CGRect, radii: CornerRadii) -> UIBezierPath {
        // 이웃한 두 반지름의 합이 변 길이를 넘지 않도록 줄인다.
        let limit = min(rect.width, rect.height) / 2
        let tl = min(radii.topLeft, limit), tr = min(radii.topRight, limit)
        let bl = min(radii.bottomLeft, limit), br = min(radii.bottomRight, limit)
        let path = UIBezierPath()
        path.move(to: CGPoint(x: rect.minX + tl, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - tr, y: rect.minY))
        path.addArc(withCenter: CGPoint(x: rect.maxX - tr, y: rect.minY + tr), radius: tr,
                    startAngle: -.pi / 2, endAngle: 0, clockwise: true)
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - br))
        path.addArc(withCenter: CGPoint(x: rect.maxX - br, y: rect.maxY - br), radius: br,
                    startAngle: 0, endAngle: .pi / 2, clockwise: true)
        path.addLine(to: CGPoint(x: rect.minX + bl, y: rect.maxY))
        path.addArc(withCenter: CGPoint(x: rect.minX + bl, y: rect.maxY - bl), radius: bl,
                    startAngle: .pi / 2, endAngle: .pi, clockwise: true)
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + tl))
        path.addArc(withCenter: CGPoint(x: rect.minX + tl, y: rect.minY + tl), radius: tl,
                    startAngle: .pi, endAngle: .pi * 3 / 2, clockwise: true)
        path.close()
        return path
    }
}

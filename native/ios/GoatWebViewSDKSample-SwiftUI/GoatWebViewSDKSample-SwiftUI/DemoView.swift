import GoatWebViewSDK
import SwiftUI

private let startURL = "https://example.com"

/// setTopSafeAreaColor에 넘기는 r, g, b, a(각 0...1). Off는 호출하지 않는다(SDK 기본: 상단을 투명하게 비움).
enum SafeAreaPreset: String, CaseIterable, Identifiable {
    case off = "Off"
    case black = "Black"
    case white = "White"
    case red = "Red"

    var id: Self { self }

    var color: (r: Float, g: Float, b: Float, a: Float)? {
        switch self {
        case .off: return nil
        case .black: return (0, 0, 0, 1)
        case .white: return (1, 1, 1, 1)
        case .red: return (1, 0, 0, 1)
        }
    }
}

extension WebViewPlugin {
    func applySafeArea(_ preset: SafeAreaPreset) {
        if let color = preset.color {
            setTopSafeAreaColor(r: color.r, g: color.g, b: color.b, a: color.a)
        }
    }
}

/// GoatWebViewSDK 기능을 시험하는 화면. Load는 웹뷰를 숨긴 채 만들어 URL을 로드하고, Show는 그 웹뷰를 표시한다.
/// Fullscreen은 설정 화면을 치우고 전체 화면에, Popup은 설정 화면 위에 마진을 두고 띄운다(바깥 설정 화면은 계속 조작 가능).
/// iOS에는 시스템 뒤로가기가 없으므로 표시 중에는 Back 버튼을 둔다. 웹 기록이 있으면 웹에서 뒤로 가고, 없으면 웹뷰를 dispose한다.
struct DemoView: View {
    @State private var input = startURL
    @State private var safeArea = SafeAreaPreset.black
    @State private var scrollBounce = true
    // 팝업은 safe area와 겹치지 않을 만큼 마진을 준다. 바깥에는 배경을 두지 않아 창 배경이 그대로 보인다.
    @State private var popup = false
    // nil이면 웹뷰가 없다.
    @State private var loadedURL: String?
    @State private var showing = false
    @State private var plugin: WebViewPlugin?
    @State private var status = "웹뷰 없음"
    @FocusState private var urlFocused: Bool

    var body: some View {
        ZStack {
            // 설정 화면이 아래, 웹뷰가 위. Popup이면 Show 중에도 설정 화면을 남겨 팝업 바깥에 보이게 한다.
            if !showing || popup {
                settings
            }
            // 웹뷰는 safe area를 직접 처리한다. Fullscreen이면 화면 전체, Popup이면 safe area와 겹치지 않는 마진을 둔다.
            if let loadedURL {
                WebViewHost(
                    url: loadedURL,
                    visible: showing,
                    safeArea: safeArea,
                    scrollBounce: scrollBounce,
                    onCreated: { plugin = $0 },
                    onStatus: { status = $0 },
                    onClosed: close
                )
                .padding(popup ? EdgeInsets(top: 96, leading: 24, bottom: 96, trailing: 24) : EdgeInsets())
                .ignoresSafeArea()
            }
            if showing {
                backButton
            }
        }
        // Android 샘플의 창 배경(#F4F6FA). Fullscreen에서 비워 둔 safe area에 보인다.
        .background(Color(red: 0xF4 / 255, green: 0xF6 / 255, blue: 0xFA / 255).ignoresSafeArea())
    }

    private var settings: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Goat WebView")
                    .font(.title2.bold())
                TextField("URL", text: $input)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .disableAutocorrection(true)
                    .focused($urlFocused)
                    .onSubmit(load)
                Text("Top safe area color")
                Picker("Top safe area color", selection: $safeArea) {
                    ForEach(SafeAreaPreset.allCases) { preset in
                        Text(preset.rawValue).tag(preset)
                    }
                }
                .pickerStyle(.segmented)
                .onChange(of: safeArea) { plugin?.applySafeArea($0) }
                Toggle("Scroll bounce", isOn: $scrollBounce)
                    .onChange(of: scrollBounce) { plugin?.setScrollBounceEnabled($0) }
                Text("Display")
                Picker("Display", selection: $popup) {
                    Text("Fullscreen").tag(false)
                    Text("Popup").tag(true)
                }
                .pickerStyle(.segmented)
                Button(action: clearCache) {
                    Text("Clear Cache").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                HStack(spacing: 8) {
                    Button(action: load) {
                        Text("Load").frame(maxWidth: .infinity)
                    }
                    Button(action: show) {
                        Text("Show").frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                Text(status)
                    .font(.footnote)
            }
            .padding()
        }
        .background(Color(.systemBackground))
    }

    private var backButton: some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                Button(action: back) {
                    Label("Back", systemImage: "chevron.backward")
                }
                .buttonStyle(.borderedProminent)
                .padding()
            }
        }
    }

    private func load() {
        urlFocused = false
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let target = trimmed.contains("://") ? trimmed : "https://\(trimmed)"
        let lowercased = target.lowercased()
        guard lowercased.hasPrefix("https://") || lowercased.hasPrefix("http://") else {
            status = "HTTP(S) URL만 사용할 수 있습니다."
            return
        }
        plugin?.loadURL(target)
        loadedURL = target
    }

    private func show() {
        if loadedURL == nil { load() }
        guard loadedURL != nil else { return }
        plugin?.setVisibility(true)
        showing = true
    }

    private func back() {
        if let plugin, plugin.canGoBack {
            plugin.goBack()
        } else {
            close()
        }
    }

    // loadedURL이 nil이 되면 WebViewHost가 사라지고 dismantleUIView에서 dispose된다.
    private func close() {
        showing = false
        loadedURL = nil
        plugin = nil
        status = "웹뷰를 닫았습니다."
    }

    private func clearCache() {
        WebViewCache.clear {
            status = "캐시를 비웠습니다."
        }
    }
}

struct DemoView_Previews: PreviewProvider {
    static var previews: some View {
        DemoView()
    }
}

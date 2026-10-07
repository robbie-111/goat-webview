/*
 * Copyright (C) 2011 Keijiro Takahashi
 * Copyright (C) 2012 GREE, Inc.
 *
 * This software is provided 'as-is', without any express or implied
 * warranty.  In no event will the authors be held liable for any damages
 * arising from the use of this software.
 *
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 *
 * 1. The origin of this software must not be misrepresented; you must not
 *    claim that you wrote the original software. If you use this software
 *    in a product, an acknowledgment in the product documentation would be
 *    appreciated but is not required.
 * 2. Altered source versions must be plainly marked as such, and must not be
 *    misrepresented as being the original software.
 * 3. This notice may not be removed or altered from any source distribution.
 */

// 수정됨: gree/unity-webview plugins/iOS/WebView.mm의 window.Unity 주입 스크립트를 옮기고,
// 허용 출처에서만 설치하도록 고쳤다. URL 검사·출처 정규화·JSON 변환 도우미와 웹 콘솔 전달 스크립트를 더했다.

import WebKit

// MARK: - Config

// Android의 WebViewConfig처럼 출처·URL 검사와 주입 스크립트를 모은다.
extension WebViewPlugin {
    // upstream(175–184)의 window.Unity 주입 스크립트. saveDataURL은 뺐고, 허용 출처에서만 설치한다.
    // Android 샘플처럼 메시지를 문자열로 바꿔 보낸다(upstream은 네이티브에서 %@로 바꿨다).
    static func bridgeScript(allowedOrigins: Set<String>?) -> String {
        let origins = allowedOrigins.flatMap { json(Array($0).sorted()) } ?? "null"
        return """
        (function() {
            var origins = \(origins);
            if (origins !== null && origins.indexOf(window.location.origin) < 0)
                return;
            window.Unity = {
                call: function(msg) {
                    window.webkit.messageHandlers.\(messageHandlerName).postMessage(String(msg));
                }
            };
        })();
        """
    }

    // console.log·info·warn·error·debug를 감싸 원래 동작은 유지하고 {level, message}를 네이티브로 보낸다.
    // WKWebView에는 콘솔 콜백이 없어서 Android onConsoleMessage 대신 쓴다.
    static let consoleScript = """
    (function() {
        var handler = window.webkit && window.webkit.messageHandlers.\(consoleHandlerName);
        if (!handler || window.__goatConsoleInstalled) return;
        window.__goatConsoleInstalled = true;
        function text(value) {
            if (typeof value === 'string') return value;
            try { return JSON.stringify(value); } catch (e) { return String(value); }
        }
        ['log', 'info', 'warn', 'error', 'debug'].forEach(function(level) {
            var original = console[level];
            console[level] = function() {
                try {
                    handler.postMessage({ level: level, message: Array.prototype.map.call(arguments, text).join(' ') });
                } catch (e) {}
                if (original) original.apply(console, arguments);
            };
        });
    })();
    """

    static func webURL(_ string: String) -> URL? {
        guard let url = URL(string: percentEncodingInvalidCharacters(string)),
              let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = url.host, !host.isEmpty else { return nil }
        return url
    }

    // 경로·쿼리·프래그먼트의 공백과 비ASCII 문자만 인코딩하고 '#', 이미 인코딩된 %xx, 호스트는 그대로 둔다.
    // iOS 17 미만의 URL(string:)은 이런 문자가 있으면 nil을 돌려주고, iOS 17 이상은 기존 %xx까지 다시 인코딩한다.
    // 국제화 도메인 호스트는 iOS 17 이상의 URL(string:)이 퓨니코드로 바꾼다(그 미만에서는 nil이다).
    private static let urlTailAllowed = CharacterSet.urlFragmentAllowed.union(CharacterSet(charactersIn: "#%"))

    private static func percentEncodingInvalidCharacters(_ string: String) -> String {
        guard let schemeEnd = string.range(of: "://") else { return string }
        let authorityEnd = string[schemeEnd.upperBound...].firstIndex { "/?#".contains($0) } ?? string.endIndex
        let tail = string[authorityEnd...]
        return String(string[..<authorityEnd]) + (tail.addingPercentEncoding(withAllowedCharacters: urlTailAllowed) ?? String(tail))
    }

    /// `https://Example.com:443` → `https://example.com`. http(s)가 아니거나 경로·쿼리가 있으면 nil이다.
    /// 국제화 도메인은 WKSecurityOrigin, `location.origin`과 같은 퓨니코드(`https://xn--...`)로 바꾼다.
    static func normalizedOrigin(_ string: String) -> String? {
        guard let components = URLComponents(string: string),
              let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = encodedHost(of: components)?.lowercased(), !host.isEmpty,
              components.user == nil, components.password == nil,
              components.path.isEmpty || components.path == "/",
              components.query == nil, components.fragment == nil else { return nil }
        return origin(scheme: scheme, host: host, port: components.port ?? 0)
    }

    // host는 퓨니코드를 유니코드로 풀어서 돌려주므로 인코딩된 호스트를 쓴다.
    // iOS 16 미만에는 encodedHost가 없어 퍼센트 인코딩된(ASCII가 아닌) 호스트는 받지 않는다.
    private static func encodedHost(of components: URLComponents) -> String? {
        if #available(iOS 16.0, *) {
            return components.encodedHost
        }
        guard let host = components.percentEncodedHost, !host.contains("%") else { return nil }
        return host
    }

    static func origin(of securityOrigin: WKSecurityOrigin) -> String {
        origin(scheme: securityOrigin.protocol.lowercased(), host: securityOrigin.host.lowercased(), port: securityOrigin.port)
    }

    private static func origin(scheme: String, host: String, port: Int) -> String {
        let isDefaultPort = port == 0 || (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        return isDefaultPort ? "\(scheme)://\(host)" : "\(scheme)://\(host):\(port)"
    }

    static func json(_ value: Any?) -> String? {
        // NaN·Infinity·Date처럼 JSON으로 쓸 수 없는 값은 JSONSerialization이 try?로 잡히지 않는
        // Objective-C 예외를 던지므로 먼저 거른다. isValidJSONObject는 최상위가 배열·딕셔너리여야 해서 배열로 감싼다.
        guard let value, JSONSerialization.isValidJSONObject([value]),
              let data = try? JSONSerialization.data(withJSONObject: value,
                                                     options: [.fragmentsAllowed, .withoutEscapingSlashes]) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

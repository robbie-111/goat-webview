package com.goatwebview.unity

import com.unity3d.player.UnityPlayer
import org.json.JSONObject

/**
 * 웹뷰 컴포넌트가 붙은 GameObject의 이벤트 메서드(OnPageStarted 등)를 UnitySendMessage로 호출한다.
 * LINE SDK Unity 래퍼의 CallbackPayload에 대응하며, 인자는 이벤트별 필드를 담은 JSON 문자열이다.
 * UnitySendMessage는 어느 스레드에서 호출해도 Unity 메인 스레드에서 처리된다.
 */
internal object CallbackPayload {
    // 테스트에서는 Unity 없이 전송 내용을 받도록 바꿔 끼운다.
    var sender: (String, String, String) -> Unit = { gameObject, method, message ->
        UnityPlayer.UnitySendMessage(gameObject, method, message)
    }

    /** [gameObjectName]의 [methodName](string) 메서드를 [fields]로 만든 JSON 문자열 인자로 호출한다. */
    fun sendMessage(gameObjectName: String, methodName: String, vararg fields: Pair<String, Any?>) {
        val json = JSONObject()
        for ((key, value) in fields) json.put(key, value ?: JSONObject.NULL)
        sender(gameObjectName, methodName, json.toString())
    }
}

package com.example.durakoverlay

import android.util.Base64
import android.webkit.JavascriptInterface

class WebViewBridge(private val parser: GameParser) {

    @JavascriptInterface
    fun onMessage(dir: String, data: String) {
        parser.process(dir, data)
    }

    @JavascriptInterface
    fun onBinary(dir: String, b64: String) {
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            parser.process(dir, String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
        }
    }

    companion object {
        const val NAME = "DurakBridge"

        val INJECT_JS = """
(function(){
  if (window.__durakHook) return;
  var B = window.DurakBridge;
  if (!B) return;
  window.__durakHook = true;

  function b64(buf){
    var s = '', b = new Uint8Array(buf);
    for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
    return btoa(s);
  }
  function emit(dir, d){
    try {
      if (typeof d === 'string') { B.onMessage(dir, d); }
      else if (d instanceof ArrayBuffer) { B.onBinary(dir, b64(d)); }
      else if (ArrayBuffer.isView(d)) { B.onBinary(dir, b64(d.buffer.slice(d.byteOffset, d.byteOffset + d.byteLength))); }
      else if (typeof Blob !== 'undefined' && d instanceof Blob) {
        d.arrayBuffer().then(function(ab){ B.onBinary(dir, b64(ab)); });
      }
    } catch (e) {}
  }

  var NativeWS = window.WebSocket;
  var nativeSend = NativeWS.prototype.send;
  NativeWS.prototype.send = function(d){
    emit('out', d);
    return nativeSend.apply(this, arguments);
  };

  function HookedWS(url, protocols){
    var ws = (arguments.length > 1) ? new NativeWS(url, protocols) : new NativeWS(url);
    ws.addEventListener('message', function(e){ emit('in', e.data); });
    return ws;
  }
  HookedWS.prototype = NativeWS.prototype;
  ['CONNECTING','OPEN','CLOSING','CLOSED'].forEach(function(k){ HookedWS[k] = NativeWS[k]; });
  window.WebSocket = HookedWS;
})();
""".trimIndent()
    }
}

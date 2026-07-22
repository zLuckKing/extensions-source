private fun extractConstantsViaWebView(indexJsCode: String): Constants? {
    val handler = Handler(Looper.getMainLooper())
    val latch = CountDownLatch(1)
    var result: Constants? = null
    var webView: WebView? = null

    handler.post {
        try {
            val view = WebView(applicationContext)
            webView = view
            with(view.settings) {
                javaScriptEnabled = true
                domStorageEnabled = false
            }

            // Safe escaping using JSONObject.quote() – prevents template literal injection errors
            val escapedJs = JSONObject.quote(indexJsCode)

            val script = """
                (function() {
                    try {
                        // The bundle is now passed as a safely escaped string
                        eval($escapedJs);
                        
                        // Find the security function dynamically
                        const targetFn = Object.values(window).find(fn =>
                            typeof fn === 'function' &&
                            fn.toString().includes('getUTCFullYear') &&
                            fn.toString().includes('return')
                        );
                        
                        if (!targetFn) {
                            return JSON.stringify({ error: 'No function with getUTCFullYear and return' });
                        }
                        
                        // Execute the function to get the full password
                        let password;
                        try {
                            password = targetFn();
                        } catch (execErr) {
                            return JSON.stringify({ error: 'Function execution failed: ' + execErr.message });
                        }
                        
                        if (!password || password.length < 10) {
                            return JSON.stringify({ error: 'Invalid password returned: ' + password });
                        }
                        
                        // Split encKey and hash (last 8 chars)
                        const encKey = password.substring(0, password.length - 8);
                        
                        // Extract hostPart from the function's source code
                        const code = targetFn.toString();
                        const hostMatch = code.match(/\+ "([^"]{10,})"\)/);
                        const hostPart = hostMatch ? hostMatch[1] : '';
                        
                        if (encKey.length < 5 || hostPart.length < 5) {
                            return JSON.stringify({ error: 'Extracted values too short', encKey, hostPart });
                        }
                        
                        return JSON.stringify({ hostPart, encKey });
                    } catch(e) {
                        return JSON.stringify({ error: e.message });
                    }
                })();
            """.trimIndent()

            view.evaluateJavascript(script) { jsonStr ->
                try {
                    val json = JSONObject(jsonStr)
                    if (!json.has("error")) {
                        val hostPart = json.getString("hostPart")
                        val encKey = json.getString("encKey")
                        result = Constants(hostPart, encKey)
                        Log.d("MangaLivreDecryptor", "WebView extraiu: host=$hostPart, encKey=$encKey")
                    } else {
                        Log.e("MangaLivreDecryptor", "WebView JS error: ${json.getString("error")}")
                    }
                } catch (e: Exception) {
                    Log.e("MangaLivreDecryptor", "Failed to parse WebView result", e)
                }
                latch.countDown()
            }
        } catch (e: Exception) {
            Log.e("MangaLivreDecryptor", "WebView setup failed", e)
            latch.countDown()
        }
    }

    latch.await(15, TimeUnit.SECONDS)
    handler.post { webView?.destroy() }
    return result
}

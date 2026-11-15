package xyz.amasuda.ws

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException

/** A `GM_registerMenuCommand` entry, offered in the menu ⋮ while its page is open. */
data class MenuCommand(val token: String, val scriptId: String, val id: String, val caption: String, val scriptName: String)

/** What the user script bridge asks of the app. Every call arrives on the main thread. */
interface UserScriptHost {
    fun openTab(url: String, background: Boolean)
    fun copyText(text: String)
    fun notify(scriptName: String, text: String)

    /** Asks whether [scriptName] may send requests to [host], which its `@connect` does not cover. */
    fun askToConnect(scriptName: String, host: String, answer: (Boolean) -> Unit)

    /** Runs JavaScript in the tab's page. */
    fun runInPage(js: String)
}

/**
 * One tab's user scripts: builds the code that runs them on each document and serves their privileged
 * GM_* calls through the `wsUserScripts` JavaScript interface.
 *
 * WebView has no isolated world for scripts, so the page can see the interface too. Each script therefore
 * gets a random token per document, and every call is checked against the script's `@grant`s (and
 * `@connect` for requests), so a page can at most use what a script that runs on it was granted.
 */
class UserScriptBridge(
    private val repo: UserScriptRepository,
    private val fetcher: HttpFetcher,
    private val host: UserScriptHost,
    private val main: Executor,
    private val io: Executor,
    private val appVersion: String,
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Running(val token: String, val scriptId: String, val scriptName: String, val pageHost: String)

    private val running = ConcurrentHashMap<String, Running>()
    private val requests = ConcurrentHashMap<String, Future<*>>()

    /** Main thread only, like [commands]. */
    private val injected = mutableSetOf<String>()
    private val commands = mutableListOf<MenuCommand>()

    /** Requests waiting for the user to allow a host, by script and host, so one question covers them all. */
    private val asking = mutableMapOf<String, MutableList<(Boolean) -> Unit>>()

    val menuCommands: List<MenuCommand> get() = commands.toList()

    /** A new document: the previous one's scripts lose their access, menu commands and requests. */
    fun newPage() {
        running.clear()
        injected.clear()
        commands.clear()
        asking.clear()
        requests.values.forEach { it.cancel(true) }
        requests.clear()
    }

    /** Code for each enabled script running on [url] at one of [phases] that has not run on this document. */
    fun injections(url: String, phases: Set<RunAt>): List<String> =
        repo.runningOn(url).filter { it.meta.runAt in phases && injected.add(it.id) }.map { script ->
            val token = newToken()
            running[token] = Running(token, script.id, script.meta.name, UrlPatterns.hostOf(url))
            UserScriptRuntime.injection(script, token, appVersion)
        }

    /** Runs a menu command the user picked. */
    fun runCommand(command: MenuCommand) {
        val key = JSONObject.quote(command.scriptId)
        host.runInPage("window.__wsGMmenu&&window.__wsGMmenu[$key]&&window.__wsGMmenu[$key](${JSONObject.quote(command.id)})")
    }

    @JavascriptInterface
    fun getValue(token: String?, key: String?): String? {
        val page = granted(token, GmApi.VALUES) ?: return null
        return repo.getValue(page.scriptId, key ?: return null)
    }

    @JavascriptInterface
    fun setValue(token: String?, key: String?, json: String?) {
        val page = granted(token, GmApi.VALUES) ?: return
        repo.setValue(page.scriptId, key ?: return, json ?: "null")
    }

    @JavascriptInterface
    fun deleteValue(token: String?, key: String?) {
        val page = granted(token, GmApi.VALUES) ?: return
        repo.deleteValue(page.scriptId, key ?: return)
    }

    @JavascriptInterface
    fun listValues(token: String?): String {
        val page = granted(token, GmApi.VALUES) ?: return "[]"
        return JSONArray(repo.valueKeys(page.scriptId)).toString()
    }

    @JavascriptInterface
    fun openInTab(token: String?, url: String?, background: Boolean) {
        granted(token, GmApi.OPEN_TAB) ?: return
        if (url == null || !UrlPatterns.isWeb(url)) return
        onMain(token) { host.openTab(url, background) }
    }

    @JavascriptInterface
    fun setClipboard(token: String?, text: String?) {
        granted(token, GmApi.CLIPBOARD) ?: return
        onMain(token) { host.copyText(text.orEmpty()) }
    }

    @JavascriptInterface
    fun notification(token: String?, text: String?) {
        val page = granted(token, GmApi.NOTIFICATION) ?: return
        onMain(token) { host.notify(page.scriptName, text.orEmpty()) }
    }

    @JavascriptInterface
    fun registerMenuCommand(token: String?, id: String?, caption: String?) {
        val page = granted(token, GmApi.MENU) ?: return
        if (id == null || caption.isNullOrBlank()) return
        onMain(token) {
            commands.removeAll { it.token == page.token && it.id == id }
            commands += MenuCommand(page.token, page.scriptId, id, caption, page.scriptName)
        }
    }

    @JavascriptInterface
    fun unregisterMenuCommand(token: String?, id: String?) {
        val page = granted(token, GmApi.MENU) ?: return
        onMain(token) { commands.removeAll { it.token == page.token && it.id == id } }
    }

    /** Starts a `GM_xmlhttpRequest`; the answer goes to the page's `__wsGMxhr[id]` callback. */
    @JavascriptInterface
    fun xhr(token: String?, id: String?, request: String?) {
        val page = granted(token, GmApi.XHR) ?: return
        if (id == null) return
        val xhr = runCatching { XhrRequest.decode(request) }.getOrElse {
            answer(page, id, XhrRequest.encodeError(it.message ?: "bad request"))
            return
        }
        if (mayConnect(page, xhr.host)) {
            send(page, id, xhr)
            return
        }
        onMain(page.token) {
            val proceed = { allowed: Boolean ->
                if (allowed) send(page, id, xhr) else answer(page, id, XhrRequest.encodeError("${xhr.host} is not allowed by @connect"))
            }
            val key = page.scriptId + "\n" + xhr.host
            asking[key]?.let {
                it += proceed
                return@onMain
            }
            asking[key] = mutableListOf(proceed)
            host.askToConnect(page.scriptName, xhr.host) { allowed ->
                if (allowed) repo.approveHost(page.scriptId, xhr.host)
                asking.remove(key).orEmpty().forEach { it(allowed) }
            }
        }
    }

    @JavascriptInterface
    fun abort(token: String?, id: String?) {
        if (token == null || id == null || running[token] == null) return
        requests.remove(requestKey(token, id))?.cancel(true)
    }

    /** The script [token] belongs to on this document, if it is still enabled and granted [api]. */
    private fun granted(token: String?, api: GmApi): Running? {
        val page = token?.let { running[it] } ?: return null
        val script = repo.find(page.scriptId) ?: return null
        return page.takeIf { script.enabled && script.meta.allows(api) }
    }

    private fun mayConnect(page: Running, targetHost: String): Boolean =
        repo.find(page.scriptId)?.mayConnect(targetHost, page.pageHost) == true

    private fun send(page: Running, id: String, xhr: XhrRequest) {
        val key = requestKey(page.token, id)
        val task = FutureTask {
            val result = try {
                // Each redirect must be allowed too, or a permitted host could bounce the request anywhere.
                xhr.encodeResponse(fetcher.fetch(xhr.http) { mayConnect(page, UrlPatterns.hostOf(it)) })
            } catch (e: SocketTimeoutException) {
                XhrRequest.encodeError("timeout", timeout = true)
            } catch (e: IOException) {
                XhrRequest.encodeError(e.message ?: "network error")
            }
            if (requests.remove(key) != null) answer(page, id, result)
        }
        requests[key] = task
        try {
            io.execute(task)
        } catch (e: RejectedExecutionException) {
            requests.remove(key)
            answer(page, id, XhrRequest.encodeError("ws is closing"))
        }
    }

    private fun answer(page: Running, id: String, json: String) {
        val quoted = JSONObject.quote(id)
        onMain(page.token) { host.runInPage("window.__wsGMxhr&&window.__wsGMxhr[$quoted]&&window.__wsGMxhr[$quoted]($json)") }
    }

    /** Runs [action] on the main thread unless the page that asked is gone by then. */
    private fun onMain(token: String?, action: () -> Unit) {
        main.execute { if (token != null && running.containsKey(token)) action() }
    }

    private fun requestKey(token: String, id: String) = "$token\n$id"

    companion object {
        const val INTERFACE = "wsUserScripts"
    }
}

/** The JavaScript that runs a user script on a page with Tampermonkey's GM_* APIs. */
object UserScriptRuntime {
    /** The names a script sees, in the order [RUNTIME] returns them. */
    private val API_NAMES = listOf(
        "GM_info", "GM", "unsafeWindow", "GM_getValue", "GM_setValue", "GM_deleteValue", "GM_listValues",
        "GM_addStyle", "GM_addElement", "GM_xmlhttpRequest", "GM_openInTab", "GM_setClipboard", "GM_notification",
        "GM_registerMenuCommand", "GM_unregisterMenuCommand", "GM_getResourceText", "GM_getResourceURL", "GM_log",
    )

    /**
     * The code that runs [script] once per document. The script's code (after its `@require`s) is the body of
     * a function whose parameters are the GM_* APIs, as in Tampermonkey; `@run-at document-end` waits for
     * DOMContentLoaded if the page is still loading.
     */
    fun injection(script: UserScript, token: String, appVersion: String): String {
        val id = JSONObject.quote(script.id)
        val waitForDom = script.meta.runAt == RunAt.DOCUMENT_END
        return buildString {
            append("(function(){var done=window.__wsUserScripts=window.__wsUserScripts||{};if(done[$id])return;done[$id]=1;")
            append("var api=(").append(RUNTIME).append(")(window.${UserScriptBridge.INTERFACE},")
            append(JSONObject.quote(token)).append(',').append(id).append(',').append(info(script, appVersion)).append(',').append(resources(script)).append(");")
            append("function run(){try{(function(").append(API_NAMES.joinToString(",")).append("){\n")
            script.requires.forEach { append(it).append("\n;\n") }
            append(script.source)
            append("\n}).call(window,").append(API_NAMES.joinToString(",") { "api.$it" }).append(");}")
            append("catch(e){console.error('[ws] user script '+").append(JSONObject.quote(script.meta.name)).append("+' failed',e);}}")
            if (waitForDom) append("if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',run,{once:true});else ")
            append("run();})()")
        }
    }

    private fun info(script: UserScript, appVersion: String): String {
        val m = script.meta
        val resources = JSONArray()
        script.resources.forEach { (name, r) -> resources.put(JSONObject().put("name", name).put("url", r.url)) }
        return JSONObject()
            .put("scriptHandler", "ws")
            .put("version", appVersion)
            .put("scriptMetaStr", m.block)
            .put("script", JSONObject()
                .put("name", m.name)
                .put("namespace", m.namespace)
                .put("version", m.version)
                .put("description", m.description)
                .put("author", m.author)
                .put("homepage", m.homepage)
                .put("matches", JSONArray(m.matches))
                .put("includes", JSONArray(m.includes))
                .put("excludes", JSONArray(m.excludes))
                .put("grant", JSONArray(m.grants))
                .put("resources", resources)
                .put("run-at", m.runAt.key))
            .toString()
    }

    private fun resources(script: UserScript): String = JSONObject().apply {
        script.resources.forEach { (name, r) -> put(name, JSONObject().put("mimeType", r.mimeType).put("base64", r.base64)) }
    }.toString()

    /**
     * Builds the GM_* APIs for one script: `(bridge, token, script id, GM_info, resources) -> {name: api}`.
     * The token never goes into a global the page can read; callbacks are keyed by the script id or random ids.
     */
    private val RUNTIME = """
        function(b,t,key,info,res){var menus={},seq=0;
        var xhrs=window.__wsGMxhr=window.__wsGMxhr||{},menuRoot=window.__wsGMmenu=window.__wsGMmenu||{};
        menuRoot[key]=function(id){var f=menus[id];if(typeof f==='function')try{f();}catch(e){console.error(e);}};
        function fire(f,a){if(typeof f==='function')try{f.call(a,a);}catch(e){console.error(e);}}
        function abs(u){return String(new URL(String(u),location.href));}
        function bytes(s){s=atob(s);var u=new Uint8Array(s.length);for(var i=0;i<s.length;i++)u[i]=s.charCodeAt(i);return u;}
        function later(f){return function(){var a=arguments;return new Promise(function(ok,ng){try{ok(f.apply(null,a));}catch(e){ng(e);}});};}
        function getValue(k,d){var v=b.getValue(t,String(k));if(v==null)return d;try{return JSON.parse(v);}catch(e){return d;}}
        function setValue(k,v){b.setValue(t,String(k),JSON.stringify(v===undefined?null:v));}
        function deleteValue(k){b.deleteValue(t,String(k));}
        function listValues(){try{return JSON.parse(b.listValues(t));}catch(e){return [];}}
        function addStyle(css){var s=document.createElement('style');s.textContent=css;(document.head||document.documentElement).appendChild(s);return s;}
        function addElement(p,tag,attrs){if(typeof p==='string'){attrs=tag;tag=p;p=null;}var e=document.createElement(tag);
          Object.keys(attrs||{}).forEach(function(k){if(k==='textContent'||k==='innerHTML')e[k]=attrs[k];else e.setAttribute(k,attrs[k]);});
          (p||document.head||document.documentElement).appendChild(e);return e;}
        function response(r,d){var type=String(d.responseType||'').toLowerCase(),text=r.text==null?'':r.text,body=text;
          if(r.base64!=null){var u=bytes(r.base64);body=type==='blob'?new Blob([u],{type:r.contentType||''}):u.buffer;}
          else if(type==='json'){try{body=JSON.parse(text);}catch(e){body=undefined;}}
          else if(type==='document'){try{body=new DOMParser().parseFromString(text,/xml/i.test(r.contentType||'')?'text/xml':'text/html');}catch(e){body=null;}}
          return {readyState:4,status:r.status||0,statusText:r.statusText||'',responseHeaders:r.headers||'',responseText:text,
            response:body,finalUrl:r.finalUrl||d.url,context:d.context,error:r.error};}
        function xhr(d){d=d||{};var id=Math.random().toString(36).slice(2)+'.'+(++seq),done=false,h=Object.assign({},d.headers);
          if(typeof URLSearchParams!=='undefined'&&d.data instanceof URLSearchParams&&
            !Object.keys(h).some(function(k){return /^content-type${'$'}/i.test(k);}))h['Content-Type']='application/x-www-form-urlencoded';
          var req={method:d.method||'GET',url:abs(d.url),headers:h,data:d.data==null?null:String(d.data),timeout:d.timeout||0,
            binary:/^(arraybuffer|blob)${'$'}/i.test(d.responseType||''),anonymous:!!d.anonymous,overrideMimeType:d.overrideMimeType||''};
          xhrs[id]=function(r){delete xhrs[id];if(done)return;done=true;var res=response(r,d);
            if(r.error)fire(r.timeout&&d.ontimeout?d.ontimeout:d.onerror,res);else{fire(d.onreadystatechange,res);fire(d.onload,res);}
            fire(d.onloadend,res);};
          fire(d.onloadstart,{readyState:1,context:d.context});b.xhr(t,id,JSON.stringify(req));
          return {abort:function(){if(done)return;done=true;delete xhrs[id];b.abort(t,id);fire(d.onabort,{readyState:4,status:0,context:d.context});}};}
        function xhrPromise(d){d=d||{};var h,p=new Promise(function(ok,ng){h=xhr(Object.assign({},d,{
          onload:function(r){fire(d.onload,r);ok(r);},onerror:function(r){fire(d.onerror,r);ng(r);},
          ontimeout:function(r){fire(d.ontimeout,r);ng(r);},onabort:function(r){fire(d.onabort,r);ng(r);}}));});
          p.abort=function(){h.abort();};return p;}
        function openInTab(u,o){var bg=typeof o==='boolean'?o:!!(o&&typeof o==='object'&&!o.active);
          b.openInTab(t,abs(u),bg);return {closed:false,close:function(){},onclose:null};}
        function setClipboard(d){b.setClipboard(t,String(d));}
        function notification(d,title){var o=d&&typeof d==='object';var text=o?(d.text||d.title):(d||title);b.notification(t,String(text==null?'':text));}
        function registerMenuCommand(c,f,o){var id=o&&typeof o==='object'&&o.id!=null?String(o.id):'m'+(++seq);
          menus[id]=f;b.registerMenuCommand(t,id,String(c));return id;}
        function unregisterMenuCommand(id){delete menus[id];b.unregisterMenuCommand(t,String(id));}
        function getResourceText(n){var r=res[n];return r?new TextDecoder().decode(bytes(r.base64)):null;}
        function getResourceURL(n){var r=res[n];return r?'data:'+(r.mimeType||'application/octet-stream')+';base64,'+r.base64:null;}
        function log(){console.log.apply(console,arguments);}
        var GM={info:info,getValue:later(getValue),setValue:later(setValue),deleteValue:later(deleteValue),
          listValues:later(listValues),addStyle:later(addStyle),addElement:later(addElement),xmlHttpRequest:xhrPromise,
          openInTab:later(openInTab),setClipboard:later(setClipboard),notification:later(notification),
          registerMenuCommand:registerMenuCommand,unregisterMenuCommand:unregisterMenuCommand,
          getResourceText:later(getResourceText),getResourceUrl:later(getResourceURL),log:log};
        return {GM_info:info,GM:GM,unsafeWindow:window,GM_getValue:getValue,GM_setValue:setValue,GM_deleteValue:deleteValue,
          GM_listValues:listValues,GM_addStyle:addStyle,GM_addElement:addElement,GM_xmlhttpRequest:xhr,GM_openInTab:openInTab,
          GM_setClipboard:setClipboard,GM_notification:notification,GM_registerMenuCommand:registerMenuCommand,
          GM_unregisterMenuCommand:unregisterMenuCommand,GM_getResourceText:getResourceText,GM_getResourceURL:getResourceURL,GM_log:log};}
    """.trimIndent()
}

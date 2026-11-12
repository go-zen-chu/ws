package xyz.amasuda.ws

/**
 * Vimium-style link hints: F on a page labels every visible link and control, and typing a label clicks it
 * (Shift+F opens the link in a background tab). Esc or Backspace past the start cancels.
 *
 * The keys are caught by the injected script rather than the app, because only the page knows whether
 * the user is typing in a text field; there F stays an ordinary letter. Labels come from [labels] through
 * the `wsHints` JavaScript interface so that the label logic is shared and testable.
 */
object LinkHints {
    const val INTERFACE = "wsHints"

    /** Vimium's default hint characters: easy to reach and hard to confuse with each other. */
    const val ALPHABET = "sadfjklewcmpgh"
    const val MAX_HINTS = 2000

    /**
     * Labels for [count] targets. No label is a prefix of another, so a hint fires as soon as its label is
     * typed; with few targets they are one character long.
     */
    fun labels(count: Int, alphabet: String = ALPHABET): List<String> {
        val n = count.coerceIn(0, MAX_HINTS)
        if (n == 0 || alphabet.isEmpty()) return emptyList()
        val nodes = mutableListOf("")
        var expanded = 0
        while (nodes.size - expanded < n || nodes.size == 1) {
            val prefix = nodes[expanded++]
            alphabet.forEach { nodes += it + prefix }
        }
        // Built back to front and sorted by the last character, so the first characters of the labels
        // are spread over the alphabet instead of piling up on its first letters.
        return nodes.subList(expanded, expanded + n).sorted().map { it.reversed() }
    }

    /** JavaScript that shows the hints, for the keyboard shortcut that works even outside the page. */
    fun showScript(newTab: Boolean): String = "window.__wsHints&&window.__wsHints.show($newTab)"

    val SCRIPT = """
        (function(){if(window.__wsHints)return;
        var SELECTOR='a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],' +
          '[role=tab],[role=menuitem],[role=checkbox],[role=radio],[role=option],[role=switch],[onclick],' +
          '[contenteditable=""],[contenteditable=true],[tabindex]:not([tabindex="-1"])';
        var host=null,root=null,hints=[],typed='',newTab=false,swallow=false;
        function deepActive(){var a=document.activeElement;
          while(a&&a.shadowRoot&&a.shadowRoot.activeElement)a=a.shadowRoot.activeElement;return a;}
        function editable(el){if(!el)return false;if(el.isContentEditable)return true;var t=el.tagName;
          if(t==='TEXTAREA'||t==='SELECT')return true;
          return t==='INPUT'&&!/^(button|submit|reset|checkbox|radio|image|file|range|color)${'$'}/i.test(el.type);}
        function all(scope,out){scope.querySelectorAll(SELECTOR).forEach(function(el){out.push(el);});
          scope.querySelectorAll('*').forEach(function(el){if(el.shadowRoot)all(el.shadowRoot,out);});return out;}
        function insideControl(el){var p=el.parentElement;return !!(p&&p.closest('a[href],button'));}
        function spot(el){if(el.disabled)return null;var rs=el.getClientRects();
          for(var i=0;i<rs.length;i++){var r=rs[i];if(r.width<1||r.height<1)continue;
            var l=Math.max(r.left,0),t=Math.max(r.top,0),rr=Math.min(r.right,innerWidth),b=Math.min(r.bottom,innerHeight);
            if(rr<=l||b<=t)continue;
            var hit=el.getRootNode().elementFromPoint((l+rr)/2,(t+b)/2);
            if(hit&&(hit===el||el.contains(hit)))return {x:l,y:t};}
          return null;}
        function render(){hints.forEach(function(h){var on=h.label.indexOf(typed)===0;h.node.style.display=on?'':'none';
          if(on)h.node.innerHTML='<b>'+typed+'</b>'+h.label.slice(typed.length);});}
        function hide(){if(!host)return;host.remove();host=null;hints=[];typed='';
          removeEventListener('scroll',hide,true);removeEventListener('resize',hide,true);
          removeEventListener('mousedown',hide,true);removeEventListener('touchstart',hide,true);}
        function show(inNewTab){hide();var targets=[];
          all(document,[]).forEach(function(el){if(insideControl(el))return;var p=spot(el);if(p)targets.push({el:el,p:p});});
          var labels=JSON.parse(window.$INTERFACE.labels(targets.length));if(!labels.length)return;
          newTab=!!inNewTab;host=document.createElement('div');
          host.style.cssText='position:fixed;inset:0;z-index:2147483647;pointer-events:none';
          root=host.attachShadow({mode:'closed'});
          root.innerHTML='<style>span{position:fixed;font:bold 12px/1 monospace;padding:2px 3px;color:#302505;' +
            'background:#ffd76e;border:1px solid #c38a22;border-radius:3px;box-shadow:0 1px 3px rgba(0,0,0,.3);' +
            'text-transform:uppercase;white-space:nowrap}b{color:#a57a14}</style>';
          labels.forEach(function(label,i){var s=document.createElement('span');s.textContent=label;
            s.style.left=targets[i].p.x+'px';s.style.top=targets[i].p.y+'px';root.appendChild(s);
            hints.push({label:label,el:targets[i].el,node:s});});
          document.documentElement.appendChild(host);
          addEventListener('scroll',hide,{capture:true,passive:true});addEventListener('resize',hide,true);
          addEventListener('mousedown',hide,true);addEventListener('touchstart',hide,{capture:true,passive:true});}
        function activate(el){var tab=newTab;hide();var a=el.closest('a[href]');
          if(tab&&a&&/^https?:/i.test(a.href)){window.$INTERFACE.openInNewTab(a.href);return;}
          el.focus({preventScroll:true});if(!editable(el))el.click();}
        function type(key){var next=typed+key;
          if(!hints.some(function(h){return h.label.indexOf(next)===0;}))return;typed=next;
          var hit=hints.filter(function(h){return h.label===typed;})[0];if(hit)activate(hit.el);else render();}
        function onKey(e){if(e.isComposing)return;
          if(host){if(e.key==='Escape')hide();
            else if(e.key==='Backspace'){if(typed)typed=typed.slice(0,-1),render();else hide();}
            else if(e.key.length===1&&!e.ctrlKey&&!e.altKey&&!e.metaKey)type(e.key.toLowerCase());
            else if(e.key!=='Shift'){hide();return;}
            swallow=true;e.preventDefault();e.stopImmediatePropagation();return;}
          if(e.ctrlKey||e.altKey||e.metaKey||e.repeat||(e.key!=='f'&&e.key!=='F')||editable(deepActive()))return;
          swallow=true;e.preventDefault();e.stopImmediatePropagation();show(e.key==='F');}
        function onOther(e){if(!swallow)return;e.preventDefault();e.stopImmediatePropagation();if(e.type==='keyup')swallow=false;}
        addEventListener('keydown',onKey,true);addEventListener('keypress',onOther,true);addEventListener('keyup',onOther,true);
        window.__wsHints={show:show};})()
    """.trimIndent()
}

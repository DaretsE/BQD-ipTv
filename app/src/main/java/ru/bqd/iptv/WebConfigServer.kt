package ru.bqd.iptv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            try {
                val i = Intent(context, PlayerActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            } catch (_: Exception) { }
        }
    }
}

object IpUtil {
    fun localIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    val host = a.hostAddress ?: continue
                    if (!a.isLoopbackAddress && !host.contains(":")) return host
                }
            }
        } catch (_: Exception) { }
        return "0.0.0.0"
    }
}

class WebConfigServer(private val onAction: (String) -> Unit) : NanoHTTPD(PORT) {

    companion object { const val PORT = 8765 }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/" -> html(PAGE)
                session.uri == "/api/state" -> jsonState()
                session.method == Method.POST -> handlePost(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun html(s: String) = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", s)

    private fun params(session: IHTTPSession): Map<String, String> {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val out = HashMap<String, String>()
        for ((k, v) in session.parms) out[k] = v ?: ""
        return out
    }

    private fun handlePost(session: IHTTPSession): Response {
        val p = params(session)
        when (session.uri) {
            "/api/add" -> {
                val url = p["url"]?.trim() ?: ""
                if (url.startsWith("http")) { Store.addPlaylist(p["name"]?.trim() ?: "", url); onAction("changed"); return ok() }
                return bad("Ссылка должна начинаться с http")
            }
            "/api/del" -> { Store.removePlaylist(p["url"] ?: ""); onAction("changed"); return ok() }
            "/api/hide" -> {
                val list = Store.getPlaylistCfgs()
                list.find { it.url == p["url"] }?.let { it.hidden = p["hidden"] == "1" }
                Store.savePlaylistCfgs(list); onAction("changed"); return ok()
            }
            "/api/rename" -> {
                val n = p["name"]?.trim() ?: ""
                if (n.isNotEmpty()) Store.renamePlaylist(p["url"] ?: "", n)
                onAction("changed"); return ok()
            }
            "/api/epgadd" -> {
                val url = p["url"]?.trim() ?: ""
                if (url.startsWith("http")) { Store.addEpgSource(p["name"]?.trim() ?: "", url); onAction("refresh_epg"); return ok() }
                return bad("Ссылка должна начинаться с http")
            }
            "/api/epgdel" -> { Store.removeEpgSource(p["url"] ?: ""); onAction("refresh_epg"); return ok() }
            "/api/epgname" -> {
                val n = p["name"]?.trim() ?: ""
                if (n.isNotEmpty()) Store.renameEpgSource(p["url"] ?: "", n)
                onAction("changed"); return ok()
            }
            "/api/refreshpl" -> { onAction("refresh_pl"); return ok() }
            "/api/refreshepg" -> { onAction("refresh_epg"); return ok() }
            "/api/favorder" -> {
                try {
                    val arr = JSONArray(p["order"] ?: "[]")
                    val byUrl = Store.getFavorites().associateBy { it.first }
                    val newList = ArrayList<Pair<String, String>>()
                    for (i in 0 until arr.length()) byUrl[arr.getString(i)]?.let { newList.add(it) }
                    for (c in Store.getFavorites()) if (newList.none { it.first == c.first }) newList.add(c)
                    Store.saveFavorites(newList); Store.favManualOrder = true; onAction("changed"); return ok()
                } catch (e: Exception) { return bad(e.message ?: "err") }
            }
            "/api/favdel" -> { Store.saveFavorites(Store.getFavorites().filter { it.first != p["url"] }); onAction("changed"); return ok() }
            "/api/favauto" -> { Store.favManualOrder = false; onAction("changed"); return ok() }
        }
        return bad("unknown")
    }

    private fun ok() = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}")
    private fun bad(msg: String) = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
        JSONObject().put("ok", false).put("error", msg).toString())

    private fun jsonState(): Response {
        val o = JSONObject()
        val pls = JSONArray()
        for (p in Store.getPlaylistCfgs())
            pls.put(JSONObject().put("name", p.name).put("url", p.url).put("hidden", p.hidden))
        o.put("playlists", pls)
        val epg = JSONArray()
        for (s in Store.getEpgSources()) epg.put(JSONObject().put("name", s.name).put("url", s.url))
        o.put("epg", epg)
        o.put("epgStatus", EpgManager.status())
        val favs = JSONArray()
        for (f in Store.getFavorites()) favs.put(JSONObject().put("url", f.first).put("name", f.second))
        o.put("favorites", favs)
        o.put("favManual", Store.favManualOrder)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())
    }

    private val PAGE = """<!DOCTYPE html>
<html lang="ru"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>BQDiptv</title>
<style>
:root{--bg:#0b0f14;--card:#161c24;--card2:#1d2530;--line:#2a3340;--text:#e9eef5;--muted:#8a97a8;--acc:#7aa61f;--acc2:#3b82f6;--gold:#f5b50a;--red:#ef4444;}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:var(--bg);color:var(--text);margin:0;padding:0 0 50px;}
.top{position:sticky;top:0;z-index:10;background:linear-gradient(135deg,#5c7d18,#8fb52b);padding:18px 18px 14px;box-shadow:0 4px 20px rgba(0,0,0,.4)}
.top h1{margin:0;font-size:22px;font-weight:800;letter-spacing:.3px}
.top p{margin:4px 0 0;font-size:13px;opacity:.9}
.wrap{max-width:560px;margin:0 auto;padding:0 14px}
.sec{margin-top:22px}
.sec>h2{font-size:13px;text-transform:uppercase;letter-spacing:1px;color:var(--muted);margin:0 0 10px 2px;font-weight:700}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:14px;margin-bottom:10px;box-shadow:0 2px 10px rgba(0,0,0,.25)}
input{width:100%;padding:13px 14px;margin:7px 0;border-radius:12px;border:1px solid var(--line);background:var(--card2);color:#fff;font-size:15px;outline:none}
input:focus{border-color:var(--acc)}
button{padding:12px 16px;border:none;border-radius:12px;background:var(--acc);color:#fff;font-size:15px;font-weight:700;width:100%;margin-top:4px;cursor:pointer;transition:.15s}
button:active{transform:scale(.98)}
button.sec-btn{background:var(--card2);border:1px solid var(--line);font-weight:600}
button.blue{background:var(--acc2)}
.chips{display:flex;gap:8px;flex-wrap:wrap;margin-top:6px}
.chip{font-size:12px;padding:7px 11px;border-radius:20px;background:var(--card2);border:1px solid var(--line);color:var(--muted);cursor:pointer}
.row{display:flex;align-items:center;gap:10px}
.row .grow{flex:1;min-width:0}
.name{font-weight:700;font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.url{font-size:11px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:2px}
.iconbtn{width:40px;height:40px;border-radius:10px;padding:0;font-size:16px;background:var(--card2);border:1px solid var(--line);flex:none}
.iconbtn.red{color:#fff;background:rgba(239,68,68,.18);border-color:rgba(239,68,68,.4)}
.tag{font-size:11px;padding:2px 8px;border-radius:8px;background:rgba(245,181,10,.15);color:var(--gold);margin-left:6px}
.hint{font-size:12px;color:var(--muted);margin:8px 2px 0;line-height:1.5}
.statusline{font-size:12px;color:var(--acc);margin:6px 2px 0}
.toast{position:fixed;left:50%;bottom:22px;transform:translateX(-50%);background:#0f1722;border:1px solid var(--line);padding:12px 18px;border-radius:12px;opacity:0;transition:.25s;z-index:50;font-size:14px;box-shadow:0 6px 24px rgba(0,0,0,.5)}
.toast.show{opacity:1}
.empty{color:var(--muted);font-size:14px;text-align:center;padding:14px}
.two{display:flex;gap:8px}
.two button{flex:1}
</style></head><body>
<div class="top"><h1>bqd ip-tv — настройки</h1><p>Изменения применяются на ТВ сразу</p></div>
<div class="wrap">

  <div class="sec"><h2>Обновить данные</h2>
    <div class="card two">
      <button class="sec-btn" onclick="refresh('pl')">🔄 Обновить плейлисты</button>
      <button class="sec-btn" onclick="refresh('epg')">🔄 Обновить программу</button>
    </div>
  </div>

  <div class="sec"><h2>Добавить плейлист M3U</h2>
    <div class="card">
      <input id="plname" placeholder="Название (необязательно)">
      <input id="plurl" placeholder="https://… ссылка на .m3u" inputmode="url">
      <button onclick="addPl()">＋ Добавить и запустить на ТВ</button>
    </div>
  </div>

  <div class="sec"><h2>Мои плейлисты</h2><div id="pls"></div></div>

  <div class="sec"><h2>Программа передач (EPG · XMLTV)</h2>
    <div class="card">
      <input id="epgname" placeholder="Название источника (необязательно)">
      <input id="epgurl" placeholder="https://… xmltv (можно .xml.gz)" inputmode="url">
      <button onclick="addEpg()">＋ Добавить источник EPG</button>
      <div class="chips">
        <span class="chip" onclick="fillEpg('http://epg.one/epg2.xml.gz')">epg.one</span>
        <span class="chip" onclick="fillEpg('http://epg.it999.ru/edem.xml.gz')">it999 / edem</span>
        <span class="chip" onclick="fillEpg('https://iptvx.one/epg/epg.xml.gz')">iptvx.one</span>
      </div>
      <div id="epgstatus" class="statusline"></div>
    </div>
    <div id="epgs"></div>
  </div>

  <div class="sec"><h2>⭐ Избранное · порядок на ТВ</h2>
    <div id="favmode" class="hint" style="margin-top:0"></div>
    <div id="favs"></div>
    <button class="sec-btn" onclick="favAuto()">↺ Авто-сортировка по частоте просмотра</button>
  </div>
</div>
<div id="toast" class="toast"></div>

<script>
function post(u,d){var b=new URLSearchParams();for(var k in d)b.append(k,d[k]);
 return fetch(u,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b.toString()}).then(function(r){return r.json();});}
function toast(t){var e=document.getElementById('toast');e.innerText=t;e.classList.add('show');setTimeout(function(){e.classList.remove('show')},1800);}
function esc(t){var d=document.createElement('div');d.innerText=t==null?'':t;return d.innerHTML;}
function enc(s){return encodeURIComponent(s);}
var state={favorites:[]};
function load(){fetch('/api/state').then(function(r){return r.json();}).then(function(s){state=s;render(s);});}
function render(s){
 var h='';
 (s.playlists||[]).forEach(function(p){
  h+='<div class="card"><div class="row"><div class="grow"><div class="name">'+esc(p.name)+(p.hidden?'<span class="tag">скрыт</span>':'')+'</div><div class="url">'+esc(p.url)+'</div></div></div>'+
     '<div class="row" style="margin-top:10px;gap:8px">'+
     '<button class="sec-btn" style="flex:1" onclick="renamePl(\''+enc(p.url)+'\')">✏️ Имя</button>'+
     '<button class="sec-btn" style="flex:1" onclick="hidePl(\''+enc(p.url)+'\','+(p.hidden?0:1)+')">'+(p.hidden?'👁 Показать':'🙈 Скрыть')+'</button>'+
     '<button class="iconbtn red" onclick="delPl(\''+enc(p.url)+'\')">🗑</button></div></div>';
 });
 document.getElementById('pls').innerHTML=h||'<div class="empty">Пока нет плейлистов</div>';

 document.getElementById('epgstatus').innerText=s.epgStatus||'';
 var e='';
 (s.epg||[]).forEach(function(x){
  e+='<div class="card"><div class="row"><div class="grow"><div class="name">'+esc(x.name)+'</div><div class="url">'+esc(x.url)+'</div></div></div>'+
     '<div class="row" style="margin-top:10px;gap:8px">'+
     '<button class="sec-btn" style="flex:1" onclick="renameEpg(\''+enc(x.url)+'\')">✏️ Имя</button>'+
     '<button class="iconbtn red" onclick="delEpg(\''+enc(x.url)+'\')">🗑</button></div></div>';
 });
 document.getElementById('epgs').innerHTML=e||'<div class="empty">Источники EPG не добавлены</div>';

 document.getElementById('favmode').innerText=s.favManual?'Режим: ручной порядок (стрелки ниже)':'Режим: авто — чем чаще смотрите, тем выше';
 var f='';
 (s.favorites||[]).forEach(function(c,i){
  f+='<div class="card row"><div class="grow"><div class="name">'+esc(c.name)+'</div></div>'+
     '<button class="iconbtn" onclick="favMove('+i+',-1)">▲</button>'+
     '<button class="iconbtn" onclick="favMove('+i+',1)">▼</button>'+
     '<button class="iconbtn red" onclick="favDel(\''+enc(c.url)+'\')">✕</button></div>';
 });
 document.getElementById('favs').innerHTML=f||'<div class="empty">Избранное пусто. Удерживайте ОК на пульте, чтобы добавить канал.</div>';
}
function refresh(w){post(w==='pl'?'/api/refreshpl':'/api/refreshepg',{}).then(function(){toast('Обновляю на ТВ…');});}
function addPl(){var u=document.getElementById('plurl').value;if(!u){toast('Вставьте ссылку');return;}
 post('/api/add',{name:document.getElementById('plname').value,url:u}).then(function(r){
  if(r.ok){toast('✔ Отправлено на ТВ');document.getElementById('plurl').value='';document.getElementById('plname').value='';}else toast(r.error);load();});}
function delPl(u){if(confirm('Удалить плейлист?'))post('/api/del',{url:decodeURIComponent(u)}).then(function(){toast('Удалено');load();});}
function hidePl(u,h){post('/api/hide',{url:decodeURIComponent(u),hidden:h}).then(load);}
function renamePl(u){var n=prompt('Новое название плейлиста:');if(n)post('/api/rename',{url:decodeURIComponent(u),name:n}).then(load);}
function fillEpg(u){document.getElementById('epgurl').value=u;}
function addEpg(){var u=document.getElementById('epgurl').value;if(!u){toast('Вставьте ссылку EPG');return;}
 post('/api/epgadd',{name:document.getElementById('epgname').value,url:u}).then(function(r){if(r.ok){toast('✔ Источник добавлен');document.getElementById('epgurl').value='';document.getElementById('epgname').value='';}else toast(r.error);load();});}
function delEpg(u){post('/api/epgdel',{url:decodeURIComponent(u)}).then(function(){toast('Удалено');load();});}
function renameEpg(u){var n=prompt('Новое название источника:');if(n)post('/api/epgname',{url:decodeURIComponent(u),name:n}).then(load);}
function favDel(u){post('/api/favdel',{url:decodeURIComponent(u)}).then(load);}
function favMove(i,d){var a=state.favorites.map(function(c){return c.url;});var j=i+d;if(j<0||j>=a.length)return;
 var t=a[i];a[i]=a[j];a[j]=t;post('/api/favorder',{order:JSON.stringify(a)}).then(load);}
function favAuto(){post('/api/favauto',{}).then(function(){toast('Авто-сортировка включена');load();});}
load();setInterval(load,5000);
</script></body></html>"""
}

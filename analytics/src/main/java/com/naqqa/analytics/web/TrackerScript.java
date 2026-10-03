package com.naqqa.analytics.web;

public final class TrackerScript {

    private static final String TEMPLATE = "(function(w,d){'use strict';if(w.naqqaTracker&&w.naqqaTracker.track)return;"
            + "var E='__ENDPOINT__',S=w.sessionStorage,id=function(){return(w.crypto&&w.crypto.randomUUID)?w.crypto.randomUUID():"
            + "('x'+Date.now().toString(36)+Math.random().toString(36).slice(2,12));},sid;"
            + "try{sid=S.getItem('naqqa_sid');if(!sid){sid=id();S.setItem('naqqa_sid',sid);}}catch(e){sid=id();}"
            + "var full=w.naqqaConsent==='full',vid=null;if(full){try{vid=w.localStorage.getItem('naqqa_vid');"
            + "if(!vid){vid=id();w.localStorage.setItem('naqqa_vid',vid);}}catch(e){}}"
            + "var q=new URLSearchParams(w.location.search),u={source:q.get('utm_source')||'',medium:q.get('utm_medium')||'',"
            + "campaign:q.get('utm_campaign')||'',term:q.get('utm_term')||'',content:q.get('utm_content')||''},"
            + "ck=['gclid','fbclid','yclid','msclkid','ttclid'].filter(function(k){return q.has(k);})[0]||null;"
            + "function send(evs){var b=JSON.stringify({v:1,vid:vid,sid:sid,uid:null,consent:full?'full':'cookieless',"
            + "ctx:{lang:(d.documentElement.lang||'').slice(0,2),path:w.location.pathname,pageType:w.naqqaPageType||'other',"
            + "ref:d.referrer||'',utm:u,click:ck,refParam:q.get('ref'),screen:screen.width+'x'+screen.height,"
            + "viewport:w.innerWidth+'x'+w.innerHeight,app:null},events:evs});"
            + "if(navigator.sendBeacon&&navigator.sendBeacon(E,new Blob([b],{type:'text/plain'})))return;"
            + "try{fetch(E,{method:'POST',body:b,keepalive:true,credentials:'same-origin',headers:{'Content-Type':'text/plain'}});}catch(e){}}"
            + "function track(n,p){send([{id:id(),t:Date.now(),name:n,props:p||{}}]);}"
            + "w.naqqaTracker={track:track};track('page_view',w.naqqaPageProps||{});"
            + "})(window,document);";

    private TrackerScript() {
    }

    public static String render(String endpoint) {
        String e = endpoint == null ? "/t/e" : endpoint.replace("\\", "").replace("'", "");
        return TEMPLATE.replace("__ENDPOINT__", e);
    }
}
